package com.paperless.scanner.data.repository

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.database.dao.CachedDocumentDao
import com.paperless.scanner.data.database.dao.PendingChangeDao
import com.paperless.scanner.data.network.NetworkMonitor
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
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

class DocumentRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver
    private lateinit var api: PaperlessApi
    private lateinit var cachedDocumentDao: CachedDocumentDao
    private lateinit var pendingChangeDao: PendingChangeDao
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var documentRepository: DocumentRepository
    private lateinit var cacheDir: File

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        contentResolver = mockk(relaxed = true)
        api = mockk()
        cachedDocumentDao = mockk(relaxed = true)
        pendingChangeDao = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)
        cacheDir = tempFolder.newFolder("cache")

        every { context.contentResolver } returns contentResolver
        every { context.cacheDir } returns cacheDir

        documentRepository = DocumentRepository(
            context,
            api,
            cachedDocumentDao,
            pendingChangeDao,
            networkMonitor
        )
    }

    @Test
    fun `uploadDocument success returns task id`() = runTest {
        val uri = mockk<Uri>()
        val testContent = "fake image content".toByteArray()
        val expectedTaskId = "task-uuid-12345"

        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream(testContent)
        coEvery {
            api.uploadDocument(any(), any(), any(), any())
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
            api.uploadDocument(any(), any(), any(), any())
        } returns "\"quoted-task-id\"".toResponseBody()

        val result = documentRepository.uploadDocument(uri)

        assertTrue(result.isSuccess)
        assertEquals("quoted-task-id", result.getOrNull())
    }

    @Test
    fun `uploadDocument with tags sends comma separated ids`() = runTest {
        val uri = mockk<Uri>()
        val testContent = "image".toByteArray()

        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream(testContent)
        coEvery {
            api.uploadDocument(any(), any(), any(), any())
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
                documentType = any()
            )
        }
    }

    @Test
    fun `uploadDocument without optional params sends nulls`() = runTest {
        val uri = mockk<Uri>()
        val testContent = "image".toByteArray()

        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream(testContent)
        coEvery {
            api.uploadDocument(any(), null, null, null)
        } returns "task-id".toResponseBody()

        val result = documentRepository.uploadDocument(uri)

        assertTrue(result.isSuccess)
        coVerify {
            api.uploadDocument(
                document = any(),
                title = null,
                tags = null,
                documentType = null
            )
        }
    }

    @Test
    fun `uploadDocument with document type sends type id`() = runTest {
        val uri = mockk<Uri>()
        val testContent = "image".toByteArray()

        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream(testContent)
        coEvery {
            api.uploadDocument(any(), any(), any(), any())
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
            api.uploadDocument(any(), any(), any(), any())
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
        assertTrue(result.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `uploadDocument network error returns failure`() = runTest {
        val uri = mockk<Uri>()
        val testContent = "image".toByteArray()

        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream(testContent)
        coEvery {
            api.uploadDocument(any(), any(), any(), any())
        } throws IOException("Network unavailable")

        val result = documentRepository.uploadDocument(uri)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is IOException)
    }

    @Test
    fun `uploadDocument creates file with jpg extension`() = runTest {
        val uri = mockk<Uri>()
        val testContent = "image".toByteArray()
        val documentSlot = slot<MultipartBody.Part>()

        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream(testContent)
        coEvery {
            api.uploadDocument(capture(documentSlot), any(), any(), any())
        } returns "task-id".toResponseBody()

        documentRepository.uploadDocument(uri)

        val capturedPart = documentSlot.captured
        assertTrue(capturedPart.body.contentType()?.toString()?.contains("image/jpeg") == true)
    }
}
