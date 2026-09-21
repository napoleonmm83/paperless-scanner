package com.paperless.scanner.data.repository

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.test.filters.SmallTest
import com.paperless.scanner.data.analytics.CrashlyticsHelper
import com.paperless.scanner.data.analytics.UploadMetricsTracker
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.domain.error.PaperlessException
import com.paperless.scanner.domain.error.ServerOfflineReason
import com.paperless.scanner.data.service.DocumentSerializer
import com.paperless.scanner.data.service.ImageProcessorService
import com.paperless.scanner.data.service.PdfGeneratorService
import com.paperless.scanner.util.SharedFileCache
import com.google.gson.Gson
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import okhttp3.MultipartBody
import okhttp3.ResponseBody.Companion.asResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.buffer
import okio.source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException

/**
 * Repository tests for [DocumentRepository].
 *
 * Marked `@SmallTest` because [DocumentRepository] no longer holds any
 * Room DAO after the Issue #51 refactor — it now depends on `cacheDir`,
 * `PaperlessApi`, `CrashlyticsHelper`, and three extracted service
 * collaborators (`ImageProcessorService`, `PdfGeneratorService`,
 * `DocumentSerializer`). Pure unit test scope per Issue #137.
 */
@SmallTest
@RunWith(RobolectricTestRunner::class)
class DocumentRepositoryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver
    private lateinit var api: PaperlessApi
    private lateinit var crashlyticsHelper: CrashlyticsHelper
    private lateinit var uploadMetricsTracker: UploadMetricsTracker
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
        uploadMetricsTracker = mockk(relaxed = true)
        cacheDir = tempFolder.newFolder("cache")

        every { context.contentResolver } returns contentResolver
        every { context.cacheDir } returns cacheDir

        imageProcessor = ImageProcessorService(context, crashlyticsHelper)
        pdfGenerator = PdfGeneratorService(context, imageProcessor)
        serializer = DocumentSerializer(Gson())

        documentRepository = DocumentRepository(
            cacheDir = cacheDir,
            api = api,
            uploadMetricsTracker = uploadMetricsTracker,
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

    @Test
    fun `downloadDocument re-throws CancellationException to preserve structured concurrency`() = runTest {
        // CancellationException MUST propagate out of DocumentRepository, not be wrapped in
        // Result.failure. Previously the catch(Exception) catch-all silently swallowed it,
        // breaking cooperative cancellation for upload/download coroutines.
        coEvery { api.downloadDocument(any()) } throws CancellationException("scope cancelled")

        var thrown: CancellationException? = null
        try {
            documentRepository.downloadDocument(123)
        } catch (e: CancellationException) {
            thrown = e
        }

        assertNotNull("CancellationException must propagate, not be wrapped in Result.failure", thrown)
        assertEquals("scope cancelled", thrown!!.message)
    }

    @Test
    fun `a DNS failure keeps its identity through the repository`() = runTest {
        // downloadDocument used to catch IOException ahead of the generic branch and map
        // it straight to NetworkError. Because UnknownHostException, ConnectException,
        // SocketTimeoutException, CleartextNotAllowlistedException,
        // CertificatePinMismatchException and the TLS trust exceptions are ALL
        // IOException subclasses, that single catch erased every distinction
        // PaperlessException.from() can make: a wrong certificate and a server that does
        // not resolve both reached the user as "check your internet connection".
        //
        // The catch is gone and from() does the work. This is the pin for that, and it
        // was missing: a cold review put the old catch back and the entire suite stayed
        // green, which is what absent coverage looks like from the inside.
        // PaperlessExceptionTest exercises from() directly but cannot see what this
        // repository does with it, and PdfViewerViewModelTest mocks the repository away.
        coEvery { api.downloadDocument(any()) } throws UnknownHostException("paperless.example")

        val error = documentRepository.downloadDocument(123).exceptionOrNull()

        assertTrue(
            "the typed failure was flattened back to NetworkError: $error",
            error is PaperlessException.ServerUnreachable
        )
        assertEquals(
            ServerOfflineReason.DNS_FAILURE,
            (error as PaperlessException.ServerUnreachable).reason
        )
    }

    @Test
    fun `a plain IO failure through the repository is still a network error`() = runTest {
        // The counterpart. Without it the test above would pass just as well if every
        // IOException had been rerouted somewhere new, and the ordinary case — the common
        // one — would have changed unnoticed.
        coEvery { api.downloadDocument(any()) } throws IOException("socket closed")

        val error = documentRepository.downloadDocument(123).exceptionOrNull()

        assertTrue("the ordinary IO case changed: $error", error is PaperlessException.NetworkError)
    }

    @Test
    fun `downloadDocument reads the response body off the calling thread`() = runTest {
        // The pin for the NetworkOnMainThreadException of user report a9e00ce0 (v1.5.245).
        //
        // Retrofit dispatches the suspend CALL off-thread by itself, so mocking the api
        // and asserting on the Result proves nothing about threads — every existing test
        // here passes with or without the fix. What was broken is the part Retrofit does
        // not touch: the ResponseBody arrives unread and byteStream() does the socket
        // reads in the CALLER's context. PdfViewerViewModel calls this from viewModelScope
        // (Dispatchers.Main), and Android throws on a socket read there.
        //
        // So the stream itself is the witness: it records which thread pulled bytes out of
        // it. Remove the withContext(Dispatchers.IO) in DocumentRepository and that thread
        // becomes this test's own thread, which is exactly the shape of the device bug.
        // The witness is the Thread OBJECT, not its name: coroutines-test appends
        // "@coroutine#id" to the name, so a later coroutineScope wrapper in the repository
        // could change the id and keep a name comparison green while the same thread reads.
        val content = ByteArray(32 * 1024) { 'x'.code.toByte() }
        val readThread = AtomicReference<Thread>()
        val recordingStream = object : ByteArrayInputStream(content) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                readThread.compareAndSet(null, Thread.currentThread())
                return super.read(b, off, len)
            }
        }
        coEvery { api.downloadDocument(any()) } returns
            recordingStream.source().buffer().asResponseBody(null, content.size.toLong())

        val callerThread = Thread.currentThread()
        val result = documentRepository.downloadDocument(123)

        assertTrue("download failed: ${result.exceptionOrNull()}", result.isSuccess)
        // Positive control: without this the assertion below passes just as well when the
        // body was never read at all, which is a different bug wearing the same green.
        assertNotNull("the response body was never read — the test proves nothing", readThread.get())
        assertNotEquals(
            "the response body was read on the calling thread (${callerThread.name}) — " +
                "on a device that is NetworkOnMainThreadException",
            callerThread,
            readThread.get()
        )
    }

    @Test
    fun `downloadDocument stops reading once the caller is cancelled`() = runTest {
        // The pin for the ensureActive() in the copy loop. That loop has no suspension
        // point, so withContext cannot break out of it and a blocking read on
        // Dispatchers.IO is never interrupted: without the check, leaving the PDF viewer
        // mid-download keeps the thread reading to EOF and leaves a fully written file
        // that no one owns. This state only became reachable WITH the IO dispatcher — the
        // old code's read died on the first byte — so the fix brought its own follow-up.
        //
        // The existing CancellationException test above cannot see this: it throws from
        // the api call, before the loop.
        val content = ByteArray(64 * 1024) { 'x'.code.toByte() }
        val reads = AtomicInteger()
        lateinit var job: Job
        val cancellingStream = object : ByteArrayInputStream(content) {
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                reads.incrementAndGet()
                job.cancel()
                return super.read(b, off, len)
            }
        }
        coEvery { api.downloadDocument(any()) } returns
            cancellingStream.source().buffer().asResponseBody(null, content.size.toLong())

        job = launch { documentRepository.downloadDocument(123) }
        job.join()

        assertTrue("the caller was not cancelled — the test set up nothing", job.isCancelled)
        // 64 KiB over 8 KiB segments is eight reads if the loop runs to completion. One or
        // two means it noticed. Drop the ensureActive() and this reads all eight.
        assertTrue(
            "the loop kept reading after cancellation: ${reads.get()} reads",
            reads.get() < 4
        )
        // Stopping the read is only half of it: outputStream() has already created the
        // file, so an abort leaves a zero-byte or half-written PDF that nobody learns
        // about — the Result is discarded, so lastDownload is never set and onCleared has
        // nothing to delete.
        assertEquals(
            "a partial download was left behind in the shared cache",
            emptyList<String>(),
            SharedFileCache.sharedPdfsDir(cacheDir).list()?.toList() ?: emptyList<String>()
        )
    }

    @Test
    fun `downloadDocument leaves no partial file behind when the stream dies`() = runTest {
        // The counterpart for the ordinary failure. Cancellation and a dropped connection
        // exit through different catches, and only one of them was covered.
        val dyingStream = object : ByteArrayInputStream(ByteArray(32 * 1024)) {
            override fun read(b: ByteArray, off: Int, len: Int): Int =
                throw IOException("connection reset")
        }
        coEvery { api.downloadDocument(any()) } returns
            dyingStream.source().buffer().asResponseBody(null, 32L * 1024)

        val result = documentRepository.downloadDocument(123)

        assertTrue("the failure was swallowed: $result", result.isFailure)
        assertEquals(
            "a partial download was left behind in the shared cache",
            emptyList<String>(),
            SharedFileCache.sharedPdfsDir(cacheDir).list()?.toList() ?: emptyList<String>()
        )
    }

    @Test
    fun `uploadDocument re-throws CancellationException to preserve structured concurrency`() = runTest {
        // CancellationException MUST propagate, not be wrapped in Result.failure.
        val uri = mockk<Uri>()
        val testContent = "fake image content".toByteArray()
        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream(testContent)
        coEvery { api.uploadDocument(any(), any(), any(), any(), any()) } throws CancellationException("scope cancelled")

        var thrown: CancellationException? = null
        try {
            documentRepository.uploadDocument(uri)
        } catch (e: CancellationException) {
            thrown = e
        }

        assertNotNull("CancellationException must propagate, not be wrapped in Result.failure", thrown)
        assertEquals("scope cancelled", thrown!!.message)
    }

    @Test
    fun `uploadMultiPageDocument re-throws CancellationException to preserve structured concurrency`() = runTest {
        // CancellationException MUST propagate, not be wrapped in Result.failure.
        // Use a mocked PdfGeneratorService so we can reach the API call and have it throw.
        val mockPdfGenerator = mockk<PdfGeneratorService>()
        val fakePdf = tempFolder.newFile("test.pdf").also { it.writeBytes(ByteArray(8)) }
        coEvery { mockPdfGenerator.createPdfFromImages(any()) } returns fakePdf
        coEvery { api.uploadDocument(any(), any(), any(), any(), any()) } throws CancellationException("scope cancelled")

        val repoWithMockedPdf = DocumentRepository(
            cacheDir = cacheDir,
            api = api,
            uploadMetricsTracker = uploadMetricsTracker,
            imageProcessor = imageProcessor,
            pdfGenerator = mockPdfGenerator,
            serializer = serializer,
        )

        var thrown: CancellationException? = null
        try {
            repoWithMockedPdf.uploadMultiPageDocument(listOf(mockk()))
        } catch (e: CancellationException) {
            thrown = e
        }

        assertNotNull("CancellationException must propagate, not be wrapped in Result.failure", thrown)
        assertEquals("scope cancelled", thrown!!.message)
    }

    @Test
    fun `uploadDocument logs start and success metrics via tracker`() = runTest {
        val uri = mockk<Uri>()
        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream("img".toByteArray())
        coEvery {
            api.uploadDocument(any(), any(), any(), any(), any())
        } returns "task-xyz".toResponseBody()

        documentRepository.uploadDocument(uri)

        verify { uploadMetricsTracker.logSinglePageUploadStart() }
        verify { uploadMetricsTracker.logSinglePageUploadSuccess("task-xyz") }
    }

    @Test
    fun `uploadDocument logs upload error via tracker on network failure`() = runTest {
        val uri = mockk<Uri>()
        every { contentResolver.openInputStream(uri) } returns ByteArrayInputStream("img".toByteArray())
        coEvery {
            api.uploadDocument(any(), any(), any(), any(), any())
        } throws IOException("Network unavailable")

        val result = documentRepository.uploadDocument(uri)

        assertTrue(result.isFailure)
        verify { uploadMetricsTracker.logSinglePageUploadStart() }
        verify { uploadMetricsTracker.logUploadError(any()) }
    }
}
