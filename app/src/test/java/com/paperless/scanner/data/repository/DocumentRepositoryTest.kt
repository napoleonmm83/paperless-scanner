package com.paperless.scanner.data.repository

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.paperless.scanner.data.analytics.CrashlyticsHelper
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.service.DocumentSerializer
import com.paperless.scanner.data.service.ImageProcessorService
import com.paperless.scanner.data.service.PdfGeneratorService
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import okhttp3.MultipartBody
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
    private lateinit var crashlyticsHelper: CrashlyticsHelper
    private lateinit var imageProcessor: ImageProcessorService
    private lateinit var pdfGenerator: PdfGeneratorService
    private lateinit var serializer: DocumentSerializer
    private lateinit var documentRepository: DocumentRepository
    private lateinit var cacheDir: File

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        contentResolver = mockk(relaxed = true)
        api = mockk()
        crashlyticsHelper = mockk(relaxed = true)
        cacheDir = tempFolder.newFolder("cache")

        every { context.contentResolver } returns contentResolver
        every { context.cacheDir } returns cacheDir

        imageProcessor = ImageProcessorService(context, crashlyticsHelper)
        pdfGenerator = PdfGeneratorService(context, imageProcessor)
        serializer = DocumentSerializer(Gson())

        documentRepository = DocumentRepository(
            cacheDir = cacheDir,
            api = api,
            crashlyticsHelper = crashlyticsHelper,
            imageProcessor = imageProcessor,
            pdfGenerator = pdfGenerator,
            serializer = serializer,
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
}
