package com.paperless.scanner.data.repository

import android.content.Context
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.api.models.AcknowledgeTasksRequest
import com.paperless.scanner.data.api.models.Document as ApiDocument
import com.paperless.scanner.data.api.models.DocumentsResponse as DtoDocumentsResponse
import com.paperless.scanner.data.api.models.TrashBulkActionRequest
import com.paperless.scanner.data.database.dao.CachedDocumentDao
import com.paperless.scanner.data.database.dao.CachedTaskDao
import com.paperless.scanner.data.database.entities.CachedDocument
import com.paperless.scanner.data.database.entities.CachedTask
import com.paperless.scanner.data.network.NetworkMonitor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
class TrashRepositoryTest {

    private lateinit var context: Context
    private lateinit var api: PaperlessApi
    private lateinit var cachedDocumentDao: CachedDocumentDao
    private lateinit var cachedTaskDao: CachedTaskDao
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var sync: DocumentSyncRepository
    private lateinit var repo: TrashRepository

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        api = mockk(relaxed = true)
        cachedDocumentDao = mockk(relaxed = true)
        cachedTaskDao = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)
        sync = mockk(relaxed = true)

        every { context.getString(any()) } returns "offline"

        // Default: executeOrQueue runs the online lambda (online happy path).
        coEvery { sync.executeOrQueue<Unit>(any(), any()) } coAnswers {
            try {
                Result.success(firstArg<suspend () -> Unit>().invoke())
            } catch (e: retrofit2.HttpException) {
                Result.failure(PaperlessException.fromHttpCode(e.code(), e.message()))
            } catch (e: Exception) {
                Result.failure(PaperlessException.from(e))
            }
        }

        repo = TrashRepository(
            context = context,
            api = api,
            cachedDocumentDao = cachedDocumentDao,
            cachedTaskDao = cachedTaskDao,
            networkMonitor = networkMonitor,
            sync = sync,
        )
    }

    // -------- helpers --------

    private fun apiDoc(
        id: Int = 1,
        title: String = "Doc $id",
        modified: String = "2026-05-06T00:00:00Z",
    ): ApiDocument = ApiDocument(
        id = id,
        title = title,
        content = null,
        created = "2026-05-06T00:00:00Z",
        modified = modified,
        added = "2026-05-06T00:00:00Z",
        correspondentId = null,
        documentTypeId = null,
        tags = emptyList(),
        archiveSerialNumber = null,
        originalFileName = null,
        notes = emptyList(),
        owner = null,
        permissions = null,
        userCanChange = true,
        ocrConfidence = null,
    )

    private fun cachedDoc(
        id: Int = 1,
        title: String = "Doc $id",
        isDeleted: Boolean = true,
        deletedAt: Long? = 1_700_000_000_000L,
    ): CachedDocument = CachedDocument(
        id = id,
        title = title,
        content = null,
        created = "2026-05-06T00:00:00Z",
        modified = "2026-05-06T00:00:00Z",
        added = "2026-05-06T00:00:00Z",
        archiveSerialNumber = null,
        originalFileName = null,
        correspondent = null,
        documentType = null,
        storagePath = null,
        tags = "[]",
        customFields = null,
        isCached = true,
        lastSyncedAt = 0L,
        isDeleted = isDeleted,
        deletedAt = deletedAt,
    )

    private fun cachedTask(
        id: Int,
        relatedDocument: String,
        acknowledged: Boolean = false,
    ): CachedTask = CachedTask(
        id = id,
        taskId = "task-uuid-$id",
        taskFileName = null,
        dateCreated = "2026-05-06T00:00:00Z",
        dateDone = null,
        type = "consume",
        status = "SUCCESS",
        result = null,
        acknowledged = acknowledged,
        relatedDocument = relatedDocument,
    )

    private fun successUnit(): Response<Unit> = Response.success(Unit)
    private fun errorUnit(code: Int = 500): Response<Unit> =
        Response.error(code, "{}".toResponseBody("application/json".toMediaTypeOrNull()))

    // -------- tests --------

    @Test
    fun `observeTrashedDocuments delegates to dao and maps to TrashedDocument`() = runTest {
        val docs = listOf(
            cachedDoc(id = 1, deletedAt = 1_700_000_000_000L),
            cachedDoc(id = 2, deletedAt = 1_700_000_001_000L),
        )
        every { cachedDocumentDao.observeDeletedDocuments() } returns flowOf(docs)

        val result = repo.observeTrashedDocuments().first()

        assertEquals(2, result.size)
        assertEquals(1, result[0].id)
        assertEquals(2, result[1].id)
        assertEquals(1_700_000_000_000L, result[0].deletedAt)
        assertEquals(1_700_000_001_000L, result[1].deletedAt)
    }

    @Test
    fun `observeTrashedDocumentsCount delegates to dao observeDeletedCount`() = runTest {
        every { cachedDocumentDao.observeDeletedCount() } returns flowOf(7)

        val result = repo.observeTrashedDocumentsCount().first()

        assertEquals(7, result)
    }

    @Test
    fun `observeOldestDeletedTimestamp delegates to dao`() = runTest {
        every { cachedDocumentDao.getOldestDeletedTimestamp() } returns flowOf(1_700_000_000_000L)

        val result = repo.observeOldestDeletedTimestamp().first()

        assertEquals(1_700_000_000_000L, result)
    }

    @Test
    fun `getTrashDocuments online inserts cache and returns response`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        coEvery { api.getTrash(page = 1, pageSize = 25) } returns DtoDocumentsResponse(
            count = 2,
            next = null,
            previous = null,
            results = listOf(apiDoc(id = 1), apiDoc(id = 2)),
        )

        val result = repo.getTrashDocuments(page = 1, pageSize = 25)

        assertTrue(result.isSuccess)
        val resp = result.getOrNull()!!
        assertEquals(2, resp.count)
        coVerify { cachedDocumentDao.insertAll(any()) }
    }

    @Test
    fun `getTrashDocuments offline returns NetworkError`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns false

        val result = repo.getTrashDocuments()

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(
            "expected NetworkError, got $ex",
            ex is PaperlessException.NetworkError
        )
    }

    @Test
    fun `deleteDocument online happy path soft-deletes locally then API succeeds`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        coEvery { cachedTaskDao.getAllTasks() } returns emptyList()
        coEvery { api.deleteDocument(1) } returns successUnit()

        val result = repo.deleteDocument(1)

        assertTrue(result.isSuccess)
        // Optimistic UI invariant: softDelete + ack MUST happen BEFORE the API call
        // so the Gmail-style swipe animation completes immediately.
        coVerifyOrder {
            cachedDocumentDao.softDelete(eq(1), any())
            cachedTaskDao.acknowledgeTasksForDocument("1")
            api.deleteDocument(1)
        }
    }

    @Test
    fun `deleteDocument online API failure rolls back optimistic delete`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        coEvery { cachedTaskDao.getAllTasks() } returns emptyList()
        coEvery { api.deleteDocument(1) } returns errorUnit(500)

        val result = repo.deleteDocument(1)

        assertTrue(result.isFailure)
        coVerify { cachedDocumentDao.softDelete(eq(1), any()) }
        coVerify { cachedDocumentDao.restoreDocument(1) }
    }

    @Test
    fun `deleteDocument online cascade-acknowledges tasks for document`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        coEvery { cachedTaskDao.getAllTasks() } returns listOf(
            cachedTask(id = 10, relatedDocument = "1", acknowledged = false),
            cachedTask(id = 11, relatedDocument = "1", acknowledged = false),
            cachedTask(id = 12, relatedDocument = "2", acknowledged = false),    // different doc
            cachedTask(id = 13, relatedDocument = "1", acknowledged = true),     // already acked
        )
        coEvery { api.deleteDocument(1) } returns successUnit()
        val ackSlot = slot<AcknowledgeTasksRequest>()
        coEvery { api.acknowledgeTasks(capture(ackSlot)) } returns successUnit()

        val result = repo.deleteDocument(1)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { api.acknowledgeTasks(any()) }
        // Only unacknowledged tasks for doc=1 should be in payload
        assertEquals(listOf(10, 11), ackSlot.captured.tasks)
        coVerify { cachedTaskDao.acknowledgeTasksForDocument("1") }
    }

    @Test
    fun `deleteDocument offline queues delete via sync and softDeletes`() = runTest {
        // Switch executeOrQueue stub to run the OFFLINE lambda
        coEvery { sync.executeOrQueue<Unit>(any(), any()) } coAnswers {
            try {
                Result.success(secondArg<suspend () -> Unit>().invoke())
            } catch (e: Exception) {
                Result.failure(PaperlessException.from(e))
            }
        }

        val result = repo.deleteDocument(7)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { sync.queueDocumentDelete(7) }
        // Offline asymmetric ordering: ack BEFORE softDelete so reactivity works
        // (per the original "CRITICAL: Must happen BEFORE soft delete" comment).
        coVerifyOrder {
            sync.queueDocumentDelete(7)
            cachedTaskDao.acknowledgeTasksForDocument("7")
            cachedDocumentDao.softDelete(eq(7), any())
        }
    }

    @Test
    fun `restoreDocument single delegates to bulk with one-element list`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        val requestSlot = slot<TrashBulkActionRequest>()
        coEvery { api.trashBulkAction(capture(requestSlot)) } returns successUnit()

        val result = repo.restoreDocument(42)

        assertTrue(result.isSuccess)
        assertEquals(listOf(42), requestSlot.captured.documents)
        assertEquals("restore", requestSlot.captured.action)
        coVerify { cachedDocumentDao.restoreDocuments(listOf(42)) }
    }

    @Test
    fun `restoreDocuments online happy path`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        val requestSlot = slot<TrashBulkActionRequest>()
        coEvery { api.trashBulkAction(capture(requestSlot)) } returns successUnit()

        val result = repo.restoreDocuments(listOf(1, 2, 3))

        assertTrue(result.isSuccess)
        assertEquals(listOf(1, 2, 3), requestSlot.captured.documents)
        assertEquals("restore", requestSlot.captured.action)
        coVerify { cachedDocumentDao.restoreDocuments(listOf(1, 2, 3)) }
    }

    @Test
    fun `restoreDocuments offline queues restore via sync`() = runTest {
        // Switch executeOrQueue stub to run the OFFLINE lambda
        coEvery { sync.executeOrQueue<Unit>(any(), any()) } coAnswers {
            try {
                Result.success(secondArg<suspend () -> Unit>().invoke())
            } catch (e: Exception) {
                Result.failure(PaperlessException.from(e))
            }
        }

        val result = repo.restoreDocuments(listOf(1, 2, 3))

        assertTrue(result.isSuccess)
        coVerify { sync.queueTrashAction(listOf(1, 2, 3), DocumentSyncRepository.TrashAction.RESTORE) }
        coVerify { cachedDocumentDao.restoreDocuments(listOf(1, 2, 3)) }
    }

    @Test
    fun `permanentlyDeleteDocument single delegates to bulk`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        val requestSlot = slot<TrashBulkActionRequest>()
        coEvery { api.trashBulkAction(capture(requestSlot)) } returns successUnit()

        val result = repo.permanentlyDeleteDocument(99)

        assertTrue(result.isSuccess)
        assertEquals(listOf(99), requestSlot.captured.documents)
        assertEquals("empty", requestSlot.captured.action)
        coVerify { cachedDocumentDao.deleteByIds(listOf(99)) }
    }

    @Test
    fun `permanentlyDeleteDocuments online + offline branches`() = runTest {
        // Online branch (default sync stub runs online lambda)
        val requestSlot = slot<TrashBulkActionRequest>()
        coEvery { api.trashBulkAction(capture(requestSlot)) } returns successUnit()

        val onlineResult = repo.permanentlyDeleteDocuments(listOf(5, 6))

        assertTrue(onlineResult.isSuccess)
        assertEquals("empty", requestSlot.captured.action)
        assertEquals(listOf(5, 6), requestSlot.captured.documents)
        coVerify { cachedDocumentDao.deleteByIds(listOf(5, 6)) }

        // Offline branch — switch sync stub to run the OFFLINE lambda
        coEvery { sync.executeOrQueue<Unit>(any(), any()) } coAnswers {
            try {
                Result.success(secondArg<suspend () -> Unit>().invoke())
            } catch (e: Exception) {
                Result.failure(PaperlessException.from(e))
            }
        }

        val offlineResult = repo.permanentlyDeleteDocuments(listOf(7, 8))

        assertTrue(offlineResult.isSuccess)
        coVerify { sync.queueTrashAction(listOf(7, 8), DocumentSyncRepository.TrashAction.PERMANENT_DELETE) }
        coVerify { cachedDocumentDao.deleteByIds(listOf(7, 8)) }
    }

    @Test
    fun `cleanupOrphanedTrashDocs removes locally-deleted-but-not-on-server`() = runTest {
        coEvery { cachedDocumentDao.getDeletedIds() } returns listOf(1, 2, 3, 4)

        repo.cleanupOrphanedTrashDocs(setOf(1, 3))

        // 2 and 4 are local-only orphans
        coVerify {
            cachedDocumentDao.deleteByIds(match { ids ->
                ids.toSet() == setOf(2, 4)
            })
        }
    }
}
