package com.paperless.scanner.data.repository

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.paperless.scanner.data.analytics.CrashlyticsHelper
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.database.dao.CachedDocumentDao
import com.paperless.scanner.data.database.dao.CachedTagDao
import com.paperless.scanner.data.database.dao.CachedTaskDao
import com.paperless.scanner.data.database.dao.PendingChangeDao
import com.paperless.scanner.data.network.NetworkMonitor
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class DocumentRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver
    private lateinit var api: PaperlessApi
    private lateinit var cachedDocumentDao: CachedDocumentDao
    private lateinit var cachedTagDao: CachedTagDao
    private lateinit var cachedTaskDao: CachedTaskDao
    private lateinit var pendingChangeDao: PendingChangeDao
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var serverHealthMonitor: com.paperless.scanner.data.health.ServerHealthMonitor
    private lateinit var gson: Gson
    private lateinit var crashlyticsHelper: CrashlyticsHelper
    private lateinit var documentRepository: DocumentRepository
    private lateinit var cacheDir: File

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        contentResolver = mockk(relaxed = true)
        api = mockk()
        cachedDocumentDao = mockk(relaxed = true)
        cachedTagDao = mockk(relaxed = true)
        cachedTaskDao = mockk(relaxed = true)
        pendingChangeDao = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)
        serverHealthMonitor = mockk(relaxed = true)
        gson = mockk(relaxed = true)
        crashlyticsHelper = mockk(relaxed = true)
        cacheDir = tempFolder.newFolder("cache")

        every { context.contentResolver } returns contentResolver
        every { context.cacheDir } returns cacheDir

        documentRepository = DocumentRepository(
            context,
            api,
            cachedDocumentDao,
            cachedTagDao,
            cachedTaskDao,
            pendingChangeDao,
            networkMonitor,
            serverHealthMonitor,
            gson,
            crashlyticsHelper
        )
    }

    @Test
    fun `uploadDocument success returns task id`() = runTest {
        val uri = mockk<Uri>()
        val testContent = "fake image content".toByteArray()
        val expectedTaskId = "task-uuid-12345"

        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream(testContent)
        coEvery {
            api.uploadDocument(any(), any(), any(), any(), any())
        } returns expectedTaskId.toResponseBody()

        val result = documentRepository.uploadDocument(uri, "Test Document")

        assertTrue(result.isSuccess)
        assertEquals(expectedTaskId, result.getOrNull())
    }

    @Test
    fun `uploadDocument with quoted response strips quotes`() = runTest {
        val uri = mockk<Uri>()
        val testContent = "fake image".toByteArray()

        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream(testContent)
        coEvery {
            api.uploadDocument(any(), any(), any(), any(), any())
        } returns "\"quoted-task-id\"".toResponseBody()

        val result = documentRepository.uploadDocument(uri)

        assertTrue(result.isSuccess)
        assertEquals("quoted-task-id", result.getOrNull())
    }

    @Test
    fun `uploadDocument with tags sends separate parts for each tag`() = runTest {
        val uri = mockk<Uri>()
        val testContent = "image".toByteArray()

        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream(testContent)
        coEvery {
            api.uploadDocument(any(), any(), any(), any(), any())
        } returns "task-123".toResponseBody()

        val result = documentRepository.uploadDocument(
            uri = uri,
            tagIds = listOf(1, 2, 3)
        )

        assertTrue(result.isSuccess)
        coVerify {
            api.uploadDocument(
                document = any(),
                title = any(),
                tags = any(),
                documentType = any(),
                correspondent = any()
            )
        }
    }

    @Test
    fun `uploadDocument without optional params sends nulls`() = runTest {
        val uri = mockk<Uri>()
        val testContent = "image".toByteArray()

        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream(testContent)
        coEvery {
            api.uploadDocument(any(), null, any(), null, null)
        } returns "task-id".toResponseBody()

        val result = documentRepository.uploadDocument(uri)

        assertTrue(result.isSuccess)
        coVerify {
            api.uploadDocument(
                document = any(),
                title = null,
                tags = emptyList(),
                documentType = null,
                correspondent = null
            )
        }
    }

    @Test
    fun `uploadDocument with document type sends type id`() = runTest {
        val uri = mockk<Uri>()
        val testContent = "image".toByteArray()

        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream(testContent)
        coEvery {
            api.uploadDocument(any(), any(), any(), any(), any())
        } returns "task-id".toResponseBody()

        val result = documentRepository.uploadDocument(
            uri = uri,
            title = "Invoice",
            documentTypeId = 5
        )

        assertTrue(result.isSuccess)
    }

    @Test
    fun `uploadDocument cleans up temp file after upload`() = runTest {
        val uri = mockk<Uri>()
        val testContent = "image".toByteArray()

        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream(testContent)
        coEvery {
            api.uploadDocument(any(), any(), any(), any(), any())
        } returns "task-id".toResponseBody()

        documentRepository.uploadDocument(uri)

        val remainingFiles = cacheDir.listFiles()?.filter { it.name.startsWith("document_") }
        assertTrue(remainingFiles.isNullOrEmpty())
    }

    @Test
    fun `uploadDocument with invalid uri returns failure`() = runTest {
        val uri = mockk<Uri>()
        every { contentResolver.openInputStream(uri) } returns null

        val result = documentRepository.uploadDocument(uri)

        assertTrue(result.isFailure)
        // DocumentRepository wraps IllegalArgumentException in PaperlessException.ContentError
        assertTrue(result.exceptionOrNull() is PaperlessException.ContentError)
    }

    @Test
    fun `uploadDocument network error returns failure`() = runTest {
        val uri = mockk<Uri>()
        val testContent = "image".toByteArray()

        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream(testContent)
        coEvery {
            api.uploadDocument(any(), any(), any(), any(), any())
        } throws IOException("Network unavailable")

        val result = documentRepository.uploadDocument(uri)

        assertTrue(result.isFailure)
        // DocumentRepository wraps IOException in PaperlessException.NetworkError
        assertTrue(result.exceptionOrNull() is PaperlessException.NetworkError)
    }

    @Test
    fun `uploadDocument creates file with jpg extension`() = runTest {
        val uri = mockk<Uri>()
        val testContent = "image".toByteArray()
        val documentSlot = slot<MultipartBody.Part>()

        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream(testContent)
        coEvery {
            api.uploadDocument(capture(documentSlot), any(), any(), any(), any())
        } returns "task-id".toResponseBody()

        documentRepository.uploadDocument(uri)

        val capturedPart = documentSlot.captured
        assertTrue(capturedPart.body.contentType()?.toString()?.contains("image/jpeg") == true)
    }

    // ==================== Trash Feature Tests ====================

    @Test
    fun `deleteDocument online success soft deletes from cache`() = runTest {
        every { networkMonitor.checkOnlineStatus() } returns true
        coEvery { cachedTaskDao.getAllTasks() } returns emptyList()
        coEvery { api.deleteDocument(1) } returns mockk {
            every { isSuccessful } returns true
        }

        val result = documentRepository.deleteDocument(1)

        assertTrue(result.isSuccess)
        coVerify { cachedDocumentDao.softDelete(1, any()) }
    }

    @Test
    fun `deleteDocument offline queues pending change`() = runTest {
        every { networkMonitor.checkOnlineStatus() } returns false

        val result = documentRepository.deleteDocument(1)

        assertTrue(result.isSuccess)
        coVerify { cachedDocumentDao.softDelete(1, any()) }
        coVerify { pendingChangeDao.insert(any()) }
    }

    @Test
    fun `deleteDocument API failure returns error`() = runTest {
        every { networkMonitor.checkOnlineStatus() } returns true
        coEvery { cachedTaskDao.getAllTasks() } returns emptyList()
        coEvery { api.deleteDocument(1) } returns mockk {
            every { isSuccessful } returns false
            every { code() } returns 404
            every { message() } returns "Not found"
        }

        val result = documentRepository.deleteDocument(1)

        assertTrue(result.isFailure)
    }

    @Test
    fun `restoreDocument online success updates local cache`() = runTest {
        every { networkMonitor.checkOnlineStatus() } returns true
        coEvery { api.trashBulkAction(any()) } returns mockk {
            every { isSuccessful } returns true
        }

        val result = documentRepository.restoreDocument(1)

        assertTrue(result.isSuccess)
        coVerify { cachedDocumentDao.restoreDocument(1) }
    }

    @Test
    fun `restoreDocument offline queues pending change`() = runTest {
        every { networkMonitor.checkOnlineStatus() } returns false

        val result = documentRepository.restoreDocument(1)

        assertTrue(result.isSuccess)
        coVerify { cachedDocumentDao.restoreDocument(1) }
        coVerify { pendingChangeDao.insert(any()) }
    }

    @Test
    fun `restoreDocuments bulk calls API with correct action`() = runTest {
        every { networkMonitor.checkOnlineStatus() } returns true
        val requestSlot = slot<com.paperless.scanner.data.api.models.TrashBulkActionRequest>()
        coEvery { api.trashBulkAction(capture(requestSlot)) } returns mockk {
            every { isSuccessful } returns true
        }

        val result = documentRepository.restoreDocuments(listOf(1, 2, 3))

        assertTrue(result.isSuccess)
        assertEquals(listOf(1, 2, 3), requestSlot.captured.documents)
        assertEquals("restore", requestSlot.captured.action)
        coVerify { cachedDocumentDao.restoreDocuments(listOf(1, 2, 3)) }
    }

    @Test
    fun `permanentlyDeleteDocument online success hard deletes from cache`() = runTest {
        every { networkMonitor.checkOnlineStatus() } returns true
        val requestSlot = slot<com.paperless.scanner.data.api.models.TrashBulkActionRequest>()
        coEvery { api.trashBulkAction(capture(requestSlot)) } returns mockk {
            every { isSuccessful } returns true
        }

        val result = documentRepository.permanentlyDeleteDocument(1)

        assertTrue(result.isSuccess)
        assertEquals("empty", requestSlot.captured.action)
        coVerify { cachedDocumentDao.hardDelete(1) }
    }

    @Test
    fun `permanentlyDeleteDocument offline queues pending change`() = runTest {
        every { networkMonitor.checkOnlineStatus() } returns false

        val result = documentRepository.permanentlyDeleteDocument(1)

        assertTrue(result.isSuccess)
        coVerify { cachedDocumentDao.hardDelete(1) }
        coVerify { pendingChangeDao.insert(any()) }
    }

    @Test
    fun `permanentlyDeleteDocuments bulk uses empty action`() = runTest {
        every { networkMonitor.checkOnlineStatus() } returns true
        val requestSlot = slot<com.paperless.scanner.data.api.models.TrashBulkActionRequest>()
        coEvery { api.trashBulkAction(capture(requestSlot)) } returns mockk {
            every { isSuccessful } returns true
        }

        val result = documentRepository.permanentlyDeleteDocuments(listOf(1, 2))

        assertTrue(result.isSuccess)
        assertEquals(listOf(1, 2), requestSlot.captured.documents)
        assertEquals("empty", requestSlot.captured.action)
        coVerify { cachedDocumentDao.deleteByIds(listOf(1, 2)) }
    }

    @Test
    fun `observeTrashedDocuments returns Flow from DAO`() = runTest {
        val mockDocuments = listOf(
            mockk<com.paperless.scanner.data.database.entities.CachedDocument>(relaxed = true)
        )
        every { cachedDocumentDao.observeDeletedDocuments() } returns kotlinx.coroutines.flow.flowOf(mockDocuments)

        val flow = documentRepository.observeTrashedDocuments()

        flow.collect { documents ->
            assertEquals(1, documents.size)
        }
    }

    @Test
    fun `observeTrashedDocumentsCount returns count Flow from DAO`() = runTest {
        every { cachedDocumentDao.observeDeletedCount() } returns kotlinx.coroutines.flow.flowOf(5)

        val flow = documentRepository.observeTrashedDocumentsCount()

        flow.collect { count ->
            assertEquals(5, count)
        }
    }
}
