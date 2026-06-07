package com.paperless.scanner.data.repository

import android.content.Context
import androidx.test.filters.LargeTest
import app.cash.turbine.test
import com.google.gson.Gson
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.domain.error.PaperlessException
import com.paperless.scanner.data.api.models.Document as ApiDocument
import com.paperless.scanner.data.api.models.UpdateDocumentRequest
import com.paperless.scanner.data.api.models.UpdateDocumentWithPermissionsRequest
import com.paperless.scanner.data.database.dao.CachedDocumentDao
import com.paperless.scanner.data.database.dao.CachedTagDao
import com.paperless.scanner.data.database.entities.CachedDocument
import com.paperless.scanner.data.database.entities.CachedTag
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.data.service.DocumentSerializer
import com.paperless.scanner.domain.model.Document
import com.paperless.scanner.testing.BaseRoomRepositoryTest
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * Repository tests for [DocumentMetadataRepository].
 *
 * Uses a real in-memory Room database (see [BaseRoomRepositoryTest]) so the
 * `cachedDocumentDao` + `cachedTagDao` operations execute against the actual
 * schema. `DocumentSerializer` is also a real instance (Gson-based, no I/O).
 * Mocked: `PaperlessApi`, `NetworkMonitor`, `Context`, and the
 * `DocumentSyncRepository` collaborator (it has its own dedicated tests).
 */
@LargeTest
class DocumentMetadataRepositoryTest : BaseRoomRepositoryTest() {

    private lateinit var context: Context
    private lateinit var api: PaperlessApi
    private lateinit var cachedDocumentDao: CachedDocumentDao
    private lateinit var cachedTagDao: CachedTagDao
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var serializer: DocumentSerializer
    private lateinit var sync: DocumentSyncRepository
    private lateinit var repo: DocumentMetadataRepository

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        api = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)
        cachedDocumentDao = database.cachedDocumentDao()
        cachedTagDao = database.cachedTagDao()
        serializer = DocumentSerializer(Gson())
        sync = mockk(relaxed = true)
        // Default: executeOrQueue runs the online lambda (online happy path).
        coEvery { sync.executeOrQueue<Document>(any(), any()) } coAnswers {
            Result.success(firstArg<suspend () -> Document>().invoke())
        }

        repo = DocumentMetadataRepository(
            context,
            api,
            database,
            cachedDocumentDao,
            cachedTagDao,
            networkMonitor,
            serializer,
            sync,
        )
    }

    // -------- helpers --------

    private fun apiDoc(
        id: Int = 1,
        title: String = "Doc $id",
        tags: List<Int> = emptyList(),
    ): ApiDocument = ApiDocument(
        id = id,
        title = title,
        content = null,
        created = "2026-05-06T00:00:00Z",
        modified = "2026-05-06T00:00:00Z",
        added = "2026-05-06T00:00:00Z",
        correspondentId = null,
        documentTypeId = null,
        tags = tags,
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
        tagsJson: String = "[]",
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
        tags = tagsJson,
        customFields = null,
        isCached = true,
        lastSyncedAt = 0L,
        isDeleted = false,
        deletedAt = null,
    )

    private fun cachedTag(id: Int, name: String, documentCount: Int = 0) = CachedTag(
        id = id,
        name = name,
        color = null,
        match = null,
        matchingAlgorithm = null,
        isInboxTag = false,
        documentCount = documentCount,
    )

    private fun http404(): HttpException =
        HttpException(Response.error<Any>(404, "".toResponseBody(null)))

    // -------- tests --------

    @Test
    fun `observeDocument emits null for missing id, then maps inserted entity to domain`() = runTest {
        repo.observeDocument(7).test {
            // Empty cache → null first.
            assertNull(awaitItem())

            // Insert after subscription; Room must invalidate and re-emit a domain doc.
            cachedDocumentDao.insert(cachedDoc(id = 7, title = "Hello"))

            val mapped = awaitItem()
            assertNotNull(mapped)
            assertEquals(7, mapped!!.id)
            assertEquals("Hello", mapped.title)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `getDocument online happy path inserts cache and returns domain`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        coEvery { api.getDocument(1) } returns apiDoc(id = 1, title = "API Doc")

        val result = repo.getDocument(1)

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull()!!.id)
        assertEquals("API Doc", result.getOrNull()!!.title)
        // Real DB verification: cache row was actually written.
        assertEquals("API Doc", cachedDocumentDao.getDocument(1)?.title)
    }

    @Test
    fun `getDocument online HttpException falls back to cache when cached`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        coEvery { api.getDocument(1) } throws http404()
        cachedDocumentDao.insert(cachedDoc(id = 1, title = "Cached"))

        val result = repo.getDocument(1)

        assertTrue(result.isSuccess)
        assertEquals("Cached", result.getOrNull()!!.title)
    }

    @Test
    fun `getDocument offline with cache returns cached domain`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns false
        cachedDocumentDao.insert(cachedDoc(id = 1, title = "Offline-Cached"))

        val result = repo.getDocument(1, forceRefresh = false)

        assertTrue(result.isSuccess)
        assertEquals("Offline-Cached", result.getOrNull()!!.title)
    }

    @Test
    fun `getDocument offline without cache returns ClientError 404`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns false

        val result = repo.getDocument(1, forceRefresh = false)

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(
            "expected ClientError, got $ex",
            ex is PaperlessException.ClientError
        )
        assertEquals(404, (ex as PaperlessException.ClientError).code)
    }

    @Test
    fun `updateDocument online updates cache and returns domain`() = runTest {
        val captured = slot<UpdateDocumentRequest>()
        coEvery { api.updateDocument(eq(1), capture(captured)) } returns apiDoc(id = 1, title = "Updated")

        val result = repo.updateDocument(documentId = 1, title = "Updated")

        assertTrue(result.isSuccess)
        assertEquals("Updated", result.getOrNull()!!.title)
        assertEquals("Updated", captured.captured.title)
        // Real DB verification: cache row was actually written.
        assertEquals("Updated", cachedDocumentDao.getDocument(1)?.title)
    }

    @Test
    fun `updateDocument online with tag delta calls cachedTagDao updateDocumentCount`() = runTest {
        // Seed real tags so updateDocumentCount has rows to act on, and seed the
        // document's old tag set [1, 2].
        cachedTagDao.insertAll(listOf(cachedTag(1, "T1", 1), cachedTag(2, "T2", 1), cachedTag(3, "T3", 0)))
        cachedDocumentDao.insert(cachedDoc(id = 1, tagsJson = "[1,2]"))
        coEvery { api.updateDocument(eq(1), any()) } returns apiDoc(id = 1, tags = listOf(2, 3))

        val result = repo.updateDocument(documentId = 1, tags = listOf(2, 3))

        assertTrue(result.isSuccess)
        // Real DB verification: documentCount on tag 1 should drop, on tag 3 should rise.
        assertEquals(0, cachedTagDao.getTag(1)?.documentCount) // 1 - 1
        assertEquals(1, cachedTagDao.getTag(2)?.documentCount) // unchanged
        assertEquals(1, cachedTagDao.getTag(3)?.documentCount) // 0 + 1
    }

    @Test
    fun `updateDocument rolls back cache insert when a tag-delta DAO call throws inside the transaction`() = runTest {
        // #65: cache insert + per-tag count deltas must be ONE atomic unit. If a tag-delta
        // DAO call throws inside db.withTransaction, the whole transaction rolls back so the
        // updated document is NEVER persisted with inconsistent tag counts alongside it.
        //
        // To force the delta to throw we build a dedicated repo with a MOCKED cachedTagDao
        // (throws on updateDocumentCount) while keeping the REAL database + real
        // cachedDocumentDao, so the rollback applies to the real DB transaction.
        val throwingTagDao = mockk<CachedTagDao>(relaxed = true)
        coEvery { throwingTagDao.updateDocumentCount(any(), any()) } throws RuntimeException("delta boom")

        val repoWithThrowingTagDao = DocumentMetadataRepository(
            context,
            api,
            database,
            cachedDocumentDao,
            throwingTagDao,
            networkMonitor,
            serializer,
            sync,
        )

        // Seed the document's old tag set [1, 2] so changing to [2, 3] yields a real delta
        // (remove 1, add 3) that hits updateDocumentCount and triggers the throw.
        cachedDocumentDao.insert(cachedDoc(id = 1, title = "Before", tagsJson = "[1,2]"))
        coEvery { api.updateDocument(eq(1), any()) } returns
            apiDoc(id = 1, title = "After", tags = listOf(2, 3))

        val result = runOnlineUpdateThroughSync(repoWithThrowingTagDao, documentId = 1, tags = listOf(2, 3))

        assertTrue(result.isFailure)
        // Real DB verification: the transaction rolled back — the document still shows the
        // pre-update state, NOT the "After"/tags=[2,3] update that the online lambda attempted.
        val cached = cachedDocumentDao.getDocument(1)
        assertEquals("Before", cached?.title)
        assertEquals("[1,2]", cached?.tags)
    }

    /**
     * Runs the online lambda of updateDocument through the (relaxed) sync mock so that any
     * exception thrown inside the transaction surfaces as Result.failure — mirroring how the
     * real DocumentSyncRepository.executeOrQueue wraps the online lambda.
     */
    private suspend fun runOnlineUpdateThroughSync(
        target: DocumentMetadataRepository,
        documentId: Int,
        tags: List<Int>,
    ): Result<Document> {
        coEvery { sync.executeOrQueue<Document>(any(), any()) } coAnswers {
            try {
                Result.success(firstArg<suspend () -> Document>().invoke())
            } catch (e: Exception) {
                Result.failure(PaperlessException.from(e))
            }
        }
        return target.updateDocument(documentId = documentId, tags = tags)
    }

    @Test
    fun `updateDocument offline queues update via sync and returns optimistic cached domain`() = runTest {
        // Switch executeOrQueue stub to run the OFFLINE lambda
        coEvery { sync.executeOrQueue<Document>(any(), any()) } coAnswers {
            Result.success(secondArg<suspend () -> Document>().invoke())
        }
        cachedDocumentDao.insert(cachedDoc(id = 1, title = "Optimistic"))
        val payloadSlot = slot<DocumentSyncRepository.DocumentUpdatePayload>()
        coEvery { sync.queueDocumentUpdate(eq(1), capture(payloadSlot)) } returns Unit

        val result = repo.updateDocument(documentId = 1, title = "New Title")

        assertTrue(result.isSuccess)
        assertEquals("Optimistic", result.getOrNull()!!.title)
        assertEquals("New Title", payloadSlot.captured.title)
    }

    @Test
    fun `updateDocument offline without cache returns ClientError 404`() = runTest {
        // Switch executeOrQueue stub to run the OFFLINE lambda — the underlying
        // PaperlessException.ClientError is thrown, then re-wrapped by executeOrQueue.
        coEvery { sync.executeOrQueue<Document>(any(), any()) } coAnswers {
            try {
                Result.success(secondArg<suspend () -> Document>().invoke())
            } catch (e: HttpException) {
                Result.failure(PaperlessException.fromHttpCode(e.code(), e.message()))
            } catch (e: Exception) {
                Result.failure(PaperlessException.from(e))
            }
        }
        coEvery { sync.queueDocumentUpdate(any(), any()) } returns Unit

        val result = repo.updateDocument(documentId = 1, title = "Doesn't matter")

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(
            "expected ClientError, got $ex",
            ex is PaperlessException.ClientError
        )
        assertEquals(404, (ex as PaperlessException.ClientError).code)
    }

    @Test
    fun `updateDocumentPermissions online inserts cache and returns domain`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        val captured = slot<UpdateDocumentWithPermissionsRequest>()
        coEvery { api.updateDocumentPermissions(eq(1), capture(captured)) } returns apiDoc(id = 1, title = "Perm")

        val result = repo.updateDocumentPermissions(
            documentId = 1,
            owner = 5,
            viewUsers = listOf(10, 11),
            viewGroups = listOf(20),
            changeUsers = listOf(30),
            changeGroups = listOf(40),
        )

        assertTrue(result.isSuccess)
        assertEquals("Perm", result.getOrNull()!!.title)
        assertEquals(5, captured.captured.owner)
        assertEquals(listOf(10, 11), captured.captured.setPermissions!!.view.users)
        assertEquals(listOf(40), captured.captured.setPermissions!!.change.groups)
        assertEquals("Perm", cachedDocumentDao.getDocument(1)?.title)
    }

    @Test
    fun `updateDocumentPermissions offline returns NetworkError`() = runTest {
        every { networkMonitor.checkOnlineStatus() } returns false

        val result = repo.updateDocumentPermissions(
            documentId = 1,
            owner = null,
            viewUsers = emptyList(),
            viewGroups = emptyList(),
            changeUsers = emptyList(),
            changeGroups = emptyList(),
        )

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(
            "expected NetworkError, got $ex",
            ex is PaperlessException.NetworkError
        )
    }
}
