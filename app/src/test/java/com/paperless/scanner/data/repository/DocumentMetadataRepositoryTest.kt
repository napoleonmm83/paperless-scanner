package com.paperless.scanner.data.repository

import android.content.Context
import com.google.gson.Gson
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.api.models.Document as ApiDocument
import com.paperless.scanner.data.api.models.UpdateDocumentRequest
import com.paperless.scanner.data.api.models.UpdateDocumentWithPermissionsRequest
import com.paperless.scanner.data.database.dao.CachedDocumentDao
import com.paperless.scanner.data.database.dao.CachedTagDao
import com.paperless.scanner.data.database.entities.CachedDocument
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.data.service.DocumentSerializer
import com.paperless.scanner.domain.model.Document
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.HttpException
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
class DocumentMetadataRepositoryTest {

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
        cachedDocumentDao = mockk(relaxed = true)
        cachedTagDao = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)
        serializer = DocumentSerializer(Gson())
        sync = mockk(relaxed = true)
        // Default: executeOrQueue runs the online lambda (online happy path).
        coEvery { sync.executeOrQueue<Document>(any(), any()) } coAnswers {
            Result.success(firstArg<suspend () -> Document>().invoke())
        }

        repo = DocumentMetadataRepository(
            context,
            api,
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

    private fun http404(): HttpException =
        HttpException(Response.error<Any>(404, "".toResponseBody(null)))

    // -------- tests --------

    @Test
    fun `observeDocument maps null cached entity to null domain`() = runTest {
        every { cachedDocumentDao.observeDocument(7) } returns flowOf(null)

        val result = repo.observeDocument(7).first()

        assertNull(result)
    }

    @Test
    fun `observeDocument maps cached entity to domain document`() = runTest {
        every { cachedDocumentDao.observeDocument(7) } returns flowOf(cachedDoc(id = 7, title = "Hello"))

        val result = repo.observeDocument(7).first()

        assertNotNull(result)
        assertEquals(7, result!!.id)
        assertEquals("Hello", result.title)
    }

    @Test
    fun `getDocument online happy path inserts cache and returns domain`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        coEvery { api.getDocument(1) } returns apiDoc(id = 1, title = "API Doc")

        val result = repo.getDocument(1)

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull()!!.id)
        assertEquals("API Doc", result.getOrNull()!!.title)
        coVerify { cachedDocumentDao.insert(any()) }
    }

    @Test
    fun `getDocument online HttpException falls back to cache when cached`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        coEvery { api.getDocument(1) } throws http404()
        coEvery { cachedDocumentDao.getDocument(1) } returns cachedDoc(id = 1, title = "Cached")

        val result = repo.getDocument(1)

        assertTrue(result.isSuccess)
        assertEquals("Cached", result.getOrNull()!!.title)
    }

    @Test
    fun `getDocument offline with cache returns cached domain`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns false
        coEvery { cachedDocumentDao.getDocument(1) } returns cachedDoc(id = 1, title = "Offline-Cached")

        val result = repo.getDocument(1, forceRefresh = false)

        assertTrue(result.isSuccess)
        assertEquals("Offline-Cached", result.getOrNull()!!.title)
    }

    @Test
    fun `getDocument offline without cache returns ClientError 404`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns false
        coEvery { cachedDocumentDao.getDocument(1) } returns null

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
        coVerify { cachedDocumentDao.insert(any()) }
    }

    @Test
    fun `updateDocument online with tag delta calls cachedTagDao updateDocumentCount`() = runTest {
        // Old tags: [1, 2]; new tags: [2, 3] -> remove 1, add 3
        coEvery { cachedDocumentDao.getDocument(1) } returns cachedDoc(id = 1, tagsJson = "[1,2]")
        coEvery { api.updateDocument(eq(1), any()) } returns apiDoc(id = 1, tags = listOf(2, 3))

        val result = repo.updateDocument(documentId = 1, tags = listOf(2, 3))

        assertTrue(result.isSuccess)
        coVerify { cachedTagDao.updateDocumentCount(1, -1) }
        coVerify { cachedTagDao.updateDocumentCount(3, 1) }
    }

    @Test
    fun `updateDocument offline queues update via sync and returns optimistic cached domain`() = runTest {
        // Switch executeOrQueue stub to run the OFFLINE lambda
        coEvery { sync.executeOrQueue<Document>(any(), any()) } coAnswers {
            Result.success(secondArg<suspend () -> Document>().invoke())
        }
        coEvery { cachedDocumentDao.getDocument(1) } returns cachedDoc(id = 1, title = "Optimistic")
        val payloadSlot = slot<DocumentSyncRepository.DocumentUpdatePayload>()
        coEvery { sync.queueDocumentUpdate(eq(1), capture(payloadSlot)) } returns Unit

        val result = repo.updateDocument(documentId = 1, title = "New Title")

        assertTrue(result.isSuccess)
        assertEquals("Optimistic", result.getOrNull()!!.title)
        coVerify { sync.queueDocumentUpdate(eq(1), any()) }
        assertEquals("New Title", payloadSlot.captured.title)
    }

    @Test
    fun `updateDocument offline without cache returns ClientError 404`() = runTest {
        // Switch executeOrQueue stub to run the OFFLINE lambda — the underlying
        // PaperlessException.ClientError is thrown, then re-wrapped by executeOrQueue.
        coEvery { sync.executeOrQueue<Document>(any(), any()) } coAnswers {
            try {
                Result.success(secondArg<suspend () -> Document>().invoke())
            } catch (e: retrofit2.HttpException) {
                Result.failure(PaperlessException.fromHttpCode(e.code(), e.message()))
            } catch (e: Exception) {
                Result.failure(PaperlessException.from(e))
            }
        }
        coEvery { cachedDocumentDao.getDocument(1) } returns null
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
        coVerify { cachedDocumentDao.insert(any()) }
    }

    @Test
    fun `updateDocumentPermissions offline returns NetworkError`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns false

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
