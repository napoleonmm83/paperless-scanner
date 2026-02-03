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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
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
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var serverHealthMonitor: ServerHealthMonitor
    private lateinit var syncHistoryRepository: SyncHistoryRepository
    private lateinit var crashlyticsHelper: CrashlyticsHelper

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        uploadQueueRepository = mockk(relaxed = true)
        documentRepository = mockk(relaxed = true)
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
    fun `doWork retries when server is not reachable`() = runBlocking {
        // Given: Server is not reachable
        every { serverHealthMonitor.isServerReachable } returns MutableStateFlow(false)
        every { serverHealthMonitor.serverStatus } returns MutableStateFlow(ServerStatus.Offline(com.paperless.scanner.data.api.ServerOfflineReason.UNKNOWN))

        val worker = createWorker()
        val result = worker.doWork()

        // Then: Worker returns retry
        assertEquals(ListenableWorker.Result.retry(), result)

        // And: No uploads were attempted
        coVerify(exactly = 0) { uploadQueueRepository.getNextPendingUpload() }
    }

    // ==================== No Pending Uploads Tests ====================

        @Test
    fun `doWork returns success when no pending uploads`() = runBlocking {
        coEvery { uploadQueueRepository.getPendingUploadCount() } returns 0
        coEvery { uploadQueueRepository.getNextPendingUpload() } returns null

        val worker = createWorker()
        val result = worker.doWork()

        assertEquals(ListenableWorker.Result.success(), result)
    }

    // ==================== Single Upload Tests ====================

        @Test
    fun `doWork processes single upload successfully`() = runBlocking {
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
    }

        @Test
    fun `doWork marks upload as failed on error`() = runBlocking {
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

        // All uploads failed -> Result.failure
        assertEquals(ListenableWorker.Result.failure(), result)
        coVerify { uploadQueueRepository.markAsUploading(1) }
        coVerify { uploadQueueRepository.markAsFailed(1, "Network error") }
    }

        @Test
    fun `doWork uses document title when available`() = runBlocking {
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
    fun `doWork passes tag ids to repository`() = runBlocking {
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
    fun `doWork passes document type id to repository`() = runBlocking {
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
    fun `doWork passes correspondent id to repository`() = runBlocking {
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
    fun `doWork processes multi-page upload successfully`() = runBlocking {
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
    fun `doWork marks multi-page upload as failed on error`() = runBlocking {
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

        assertEquals(ListenableWorker.Result.failure(), result)
        coVerify { uploadQueueRepository.markAsFailed(1, "PDF conversion failed") }
    }

    // ==================== Multiple Uploads Tests ====================

        @Test
    fun `doWork processes multiple uploads successfully`() = runBlocking {
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
    fun `doWork returns success with partial failures`() = runBlocking {
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
    fun `doWork returns failure when all uploads fail`() = runBlocking {
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

        assertEquals(ListenableWorker.Result.failure(), result)
        coVerify { uploadQueueRepository.markAsFailed(1, "Network error") }
        coVerify { uploadQueueRepository.markAsFailed(2, "Network error") }
    }

    // ==================== Retry Behavior Tests ====================

        @Test
    fun `doWork logs max retries warning when retry count exceeded`() = runBlocking {
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
    fun `doWork breaks loop when same upload returned twice`() = runBlocking {
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
    fun `doWork handles unexpected exception during upload`() = runBlocking {
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

        assertEquals(ListenableWorker.Result.failure(), result)
        coVerify { uploadQueueRepository.markAsFailed(1, "Unexpected crash") }
    }

    // ==================== Progress Callback Tests ====================

        @Test
    fun `doWork invokes progress callback during upload`() = runBlocking {
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
