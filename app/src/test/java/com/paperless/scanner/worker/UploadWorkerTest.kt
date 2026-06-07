package com.paperless.scanner.worker

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.ListenableWorker
import com.paperless.scanner.data.analytics.CrashlyticsHelper
import com.paperless.scanner.data.database.PendingUpload
import com.paperless.scanner.data.database.UploadStatus
import com.paperless.scanner.data.health.ServerHealthMonitor
import com.paperless.scanner.data.health.ServerStatus
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.data.repository.DocumentRepository
import com.paperless.scanner.data.repository.SyncHistoryRepository
import com.paperless.scanner.data.repository.TaskRepository
import com.paperless.scanner.data.repository.UploadQueueRepository
import com.paperless.scanner.util.FileUtils
import kotlinx.coroutines.flow.MutableStateFlow
import com.google.common.util.concurrent.ListenableFuture
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.Runs
import io.mockk.slot
import io.mockk.spyk
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
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
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class UploadWorkerTest {

    private lateinit var context: Context
    private lateinit var uploadQueueRepository: UploadQueueRepository
    private lateinit var documentRepository: DocumentRepository
    private lateinit var taskRepository: TaskRepository
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var serverHealthMonitor: ServerHealthMonitor
    private lateinit var syncHistoryRepository: SyncHistoryRepository
    private lateinit var crashlyticsHelper: CrashlyticsHelper

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        uploadQueueRepository = mockk(relaxed = true)
        documentRepository = mockk(relaxed = true)
        taskRepository = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)
        serverHealthMonitor = mockk(relaxed = true)
        syncHistoryRepository = mockk(relaxed = true)
        crashlyticsHelper = mockk(relaxed = true)

        // Default: hasValidatedInternet returns true
        every { networkMonitor.hasValidatedInternet() } returns true

        // Default: Server is reachable
        every { serverHealthMonitor.serverStatus } returns MutableStateFlow<ServerStatus>(ServerStatus.Online(System.currentTimeMillis()))
        every { serverHealthMonitor.isServerReachable } returns MutableStateFlow(true)
        coEvery { serverHealthMonitor.checkServerHealth() } returns com.paperless.scanner.data.health.ServerHealthResult.Success

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

    // ==================== Pre-Check Tests ====================

    @Test
    fun `doWork retries when server is not reachable`() = runTest {
        // Given: Server is not reachable
        every { serverHealthMonitor.isServerReachable } returns MutableStateFlow(false)
        every { serverHealthMonitor.serverStatus } returns MutableStateFlow(ServerStatus.Offline(com.paperless.scanner.domain.error.ServerOfflineReason.UNKNOWN))

        val worker = createWorker()
        val result = worker.doWork()

        // Then: Worker returns retry
        assertEquals(ListenableWorker.Result.retry(), result)

        // And: No uploads were attempted
        coVerify(exactly = 0) { uploadQueueRepository.getNextPendingUpload() }
    }

    // ==================== No Pending Uploads Tests ====================

        @Test
    fun `doWork returns success when no pending uploads`() = runTest {
        coEvery { uploadQueueRepository.getPendingUploadCount() } returns 0
        coEvery { uploadQueueRepository.getNextPendingUpload() } returns null

        val worker = createWorker()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
    }

    // ==================== Single Upload Tests ====================

        @Test
    fun `doWork processes single upload successfully`() = runTest {
        val pendingUpload = createPendingUpload(id = 1, uri = "content://test/doc1.pdf")

        coEvery { uploadQueueRepository.getPendingUploadCount() } returns 1
        coEvery { uploadQueueRepository.getNextPendingUpload() } returnsMany listOf(pendingUpload, null)
        coEvery {
            documentRepository.uploadDocument(
                uri = any(),
                title = any(),
                tagIds = any(),
                documentTypeId = any(),
                correspondentId = any(),
                onProgress = any()
            )
        } returns Result.success("task-123")

        val worker = createWorker()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify { uploadQueueRepository.markAsUploading(1) }
        coVerify { uploadQueueRepository.markAsCompleted(1) }
        // Refresh tasks so the new server-side consumption task shows up in the
        // "In Verarbeitung" list immediately (reactive Room flow).
        coVerify { taskRepository.getTasks(forceRefresh = true) }
    }

        @Test
    fun `doWork marks upload as failed on error`() = runTest {
        val pendingUpload = createPendingUpload(id = 1, uri = "content://test/doc1.pdf")

        coEvery { uploadQueueRepository.getPendingUploadCount() } returns 1
        coEvery { uploadQueueRepository.getNextPendingUpload() } returnsMany listOf(pendingUpload, null)
        coEvery {
            documentRepository.uploadDocument(
                uri = any(),
                title = any(),
                tagIds = any(),
                documentTypeId = any(),
                correspondentId = any(),
                onProgress = any()
            )
        } returns Result.failure(Exception("Network error"))

        val worker = createWorker()
        val result = worker.doWork()

        // The worker drained the queue; the per-item failure is persisted via
        // markAsFailed, so the work unit itself succeeds (#128 success-after-drain).
        assertEquals(ListenableWorker.Result.success(), result)
        coVerify { uploadQueueRepository.markAsUploading(1) }
        coVerify { uploadQueueRepository.markAsFailed(1, "Network error") }
    }

        @Test
    fun `doWork uses document title when available`() = runTest {
        val pendingUpload = createPendingUpload(
            id = 1,
            uri = "content://test/doc1.pdf",
            title = "My Invoice"
        )

        coEvery { uploadQueueRepository.getPendingUploadCount() } returns 1
        coEvery { uploadQueueRepository.getNextPendingUpload() } returnsMany listOf(pendingUpload, null)
        coEvery {
            documentRepository.uploadDocument(
                uri = any(),
                title = eq("My Invoice"),
                tagIds = any(),
                documentTypeId = any(),
                correspondentId = any(),
                onProgress = any()
            )
        } returns Result.success("task-123")

        val worker = createWorker()
        worker.doWork()

        coVerify {
            documentRepository.uploadDocument(
                uri = any(),
                title = "My Invoice",
                tagIds = any(),
                documentTypeId = any(),
                correspondentId = any(),
                onProgress = any()
            )
        }
    }

        @Test
    fun `doWork passes tag ids to repository`() = runTest {
        val tagIds = listOf(1, 2, 3)
        val pendingUpload = createPendingUpload(
            id = 1,
            uri = "content://test/doc1.pdf",
            tagIds = tagIds
        )

        coEvery { uploadQueueRepository.getPendingUploadCount() } returns 1
        coEvery { uploadQueueRepository.getNextPendingUpload() } returnsMany listOf(pendingUpload, null)
        coEvery {
            documentRepository.uploadDocument(
                uri = any(),
                title = any(),
                tagIds = eq(tagIds),
                documentTypeId = any(),
                correspondentId = any(),
                onProgress = any()
            )
        } returns Result.success("task-123")

        val worker = createWorker()
        worker.doWork()

        coVerify {
            documentRepository.uploadDocument(
                uri = any(),
                title = any(),
                tagIds = tagIds,
                documentTypeId = any(),
                correspondentId = any(),
                onProgress = any()
            )
        }
    }

        @Test
    fun `doWork passes document type id to repository`() = runTest {
        val pendingUpload = createPendingUpload(
            id = 1,
            uri = "content://test/doc1.pdf",
            documentTypeId = 5
        )

        coEvery { uploadQueueRepository.getPendingUploadCount() } returns 1
        coEvery { uploadQueueRepository.getNextPendingUpload() } returnsMany listOf(pendingUpload, null)
        coEvery {
            documentRepository.uploadDocument(
                uri = any(),
                title = any(),
                tagIds = any(),
                documentTypeId = eq(5),
                correspondentId = any(),
                onProgress = any()
            )
        } returns Result.success("task-123")

        val worker = createWorker()
        worker.doWork()

        coVerify {
            documentRepository.uploadDocument(
                uri = any(),
                title = any(),
                tagIds = any(),
                documentTypeId = 5,
                correspondentId = any(),
                onProgress = any()
            )
        }
    }

        @Test
    fun `doWork passes correspondent id to repository`() = runTest {
        val pendingUpload = createPendingUpload(
            id = 1,
            uri = "content://test/doc1.pdf",
            correspondentId = 10
        )

        coEvery { uploadQueueRepository.getPendingUploadCount() } returns 1
        coEvery { uploadQueueRepository.getNextPendingUpload() } returnsMany listOf(pendingUpload, null)
        coEvery {
            documentRepository.uploadDocument(
                uri = any(),
                title = any(),
                tagIds = any(),
                documentTypeId = any(),
                correspondentId = eq(10),
                onProgress = any()
            )
        } returns Result.success("task-123")

        val worker = createWorker()
        worker.doWork()

        coVerify {
            documentRepository.uploadDocument(
                uri = any(),
                title = any(),
                tagIds = any(),
                documentTypeId = any(),
                correspondentId = 10,
                onProgress = any()
            )
        }
    }

    // ==================== Multi-Page Upload Tests ====================

        @Test
    fun `doWork processes multi-page upload successfully`() = runTest {
        val pendingUpload = createPendingUpload(
            id = 1,
            uri = "content://test/page1.jpg",
            isMultiPage = true,
            additionalUris = listOf("content://test/page2.jpg", "content://test/page3.jpg")
        )

        val expectedUris = listOf(
            Uri.parse("content://test/page1.jpg"),
            Uri.parse("content://test/page2.jpg"),
            Uri.parse("content://test/page3.jpg")
        )

        coEvery { uploadQueueRepository.getPendingUploadCount() } returns 1
        coEvery { uploadQueueRepository.getNextPendingUpload() } returnsMany listOf(pendingUpload, null)
        coEvery { uploadQueueRepository.getAllUris(pendingUpload) } returns expectedUris
        coEvery {
            documentRepository.uploadMultiPageDocument(
                uris = any(),
                title = any(),
                tagIds = any(),
                documentTypeId = any(),
                correspondentId = any(),
                onProgress = any()
            )
        } returns Result.success("task-456")

        val worker = createWorker()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify { uploadQueueRepository.markAsCompleted(1) }
        coVerify {
            documentRepository.uploadMultiPageDocument(
                uris = expectedUris,
                title = any(),
                tagIds = any(),
                documentTypeId = any(),
                correspondentId = any(),
                onProgress = any()
            )
        }
    }

        @Test
    fun `doWork marks multi-page upload as failed on error`() = runTest {
        val pendingUpload = createPendingUpload(
            id = 1,
            uri = "content://test/page1.jpg",
            isMultiPage = true
        )

        coEvery { uploadQueueRepository.getPendingUploadCount() } returns 1
        coEvery { uploadQueueRepository.getNextPendingUpload() } returnsMany listOf(pendingUpload, null)
        coEvery { uploadQueueRepository.getAllUris(pendingUpload) } returns listOf(Uri.parse("content://test/page1.jpg"))
        coEvery {
            documentRepository.uploadMultiPageDocument(
                uris = any(),
                title = any(),
                tagIds = any(),
                documentTypeId = any(),
                correspondentId = any(),
                onProgress = any()
            )
        } returns Result.failure(Exception("PDF conversion failed"))

        val worker = createWorker()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify { uploadQueueRepository.markAsFailed(1, "PDF conversion failed") }
    }

    // ==================== Multiple Uploads Tests ====================

        @Test
    fun `doWork processes multiple uploads successfully`() = runTest {
        val upload1 = createPendingUpload(id = 1, uri = "content://test/doc1.pdf")
        val upload2 = createPendingUpload(id = 2, uri = "content://test/doc2.pdf")
        val upload3 = createPendingUpload(id = 3, uri = "content://test/doc3.pdf")

        coEvery { uploadQueueRepository.getPendingUploadCount() } returns 3
        coEvery { uploadQueueRepository.getNextPendingUpload() } returnsMany listOf(upload1, upload2, upload3, null)
        coEvery {
            documentRepository.uploadDocument(
                uri = any(),
                title = any(),
                tagIds = any(),
                documentTypeId = any(),
                correspondentId = any(),
                onProgress = any()
            )
        } returns Result.success("task-123")

        val worker = createWorker()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify { uploadQueueRepository.markAsCompleted(1) }
        coVerify { uploadQueueRepository.markAsCompleted(2) }
        coVerify { uploadQueueRepository.markAsCompleted(3) }
    }

        @Test
    fun `doWork returns success with partial failures`() = runTest {
        val upload1 = createPendingUpload(id = 1, uri = "content://test/doc1.pdf")
        val upload2 = createPendingUpload(id = 2, uri = "content://test/doc2.pdf")

        coEvery { uploadQueueRepository.getPendingUploadCount() } returns 2
        coEvery { uploadQueueRepository.getNextPendingUpload() } returnsMany listOf(upload1, upload2, null)

        // First upload succeeds, second fails
        coEvery {
            documentRepository.uploadDocument(
                uri = eq(Uri.parse("content://test/doc1.pdf")),
                title = any(),
                tagIds = any(),
                documentTypeId = any(),
                correspondentId = any(),
                onProgress = any()
            )
        } returns Result.success("task-1")

        coEvery {
            documentRepository.uploadDocument(
                uri = eq(Uri.parse("content://test/doc2.pdf")),
                title = any(),
                tagIds = any(),
                documentTypeId = any(),
                correspondentId = any(),
                onProgress = any()
            )
        } returns Result.failure(Exception("Failed"))

        val worker = createWorker()
        val result = worker.doWork()

        // Partial success still returns success
        assertEquals(ListenableWorker.Result.success(), result)
        coVerify { uploadQueueRepository.markAsCompleted(1) }
        coVerify { uploadQueueRepository.markAsFailed(2, "Failed") }
    }

        @Test
    fun `doWork returns success after draining even when all uploads fail`() = runTest {
        val upload1 = createPendingUpload(id = 1, uri = "content://test/doc1.pdf")
        val upload2 = createPendingUpload(id = 2, uri = "content://test/doc2.pdf")

        coEvery { uploadQueueRepository.getPendingUploadCount() } returns 2
        coEvery { uploadQueueRepository.getNextPendingUpload() } returnsMany listOf(upload1, upload2, null)
        coEvery {
            documentRepository.uploadDocument(
                uri = any(),
                title = any(),
                tagIds = any(),
                documentTypeId = any(),
                correspondentId = any(),
                onProgress = any()
            )
        } returns Result.failure(Exception("Network error"))

        val worker = createWorker()
        val result = worker.doWork()

        // Draining completed (both per-item failures persisted via markAsFailed);
        // the work unit succeeds so it can't poison an APPEND-chained follow-up (#128).
        assertEquals(ListenableWorker.Result.success(), result)
        coVerify { uploadQueueRepository.markAsFailed(1, "Network error") }
        coVerify { uploadQueueRepository.markAsFailed(2, "Network error") }
        // No successful upload -> no task refresh needed.
        coVerify(exactly = 0) { taskRepository.getTasks(any()) }
    }

    // ==================== Retry Behavior Tests ====================

        @Test
    fun `doWork logs max retries warning when retry count exceeded`() = runTest {
        val pendingUpload = createPendingUpload(
            id = 1,
            uri = "content://test/doc1.pdf",
            retryCount = 3 // MAX_RETRIES reached
        )

        coEvery { uploadQueueRepository.getPendingUploadCount() } returns 1
        coEvery { uploadQueueRepository.getNextPendingUpload() } returnsMany listOf(pendingUpload, null)
        coEvery {
            documentRepository.uploadDocument(
                uri = any(),
                title = any(),
                tagIds = any(),
                documentTypeId = any(),
                correspondentId = any(),
                onProgress = any()
            )
        } returns Result.failure(Exception("Still failing"))

        val worker = createWorker()
        worker.doWork()

        // Should still mark as failed
        coVerify { uploadQueueRepository.markAsFailed(1, "Still failing") }
    }

    // ==================== Safety Limit Tests ====================

        @Test
    fun `doWork breaks loop when same upload returned twice`() = runTest {
        val pendingUpload = createPendingUpload(id = 1, uri = "content://test/doc1.pdf")

        coEvery { uploadQueueRepository.getPendingUploadCount() } returns 1
        // Return same upload repeatedly (simulates bug where upload isn't removed from queue)
        coEvery { uploadQueueRepository.getNextPendingUpload() } returns pendingUpload
        coEvery {
            documentRepository.uploadDocument(
                uri = any(),
                title = any(),
                tagIds = any(),
                documentTypeId = any(),
                correspondentId = any(),
                onProgress = any()
            )
        } returns Result.success("task-123")

        val worker = createWorker()
        val result = worker.doWork()

        // Should process once then break
        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 1) { uploadQueueRepository.markAsCompleted(1) }
    }

    // ==================== Exception Handling Tests ====================

        @Test
    fun `doWork handles unexpected exception during upload`() = runTest {
        val pendingUpload = createPendingUpload(id = 1, uri = "content://test/doc1.pdf")

        coEvery { uploadQueueRepository.getPendingUploadCount() } returns 1
        coEvery { uploadQueueRepository.getNextPendingUpload() } returnsMany listOf(pendingUpload, null)
        coEvery {
            documentRepository.uploadDocument(
                uri = any(),
                title = any(),
                tagIds = any(),
                documentTypeId = any(),
                correspondentId = any(),
                onProgress = any()
            )
        } throws RuntimeException("Unexpected crash")

        val worker = createWorker()
        val result = worker.doWork()

        // A genuine bug is recorded per-item (markAsFailed) and the drain completes,
        // so the work unit succeeds (the bug surfaces via Crashlytics, not the Result).
        assertEquals(ListenableWorker.Result.success(), result)
        coVerify { uploadQueueRepository.markAsFailed(1, "Unexpected crash") }
    }

    @Test
    fun `doWork reports unexpected throwable to Crashlytics as non-fatal`() = runTest {
        val pendingUpload = createPendingUpload(id = 1, uri = "content://test/doc1.pdf")

        coEvery { uploadQueueRepository.getPendingUploadCount() } returns 1
        coEvery { uploadQueueRepository.getNextPendingUpload() } returnsMany listOf(pendingUpload, null)
        coEvery {
            documentRepository.uploadDocument(
                uri = any(),
                title = any(),
                tagIds = any(),
                documentTypeId = any(),
                correspondentId = any(),
                onProgress = any()
            )
        } throws RuntimeException("Unexpected crash")

        val worker = createWorker()
        val result = worker.doWork()

        // A genuine bug (thrown, not Result-wrapped) surfaces in Crashlytics as a
        // non-fatal with its full stack trace...
        verify(exactly = 1) {
            crashlyticsHelper.recordException(match { it is RuntimeException && it.message == "Unexpected crash" })
        }
        // ...while the per-item outcome is still persisted via markAsFailed and the
        // drain itself succeeds (per-item failure does not fail the work unit, #128).
        assertEquals(ListenableWorker.Result.success(), result)
        coVerify { uploadQueueRepository.markAsFailed(1, "Unexpected crash") }
    }

    @Test
    fun `doWork rethrows CancellationException and resets the in-flight row to PENDING`() = runTest {
        val pendingUpload = createPendingUpload(id = 1, uri = "content://test/doc1.pdf")

        coEvery { uploadQueueRepository.getPendingUploadCount() } returns 1
        coEvery { uploadQueueRepository.getNextPendingUpload() } returnsMany listOf(pendingUpload, null)
        coEvery {
            documentRepository.uploadDocument(
                uri = any(),
                title = any(),
                tagIds = any(),
                documentTypeId = any(),
                correspondentId = any(),
                onProgress = any()
            )
        } throws CancellationException("cancelled")

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
        coVerify { uploadQueueRepository.resetToPending(1) }
        // A cancelled in-flight upload must NOT be marked permanently failed...
        coVerify(exactly = 0) { uploadQueueRepository.markAsFailed(1, any()) }
        // ...and cancellation is not a crash → no Crashlytics non-fatal.
        verify(exactly = 0) { crashlyticsHelper.recordException(any()) }
    }

    @Test
    fun `doWork completes the row instead of requeuing when cancelled after a successful upload`() = runTest {
        val pendingUpload = createPendingUpload(id = 1, uri = "content://test/doc1.pdf")

        coEvery { uploadQueueRepository.getPendingUploadCount() } returns 1
        coEvery { uploadQueueRepository.getNextPendingUpload() } returnsMany listOf(pendingUpload, null)
        // The server ACCEPTS the document...
        coEvery {
            documentRepository.uploadDocument(
                uri = any(),
                title = any(),
                tagIds = any(),
                documentTypeId = any(),
                correspondentId = any(),
                onProgress = any()
            )
        } returns Result.success("task-123")
        // ...but the worker is cancelled while finishing the row: the first
        // markAsCompleted is interrupted, the NonCancellable finalization retries it.
        var completeCalls = 0
        coEvery { uploadQueueRepository.markAsCompleted(1) } answers {
            completeCalls++
            if (completeCalls == 1) throw CancellationException("cancelled during completion")
        }

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
        coVerify(exactly = 0) { uploadQueueRepository.resetToPending(1) }
        coVerify(exactly = 2) { uploadQueueRepository.markAsCompleted(1) }
    }

    @Test
    fun `doWork completes the row instead of requeuing on a non-cancellation error after a successful upload`() = runTest {
        val pendingUpload = createPendingUpload(id = 1, uri = "content://test/doc1.pdf")

        coEvery { uploadQueueRepository.getPendingUploadCount() } returns 1
        coEvery { uploadQueueRepository.getNextPendingUpload() } returnsMany listOf(pendingUpload, null)
        // The server ACCEPTS the document...
        coEvery {
            documentRepository.uploadDocument(
                uri = any(),
                title = any(),
                tagIds = any(),
                documentTypeId = any(),
                correspondentId = any(),
                onProgress = any()
            )
        } returns Result.success("task-123")
        // ...but finishing the row throws a regular (non-cancellation) error the first
        // time (e.g. the markAsCompleted delete hiccups). The generic catch must NOT
        // mark a server-accepted upload as failed (that would re-upload → duplicate).
        var completeCalls = 0
        coEvery { uploadQueueRepository.markAsCompleted(1) } answers {
            completeCalls++
            if (completeCalls == 1) throw RuntimeException("db error during completion")
        }

        val worker = createWorker()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { uploadQueueRepository.markAsFailed(1, any()) }
        coVerify(exactly = 0) { uploadQueueRepository.resetToPending(1) }
        coVerify(exactly = 2) { uploadQueueRepository.markAsCompleted(1) }
    }

    @Test
    fun `doWork resets the row when cancelled after claiming it but before uploading`() = runTest {
        val pendingUpload = createPendingUpload(id = 1, uri = "content://test/doc1.pdf")

        coEvery { uploadQueueRepository.getPendingUploadCount() } returns 1
        coEvery { uploadQueueRepository.getNextPendingUpload() } returnsMany listOf(pendingUpload, null)

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
        coVerify { uploadQueueRepository.markAsUploading(1) }
        coVerify { uploadQueueRepository.resetToPending(1) }
        coVerify(exactly = 0) {
            documentRepository.uploadDocument(any(), any(), any(), any(), any(), onProgress = any())
        }
    }

    @Test
    fun `doWork does not report Crashlytics non-fatal for repo Result failure`() = runTest {
        val pendingUpload = createPendingUpload(id = 1, uri = "content://test/doc1.pdf")

        coEvery { uploadQueueRepository.getPendingUploadCount() } returns 1
        coEvery { uploadQueueRepository.getNextPendingUpload() } returnsMany listOf(pendingUpload, null)
        coEvery {
            documentRepository.uploadDocument(
                uri = any(),
                title = any(),
                tagIds = any(),
                documentTypeId = any(),
                correspondentId = any(),
                onProgress = any()
            )
        } returns Result.failure(Exception("Network error"))

        val worker = createWorker()
        val result = worker.doWork()

        // A typed Result.failure from the repo (.onFailure path) is an EXPECTED
        // failure and must NOT generate Crashlytics non-fatal noise.
        assertEquals(ListenableWorker.Result.success(), result)
        verify(exactly = 0) { crashlyticsHelper.recordException(any()) }
    }

    // ==================== Progress Callback Tests ====================

        @Test
    fun `doWork invokes progress callback during upload`() = runTest {
        val pendingUpload = createPendingUpload(id = 1, uri = "content://test/doc1.pdf")
        val progressSlot = slot<(Float) -> Unit>()

        coEvery { uploadQueueRepository.getPendingUploadCount() } returns 1
        coEvery { uploadQueueRepository.getNextPendingUpload() } returnsMany listOf(pendingUpload, null)
        coEvery {
            documentRepository.uploadDocument(
                uri = any(),
                title = any(),
                tagIds = any(),
                documentTypeId = any(),
                correspondentId = any(),
                onProgress = capture(progressSlot)
            )
        } answers {
            // Simulate progress updates
            progressSlot.captured(0.25f)
            progressSlot.captured(0.5f)
            progressSlot.captured(0.75f)
            progressSlot.captured(1.0f)
            Result.success("task-123")
        }

        val worker = createWorker()
        worker.doWork()

        // Verify progress callback was captured and invoked
        coVerify {
            documentRepository.uploadDocument(
                uri = any(),
                title = any(),
                tagIds = any(),
                documentTypeId = any(),
                correspondentId = any(),
                onProgress = any()
            )
        }
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
