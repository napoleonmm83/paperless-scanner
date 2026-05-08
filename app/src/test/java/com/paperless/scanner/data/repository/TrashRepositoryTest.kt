package com.paperless.scanner.data.repository

import android.content.Context
import androidx.test.filters.LargeTest
import app.cash.turbine.test
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
import com.paperless.scanner.testing.BaseRoomRepositoryTest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response

/**
 * Repository tests for [TrashRepository].
 *
 * Uses a real in-memory Room database (see [BaseRoomRepositoryTest]) wrapped
 * in `spyk` so the original `coVerifyOrder` UX-invariant assertions
 * ("softDelete + ack BEFORE the API call" — Gmail-style swipe contract) keep
 * working while the DAO calls actually execute against the real schema.
 *
 * Mocked: `PaperlessApi`, `NetworkMonitor`, `Context`, `DocumentSyncRepository`.
 * Real (via spyk): `CachedDocumentDao`, `CachedTaskDao`.
 */
@LargeTest
class TrashRepositoryTest : BaseRoomRepositoryTest() {

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
        networkMonitor = mockk(relaxed = true)
        sync = mockk(relaxed = true)
        // Spyk wraps the real Room DAOs: methods execute against the real schema
        // but calls are still recorded so coVerify(Order) keeps working.
        cachedDocumentDao = spyk(database.cachedDocumentDao())
        cachedTaskDao = spyk(database.cachedTaskDao())

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
    fun `observeTrashedDocuments reacts to deleted-doc inserts and maps to TrashedDocument`() =
        runTest {
            repo.observeTrashedDocuments().test {
                assertEquals(emptyList<com.paperless.scanner.domain.model.TrashedDocument>(), awaitItem())

                cachedDocumentDao.insertAll(
                    listOf(
                        cachedDoc(id = 1, deletedAt = 1_700_000_000_000L),
                        cachedDoc(id = 2, deletedAt = 1_700_000_001_000L),
                    )
                )

                val emitted = awaitItem()
                assertEquals(2, emitted.size)
                assertEquals(setOf(1, 2), emitted.map { it.id }.toSet())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `observeTrashedDocumentsCount reacts to inserts of deleted docs`() = runTest {
        repo.observeTrashedDocumentsCount().test {
            assertEquals(0, awaitItem())

            cachedDocumentDao.insertAll((1..7).map { cachedDoc(id = it) })

            assertEquals(7, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeOldestDeletedTimestamp reacts to inserts and emits the minimum deletedAt`() =
        runTest {
            repo.observeOldestDeletedTimestamp().test {
                assertEquals(null, awaitItem()) // empty cache → no minimum

                cachedDocumentDao.insertAll(
                    listOf(
                        cachedDoc(id = 1, deletedAt = 1_700_000_005_000L),
                        cachedDoc(id = 2, deletedAt = 1_700_000_000_000L),
                        cachedDoc(id = 3, deletedAt = 1_700_000_010_000L),
                    )
                )

                assertEquals(1_700_000_000_000L, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
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
        assertEquals(2, result.getOrNull()!!.count)
        // Real DB verification: rows persisted as deleted.
        val ids = cachedDocumentDao.getDeletedIds().toSet()
        assertEquals(setOf(1, 2), ids)
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
        // Seed the doc as live so softDelete actually does work.
        cachedDocumentDao.insert(cachedDoc(id = 1, isDeleted = false, deletedAt = null))
        coEvery { api.deleteDocument(1) } returns successUnit()

        val result = repo.deleteDocument(1)

        assertTrue(result.isSuccess)
        // Optimistic UI invariant: softDelete + ack MUST happen BEFORE the API call
        // so the Gmail-style swipe animation completes immediately. coVerifyOrder
        // works on spyk-wrapped DAOs.
        coVerifyOrder {
            cachedDocumentDao.softDelete(eq(1), any())
            cachedTaskDao.acknowledgeTasksForDocument("1")
            api.deleteDocument(1)
        }
        // Real DB sanity: the document is now soft-deleted.
        assertTrue(cachedDocumentDao.getDeletedIds().contains(1))
    }

    @Test
    fun `deleteDocument online API failure rolls back optimistic delete`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        cachedDocumentDao.insert(cachedDoc(id = 1, isDeleted = false, deletedAt = null))
        coEvery { api.deleteDocument(1) } returns errorUnit(500)

        val result = repo.deleteDocument(1)

        assertTrue(result.isFailure)
        // The end-state assertion proves rollback against the real DB; the
        // intermediate softDelete + restoreDocument calls are implementation
        // detail and not asserted directly per the migration's spirit.
        assertTrue(!cachedDocumentDao.getDeletedIds().contains(1))
    }

    @Test
    fun `deleteDocument online cascade-acknowledges tasks for document`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        // Seed real tasks: two for doc=1 (unacked), one for doc=2, one already acked.
        cachedTaskDao.insertAll(
            listOf(
                cachedTask(id = 10, relatedDocument = "1", acknowledged = false),
                cachedTask(id = 11, relatedDocument = "1", acknowledged = false),
                cachedTask(id = 12, relatedDocument = "2", acknowledged = false),
                cachedTask(id = 13, relatedDocument = "1", acknowledged = true),
            )
        )
        cachedDocumentDao.insert(cachedDoc(id = 1, isDeleted = false, deletedAt = null))
        coEvery { api.deleteDocument(1) } returns successUnit()
        val ackSlot = slot<AcknowledgeTasksRequest>()
        coEvery { api.acknowledgeTasks(capture(ackSlot)) } returns successUnit()

        val result = repo.deleteDocument(1)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { api.acknowledgeTasks(any()) }
        // Only unacknowledged tasks for doc=1 should be in the API payload.
        assertEquals(setOf(10, 11), ackSlot.captured.tasks.toSet())
        coVerify { cachedTaskDao.acknowledgeTasksForDocument("1") }
        // Real DB: tasks 10 + 11 are now acknowledged in the cache.
        val tasksAfter = cachedTaskDao.getAllTasks().associateBy { it.id }
        assertTrue(tasksAfter[10]!!.acknowledged)
        assertTrue(tasksAfter[11]!!.acknowledged)
        assertTrue(!tasksAfter[12]!!.acknowledged) // different doc, untouched
    }

    @Test
    fun `deleteDocument offline queues delete via sync and softDeletes`() = runTest {
        // Switch executeOrQueue stub to run the OFFLINE lambda.
        coEvery { sync.executeOrQueue<Unit>(any(), any()) } coAnswers {
            try {
                Result.success(secondArg<suspend () -> Unit>().invoke())
            } catch (e: Exception) {
                Result.failure(PaperlessException.from(e))
            }
        }
        cachedDocumentDao.insert(cachedDoc(id = 7, isDeleted = false, deletedAt = null))

        val result = repo.deleteDocument(7)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { sync.queueDocumentDelete(7) }
        // Offline asymmetric ordering: ack BEFORE softDelete so reactivity works.
        coVerifyOrder {
            sync.queueDocumentDelete(7)
            cachedTaskDao.acknowledgeTasksForDocument("7")
            cachedDocumentDao.softDelete(eq(7), any())
        }
        assertTrue(cachedDocumentDao.getDeletedIds().contains(7))
    }

    @Test
    fun `restoreDocument single delegates to bulk with one-element list`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        cachedDocumentDao.insert(cachedDoc(id = 42)) // already trashed
        val requestSlot = slot<TrashBulkActionRequest>()
        coEvery { api.trashBulkAction(capture(requestSlot)) } returns successUnit()

        val result = repo.restoreDocument(42)

        assertTrue(result.isSuccess)
        assertEquals(listOf(42), requestSlot.captured.documents)
        assertEquals("restore", requestSlot.captured.action)
        // Real DB: doc 42 is no longer in the deleted set.
        assertTrue(!cachedDocumentDao.getDeletedIds().contains(42))
    }

    @Test
    fun `restoreDocuments online happy path`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        cachedDocumentDao.insertAll(listOf(cachedDoc(id = 1), cachedDoc(id = 2), cachedDoc(id = 3)))
        val requestSlot = slot<TrashBulkActionRequest>()
        coEvery { api.trashBulkAction(capture(requestSlot)) } returns successUnit()

        val result = repo.restoreDocuments(listOf(1, 2, 3))

        assertTrue(result.isSuccess)
        assertEquals(listOf(1, 2, 3), requestSlot.captured.documents)
        assertEquals("restore", requestSlot.captured.action)
        assertTrue(cachedDocumentDao.getDeletedIds().toSet().intersect(setOf(1, 2, 3)).isEmpty())
    }

    @Test
    fun `restoreDocuments offline queues restore via sync`() = runTest {
        coEvery { sync.executeOrQueue<Unit>(any(), any()) } coAnswers {
            try {
                Result.success(secondArg<suspend () -> Unit>().invoke())
            } catch (e: Exception) {
                Result.failure(PaperlessException.from(e))
            }
        }
        cachedDocumentDao.insertAll(listOf(cachedDoc(id = 1), cachedDoc(id = 2), cachedDoc(id = 3)))

        val result = repo.restoreDocuments(listOf(1, 2, 3))

        assertTrue(result.isSuccess)
        coVerify {
            sync.queueTrashAction(listOf(1, 2, 3), DocumentSyncRepository.TrashAction.RESTORE)
        }
        assertTrue(cachedDocumentDao.getDeletedIds().toSet().intersect(setOf(1, 2, 3)).isEmpty())
    }

    @Test
    fun `permanentlyDeleteDocument single delegates to bulk`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        cachedDocumentDao.insert(cachedDoc(id = 99))
        val requestSlot = slot<TrashBulkActionRequest>()
        coEvery { api.trashBulkAction(capture(requestSlot)) } returns successUnit()

        val result = repo.permanentlyDeleteDocument(99)

        assertTrue(result.isSuccess)
        assertEquals(listOf(99), requestSlot.captured.documents)
        assertEquals("empty", requestSlot.captured.action)
        // Hard delete: row is gone, not just hidden via soft delete.
        val allIds = cachedDocumentDao.getAllIds()
        assertTrue(!allIds.contains(99))
    }

    @Test
    fun `permanentlyDeleteDocuments online and offline branches`() = runTest {
        // Online branch (default sync stub runs online lambda).
        cachedDocumentDao.insertAll(listOf(cachedDoc(id = 5), cachedDoc(id = 6)))
        val requestSlot = slot<TrashBulkActionRequest>()
        coEvery { api.trashBulkAction(capture(requestSlot)) } returns successUnit()

        val onlineResult = repo.permanentlyDeleteDocuments(listOf(5, 6))

        assertTrue(onlineResult.isSuccess)
        assertEquals("empty", requestSlot.captured.action)
        assertEquals(listOf(5, 6), requestSlot.captured.documents)
        val afterOnline = cachedDocumentDao.getAllIds().toSet()
        assertTrue(!afterOnline.contains(5) && !afterOnline.contains(6))

        // Offline branch — switch sync stub to run the OFFLINE lambda.
        cachedDocumentDao.insertAll(listOf(cachedDoc(id = 7), cachedDoc(id = 8)))
        coEvery { sync.executeOrQueue<Unit>(any(), any()) } coAnswers {
            try {
                Result.success(secondArg<suspend () -> Unit>().invoke())
            } catch (e: Exception) {
                Result.failure(PaperlessException.from(e))
            }
        }

        val offlineResult = repo.permanentlyDeleteDocuments(listOf(7, 8))

        assertTrue(offlineResult.isSuccess)
        coVerify {
            sync.queueTrashAction(listOf(7, 8), DocumentSyncRepository.TrashAction.PERMANENT_DELETE)
        }
        val afterOffline = cachedDocumentDao.getAllIds().toSet()
        assertTrue(!afterOffline.contains(7) && !afterOffline.contains(8))
    }

    @Test
    fun `cleanupOrphanedTrashDocs removes locally-deleted-but-not-on-server`() = runTest {
        cachedDocumentDao.insertAll((1..4).map { cachedDoc(id = it) })

        repo.cleanupOrphanedTrashDocs(setOf(1, 3))

        // 2 and 4 are local-only orphans → must be hard-deleted; 1 and 3 stay
        // (still in the trashed set because all four were inserted as trashed).
        val remainingTrashedIds = cachedDocumentDao.getDeletedIds().toSet()
        assertEquals(setOf(1, 3), remainingTrashedIds)
    }
}
