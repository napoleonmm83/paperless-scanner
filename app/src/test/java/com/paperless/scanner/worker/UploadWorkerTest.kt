package com.paperless.scanner.worker

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.ListenableWorker
import com.paperless.scanner.data.database.PendingUpload
import com.paperless.scanner.data.database.UploadStatus
import com.paperless.scanner.data.health.ServerStatus
import com.paperless.scanner.domain.error.ServerOfflineReason
import com.paperless.scanner.testing.fakes.FakeCrashlyticsHelper
import com.paperless.scanner.testing.fakes.FakeDocumentRepository
import com.paperless.scanner.testing.fakes.FakeNetworkMonitor
import com.paperless.scanner.testing.fakes.FakeServerHealthMonitor
import com.paperless.scanner.testing.fakes.FakeSyncHistoryRepository
import com.paperless.scanner.testing.fakes.FakeTaskRepository
import com.paperless.scanner.testing.fakes.FakeUploadQueueRepository
import com.paperless.scanner.util.FileUtils
import com.google.common.util.concurrent.ListenableFuture
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.Runs
import io.mockk.spyk
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit tests for UploadWorker.
 *
 * Uses Robolectric for Android framework mocking (Context, NotificationManager).
 * Tests cover:
 * - No pending uploads
 * - Single upload success/failure
 * - Multi-page upload success/failure
 * - Multiple uploads with mixed results
 * - Retry limit behavior
 * - Safety limits for iteration
 *
 * #202 (plan-03): all collaborators are the typed fakes from testing/fakes/
 * (compile-time-checked against the #321 contracts); assertions inspect recorded
 * data instead of relaxed verify calls. The worker itself stays a spyk — its
 * WorkManager surface (setForeground/setProgressAsync) is the test subject's own
 * framework boundary, not a collaborator.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class UploadWorkerTest {

    private lateinit var context: Context
    private lateinit var uploadQueueRepository: FakeUploadQueueRepository
    private lateinit var documentRepository: FakeDocumentRepository
    private lateinit var taskRepository: FakeTaskRepository
    private lateinit var networkMonitor: FakeNetworkMonitor
    private lateinit var serverHealthMonitor: FakeServerHealthMonitor
    private lateinit var syncHistoryRepository: FakeSyncHistoryRepository
    private lateinit var crashlyticsHelper: FakeCrashlyticsHelper

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        uploadQueueRepository = FakeUploadQueueRepository()
        documentRepository = FakeDocumentRepository()
        taskRepository = FakeTaskRepository()
        // Defaults: validated internet + reachable server.
        networkMonitor = FakeNetworkMonitor(initiallyOnline = true)
        serverHealthMonitor = FakeServerHealthMonitor(initiallyReachable = true)
        serverHealthMonitor.status.value = ServerStatus.Online(System.currentTimeMillis())
        syncHistoryRepository = FakeSyncHistoryRepository()
        crashlyticsHelper = FakeCrashlyticsHelper()

        // Mock Android Log class to avoid "Method not mocked" errors
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.v(any(), any()) } returns 0

        // Mock FileUtils object for file existence checks
        mockkObject(FileUtils)
        every { FileUtils.fileExists(any()) } returns true
        every { FileUtils.getFileSize(any()) } returns 1024L  // 1KB default
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
        unmockkObject(FileUtils)
    }

    private fun createWorker(): UploadWorker {
        val worker = spyk(
            UploadWorker(
                context = context,
                workerParams = mockk(relaxed = true),
                uploadQueueRepository = uploadQueueRepository,
                documentRepository = documentRepository,
                taskRepository = taskRepository,
                networkMonitor = networkMonitor,
                serverHealthMonitor = serverHealthMonitor,
                syncHistoryRepository = syncHistoryRepository,
                crashlyticsHelper = crashlyticsHelper
            )
        )
        // Mock suspend function setForeground to avoid WorkManager context issues
        coEvery { worker.setForeground(any()) } just Runs
        // Mock setProgressAsync - returns immediately completed future
        every { worker.setProgressAsync(any()) } returns mockk<ListenableFuture<Void>> {
            every { isDone } returns true
            every { get() } returns null
        }
        return worker
    }

    private fun markCalls(name: String, id: Long): Int =
        uploadQueueRepository.recordedCalls.count { it.startsWith("$name($id") }

    // ==================== Pre-Check Tests ====================

    @Test
    fun `doWork retries when server is not reachable`() = runTest {
        // Given: Server is not reachable
        serverHealthMonitor.reachable.value = false
        serverHealthMonitor.status.value = ServerStatus.Offline(ServerOfflineReason.UNKNOWN)

        val worker = createWorker()
        val result = worker.doWork()

        // Then: Worker returns retry
        assertEquals(ListenableWorker.Result.retry(), result)

        // And: No uploads were attempted
        assertEquals(0, uploadQueueRepository.recordedCalls.count { it == "getNextPendingUpload" })
    }

    // ==================== No Pending Uploads Tests ====================

    @Test
    fun `doWork returns success when no pending uploads`() = runTest {
        val worker = createWorker()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
    }

    // ==================== Single Upload Tests ====================

    @Test
    fun `doWork processes single upload successfully`() = runTest {
        uploadQueueRepository.enqueue(createPendingUpload(id = 1, uri = "content://test/doc1.pdf"))
        documentRepository.defaultUploadResult = Result.success("task-123")

        val worker = createWorker()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, markCalls("markAsUploading", 1))
        assertEquals(1, markCalls("markAsCompleted", 1))
        // Refresh tasks so the new server-side consumption task shows up in the
        // "In Verarbeitung" list immediately (reactive Room flow).
        assertEquals(listOf(true), taskRepository.getTasksCalls)
    }

    @Test
    fun `doWork marks upload as failed on error`() = runTest {
        uploadQueueRepository.enqueue(createPendingUpload(id = 1, uri = "content://test/doc1.pdf"))
        documentRepository.defaultUploadResult = Result.failure(Exception("Network error"))

        val worker = createWorker()
        val result = worker.doWork()

        // The worker drained the queue; the per-item failure is persisted via
        // markAsFailed, so the work unit itself succeeds (#128 success-after-drain).
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, markCalls("markAsUploading", 1))
        assertTrue(uploadQueueRepository.recordedCalls.contains("markAsFailed(1, Network error)"))
    }

    @Test
    fun `doWork uses document title when available`() = runTest {
        uploadQueueRepository.enqueue(
            createPendingUpload(id = 1, uri = "content://test/doc1.pdf", title = "My Invoice")
        )

        val worker = createWorker()
        worker.doWork()

        assertEquals("My Invoice", documentRepository.uploads.single().title)
    }

    @Test
    fun `doWork passes tag ids to repository`() = runTest {
        val tagIds = listOf(1, 2, 3)
        uploadQueueRepository.enqueue(
            createPendingUpload(id = 1, uri = "content://test/doc1.pdf", tagIds = tagIds)
        )

        val worker = createWorker()
        worker.doWork()

        assertEquals(tagIds, documentRepository.uploads.single().tagIds)
    }

    @Test
    fun `doWork passes document type id to repository`() = runTest {
        uploadQueueRepository.enqueue(
            createPendingUpload(id = 1, uri = "content://test/doc1.pdf", documentTypeId = 5)
        )

        val worker = createWorker()
        worker.doWork()

        assertEquals(5, documentRepository.uploads.single().documentTypeId)
    }

    @Test
    fun `doWork passes correspondent id to repository`() = runTest {
        uploadQueueRepository.enqueue(
            createPendingUpload(id = 1, uri = "content://test/doc1.pdf", correspondentId = 10)
        )

        val worker = createWorker()
        worker.doWork()

        assertEquals(10, documentRepository.uploads.single().correspondentId)
    }

    // ==================== Multi-Page Upload Tests ====================

    @Test
    fun `doWork processes multi-page upload successfully`() = runTest {
        uploadQueueRepository.enqueue(
            createPendingUpload(
                id = 1,
                uri = "content://test/page1.jpg",
                isMultiPage = true,
                additionalUris = listOf("content://test/page2.jpg", "content://test/page3.jpg")
            )
        )
        documentRepository.defaultUploadResult = Result.success("task-456")

        val expectedUris = listOf(
            Uri.parse("content://test/page1.jpg"),
            Uri.parse("content://test/page2.jpg"),
            Uri.parse("content://test/page3.jpg")
        )

        val worker = createWorker()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, markCalls("markAsCompleted", 1))
        val upload = documentRepository.uploads.single()
        assertTrue(upload.isMultiPage)
        assertEquals(expectedUris, upload.uris)
    }

    @Test
    fun `doWork marks multi-page upload as failed on error`() = runTest {
        uploadQueueRepository.enqueue(
            createPendingUpload(id = 1, uri = "content://test/page1.jpg", isMultiPage = true)
        )
        documentRepository.uploadResults["content://test/page1.jpg"] =
            Result.failure(Exception("PDF conversion failed"))

        val worker = createWorker()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(uploadQueueRepository.recordedCalls.contains("markAsFailed(1, PDF conversion failed)"))
    }

    // ==================== Multiple Uploads Tests ====================

    @Test
    fun `doWork processes multiple uploads successfully`() = runTest {
        uploadQueueRepository.enqueue(createPendingUpload(id = 1, uri = "content://test/doc1.pdf"))
        uploadQueueRepository.enqueue(createPendingUpload(id = 2, uri = "content://test/doc2.pdf"))
        uploadQueueRepository.enqueue(createPendingUpload(id = 3, uri = "content://test/doc3.pdf"))
        documentRepository.defaultUploadResult = Result.success("task-123")

        val worker = createWorker()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, markCalls("markAsCompleted", 1))
        assertEquals(1, markCalls("markAsCompleted", 2))
        assertEquals(1, markCalls("markAsCompleted", 3))
    }

    @Test
    fun `doWork returns success with partial failures`() = runTest {
        uploadQueueRepository.enqueue(createPendingUpload(id = 1, uri = "content://test/doc1.pdf"))
        uploadQueueRepository.enqueue(createPendingUpload(id = 2, uri = "content://test/doc2.pdf"))

        // First upload succeeds, second fails
        documentRepository.uploadResults["content://test/doc1.pdf"] = Result.success("task-1")
        documentRepository.uploadResults["content://test/doc2.pdf"] = Result.failure(Exception("Failed"))

        val worker = createWorker()
        val result = worker.doWork()

        // Partial success still returns success
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, markCalls("markAsCompleted", 1))
        assertTrue(uploadQueueRepository.recordedCalls.contains("markAsFailed(2, Failed)"))
    }

    @Test
    fun `doWork returns success after draining even when all uploads fail`() = runTest {
        uploadQueueRepository.enqueue(createPendingUpload(id = 1, uri = "content://test/doc1.pdf"))
        uploadQueueRepository.enqueue(createPendingUpload(id = 2, uri = "content://test/doc2.pdf"))
        documentRepository.defaultUploadResult = Result.failure(Exception("Network error"))

        val worker = createWorker()
        val result = worker.doWork()

        // Draining completed (both per-item failures persisted via markAsFailed);
        // the work unit succeeds so it can't poison an APPEND-chained follow-up (#128).
        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(uploadQueueRepository.recordedCalls.contains("markAsFailed(1, Network error)"))
        assertTrue(uploadQueueRepository.recordedCalls.contains("markAsFailed(2, Network error)"))
        // No successful upload -> no task refresh needed.
        assertTrue(taskRepository.getTasksCalls.isEmpty())
    }

    // ==================== Retry Behavior Tests ====================

    @Test
    fun `doWork logs max retries warning when retry count exceeded`() = runTest {
        uploadQueueRepository.enqueue(
            createPendingUpload(
                id = 1,
                uri = "content://test/doc1.pdf",
                retryCount = 3 // MAX_RETRIES reached
            )
        )
        documentRepository.defaultUploadResult = Result.failure(Exception("Still failing"))

        val worker = createWorker()
        worker.doWork()

        // Should still mark as failed
        assertTrue(uploadQueueRepository.recordedCalls.contains("markAsFailed(1, Still failing)"))
    }

    // ==================== Safety Limit Tests ====================

    @Test
    fun `doWork breaks loop when same upload returned twice`() = runTest {
        uploadQueueRepository.enqueue(createPendingUpload(id = 1, uri = "content://test/doc1.pdf"))
        // Simulate a buggy queue where completion does not remove the row and the same
        // upload keeps being returned.
        uploadQueueRepository.stickyNextUpload = true
        documentRepository.defaultUploadResult = Result.success("task-123")

        val worker = createWorker()
        val result = worker.doWork()

        // Should process once then break
        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(1, markCalls("markAsCompleted", 1))
    }

    // ==================== Exception Handling Tests ====================

    @Test
    fun `doWork handles unexpected exception during upload`() = runTest {
        uploadQueueRepository.enqueue(createPendingUpload(id = 1, uri = "content://test/doc1.pdf"))
        documentRepository.uploadExceptions["content://test/doc1.pdf"] = RuntimeException("Unexpected crash")

        val worker = createWorker()
        val result = worker.doWork()

        // A genuine bug is recorded per-item (markAsFailed) and the drain completes,
        // so the work unit succeeds (the bug surfaces via Crashlytics, not the Result).
        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(uploadQueueRepository.recordedCalls.contains("markAsFailed(1, Unexpected crash)"))
    }

    @Test
    fun `doWork reports unexpected throwable to Crashlytics as non-fatal`() = runTest {
        uploadQueueRepository.enqueue(createPendingUpload(id = 1, uri = "content://test/doc1.pdf"))
        documentRepository.uploadExceptions["content://test/doc1.pdf"] = RuntimeException("Unexpected crash")

        val worker = createWorker()
        val result = worker.doWork()

        // A genuine bug (thrown, not Result-wrapped) surfaces in Crashlytics as a
        // non-fatal with its full stack trace...
        val recorded = crashlyticsHelper.recordedExceptions.single()
        assertTrue(recorded is RuntimeException && recorded.message == "Unexpected crash")
        // ...while the per-item outcome is still persisted via markAsFailed and the
        // drain itself succeeds (per-item failure does not fail the work unit, #128).
        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(uploadQueueRepository.recordedCalls.contains("markAsFailed(1, Unexpected crash)"))
    }

    @Test
    fun `doWork rethrows CancellationException and resets the in-flight row to PENDING`() = runTest {
        uploadQueueRepository.enqueue(createPendingUpload(id = 1, uri = "content://test/doc1.pdf"))
        documentRepository.uploadExceptions["content://test/doc1.pdf"] = CancellationException("cancelled")

        val worker = createWorker()

        var thrown: Throwable? = null
        try {
            worker.doWork()
        } catch (e: CancellationException) {
            thrown = e
        }

        // Coroutine cancellation means the worker was STOPPED: it must propagate
        // (not be swallowed into a per-item failure) so WorkManager sees the stop.
        assertTrue("doWork must rethrow CancellationException", thrown is CancellationException)
        // The row was moved to UPLOADING before the attempt; on cancellation it must
        // be reset to PENDING so the next run retries it instead of stranding it (#128).
        assertEquals(1, markCalls("resetToPending", 1))
        // A cancelled in-flight upload must NOT be marked permanently failed...
        assertEquals(0, markCalls("markAsFailed", 1))
        // ...and cancellation is not a crash → no Crashlytics non-fatal.
        assertTrue(crashlyticsHelper.recordedExceptions.isEmpty())
    }

    @Test
    fun `doWork completes the row instead of requeuing when cancelled after a successful upload`() = runTest {
        uploadQueueRepository.enqueue(createPendingUpload(id = 1, uri = "content://test/doc1.pdf"))
        // The server ACCEPTS the document...
        documentRepository.defaultUploadResult = Result.success("task-123")
        // ...but the worker is cancelled while finishing the row: the first
        // markAsCompleted is interrupted, the NonCancellable finalization retries it.
        uploadQueueRepository.markAsCompletedFailures.addLast(CancellationException("cancelled during completion"))

        val worker = createWorker()

        var thrown: Throwable? = null
        try {
            worker.doWork()
        } catch (e: CancellationException) {
            thrown = e
        }

        assertTrue("doWork must rethrow CancellationException", thrown is CancellationException)
        // The server already has the document → the row must NOT be reset to PENDING
        // (that would re-upload and create a DUPLICATE); it must be completed instead.
        assertEquals(0, markCalls("resetToPending", 1))
        assertEquals(2, markCalls("markAsCompleted", 1))
    }

    @Test
    fun `doWork completes the row instead of requeuing on a non-cancellation error after a successful upload`() = runTest {
        uploadQueueRepository.enqueue(createPendingUpload(id = 1, uri = "content://test/doc1.pdf"))
        // The server ACCEPTS the document...
        documentRepository.defaultUploadResult = Result.success("task-123")
        // ...but finishing the row throws a regular (non-cancellation) error the first
        // time (e.g. the markAsCompleted delete hiccups). The generic catch must NOT
        // mark a server-accepted upload as failed (that would re-upload → duplicate).
        uploadQueueRepository.markAsCompletedFailures.addLast(RuntimeException("db error during completion"))

        val worker = createWorker()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(0, markCalls("markAsFailed", 1))
        assertEquals(0, markCalls("resetToPending", 1))
        assertEquals(2, markCalls("markAsCompleted", 1))
    }

    @Test
    fun `doWork resets the row when cancelled after claiming it but before uploading`() = runTest {
        uploadQueueRepository.enqueue(createPendingUpload(id = 1, uri = "content://test/doc1.pdf"))

        val worker = createWorker()
        // Cancel during the per-item setForeground() — AFTER markAsUploading has moved
        // the row to UPLOADING but BEFORE the upload starts. The first setForeground
        // (initial progress, before the loop) succeeds; the second (per-item) is
        // cancelled. The claim + foreground setup must sit inside the protected region
        // so this window still resets the row (#128).
        var fgCalls = 0
        coEvery { worker.setForeground(any()) } answers {
            fgCalls++
            if (fgCalls >= 2) throw CancellationException("cancelled during setForeground")
        }

        var thrown: Throwable? = null
        try {
            worker.doWork()
        } catch (e: CancellationException) {
            thrown = e
        }

        assertTrue("doWork must rethrow CancellationException", thrown is CancellationException)
        // The row was claimed (UPLOADING) then cancelled before the upload → it must be
        // reset to PENDING, and the upload must never have been attempted.
        assertEquals(1, markCalls("markAsUploading", 1))
        assertEquals(1, markCalls("resetToPending", 1))
        assertTrue("upload must never be attempted", documentRepository.uploads.isEmpty())
    }

    @Test
    fun `doWork does not report Crashlytics non-fatal for repo Result failure`() = runTest {
        uploadQueueRepository.enqueue(createPendingUpload(id = 1, uri = "content://test/doc1.pdf"))
        documentRepository.defaultUploadResult = Result.failure(Exception("Network error"))

        val worker = createWorker()
        val result = worker.doWork()

        // A typed Result.failure from the repo (.onFailure path) is an EXPECTED
        // failure and must NOT generate Crashlytics non-fatal noise.
        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue(crashlyticsHelper.recordedExceptions.isEmpty())
    }

    // ==================== Progress Callback Tests ====================

    @Test
    fun `doWork invokes progress callback during upload`() = runTest {
        uploadQueueRepository.enqueue(createPendingUpload(id = 1, uri = "content://test/doc1.pdf"))
        // The fake replays these through onProgress before returning the result.
        documentRepository.progressSteps = listOf(0.25f, 0.5f, 0.75f, 1.0f)
        documentRepository.defaultUploadResult = Result.success("task-123")

        val worker = createWorker()
        worker.doWork()

        // The upload ran and every progress step was delivered through the callback.
        assertEquals(1, documentRepository.uploads.size)
        assertEquals(listOf(0.25f, 0.5f, 0.75f, 1.0f), documentRepository.reportedProgress)
    }

    // ==================== Foreground Refresh Throttle Tests ====================

    @Test
    fun `shouldRefreshForeground returns true on first invocation`() {
        val worker = createWorker()
        assertTrue(worker.shouldRefreshForeground(UploadWorker.FOREGROUND_REFRESH_INTERVAL_MS))
    }

    @Test
    fun `shouldRefreshForeground returns false within throttle interval`() {
        val worker = createWorker()
        worker.lastForegroundRefreshMs = 100_000L
        assertFalse(worker.shouldRefreshForeground(105_000L))
    }

    @Test
    fun `shouldRefreshForeground returns true exactly at interval boundary`() {
        val worker = createWorker()
        worker.lastForegroundRefreshMs = 100_000L
        assertTrue(
            worker.shouldRefreshForeground(100_000L + UploadWorker.FOREGROUND_REFRESH_INTERVAL_MS)
        )
    }

    @Test
    fun `shouldRefreshForeground returns true after interval elapsed`() {
        val worker = createWorker()
        worker.lastForegroundRefreshMs = 100_000L
        assertTrue(worker.shouldRefreshForeground(131_000L))
    }

    // ==================== Long Upload Warning Tests ====================

    @Test
    fun `isLongUpload returns false before worker started`() {
        val worker = createWorker()
        assertFalse(worker.isLongUpload(System.currentTimeMillis()))
    }

    @Test
    fun `isLongUpload returns false within 5 minutes of start`() {
        val worker = createWorker()
        worker.workerStartTimeMs = 1_000_000L
        assertFalse(worker.isLongUpload(1_000_000L + 4 * 60 * 1000L))
    }

    @Test
    fun `isLongUpload returns true after 5 minute threshold`() {
        val worker = createWorker()
        worker.workerStartTimeMs = 1_000_000L
        assertTrue(
            worker.isLongUpload(1_000_000L + UploadWorker.LONG_UPLOAD_WARNING_MS + 1L)
        )
    }

    // ==================== Helper Functions ====================

    private fun createPendingUpload(
        id: Long,
        uri: String,
        title: String? = null,
        tagIds: List<Int> = emptyList(),
        documentTypeId: Int? = null,
        correspondentId: Int? = null,
        status: UploadStatus = UploadStatus.PENDING,
        retryCount: Int = 0,
        isMultiPage: Boolean = false,
        additionalUris: List<String> = emptyList()
    ) = PendingUpload(
        id = id,
        uri = uri,
        title = title,
        tagIds = tagIds,
        documentTypeId = documentTypeId,
        correspondentId = correspondentId,
        status = status,
        retryCount = retryCount,
        isMultiPage = isMultiPage,
        additionalUris = additionalUris
    )
}
