package com.paperless.scanner.data.service

import android.content.Context
import androidx.test.filters.SmallTest
import com.paperless.scanner.data.api.PaperlessException
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for [ProtocolDetector], the raw-OkHttp HTTP/HTTPS + Paperless-server
 * detection probe extracted out of [com.paperless.scanner.data.repository.AuthRepository]
 * (Issue #48).
 *
 * Marked `@SmallTest`; Robolectric is required for `Context` (localized error
 * strings). Mirrors the MockWebServer + relaxed-mock-Context style of
 * [com.paperless.scanner.data.repository.AuthRepositoryTest]. The HTTPS-first /
 * HTTP-fallback orchestration and typed CleartextBlocked/CertPinMismatch
 * propagation are covered there via `detectServerProtocol`; this suite pins the
 * extracted unit's own `/api/` verification and `/api/documents/` fallback paths.
 */
@SmallTest
@RunWith(RobolectricTestRunner::class)
class ProtocolDetectorTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var context: Context
    private lateinit var client: OkHttpClient
    private lateinit var detector: ProtocolDetector

    @Before
    fun setup() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        // Relaxed mock returns "" for any getString — detection branches are
        // asserted by typed result + Result success/failure, not message text.
        context = mockk(relaxed = true)
        client = OkHttpClient.Builder().build()
        detector = ProtocolDetector(context, client)
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    private fun host() = "${mockWebServer.hostName}:${mockWebServer.port}"

    @Test
    fun `tryProtocol succeeds when api root is a paperless server`() = runTest {
        // /api/ 200 with >=3 Paperless endpoint markers → verified immediately.
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"documents":"/api/documents/","tags":"/api/tags/",""" +
                        """"correspondents":"/api/correspondents/",""" +
                        """"document_types":"/api/document_types/",""" +
                        """"saved_views":"/api/saved_views/"}"""
                )
        )

        val result = detector.tryProtocol("http", host())

        assertTrue("Paperless /api/ root must verify", result.isSuccess)
        assertEquals("http://${host()}", result.getOrNull())
    }

    @Test
    fun `tryProtocol returns not-paperless error when api root is 404`() = runTest {
        mockWebServer.enqueue(MockResponse().setResponseCode(404))

        val result = detector.tryProtocol("http", host())

        assertTrue(result.isFailure)
        assertTrue(
            "A 404 /api/ root is not Paperless",
            result.exceptionOrNull() is PaperlessException.NetworkError
        )
    }

    @Test
    fun `tryProtocol verifies via documents endpoint when api root requires auth`() = runTest {
        // /api/ 401 → secondary /api/documents/ probe; 401 there is the expected
        // auth-protected-Paperless signal → success.
        mockWebServer.enqueue(MockResponse().setResponseCode(401))
        mockWebServer.enqueue(MockResponse().setResponseCode(401))

        val result = detector.tryProtocol("http", host())

        assertTrue("401 at both endpoints means auth-protected Paperless", result.isSuccess)
        assertEquals("http://${host()}", result.getOrNull())
        assertEquals(
            "Should probe /api/ then fall through to /api/documents/",
            2, mockWebServer.requestCount
        )
    }

    @Test
    fun `tryProtocol falls through to documents endpoint when api root is generic 200`() = runTest {
        // /api/ 200 but NOT Paperless-shaped → secondary /api/documents/ check;
        // a results/count payload confirms Paperless → success.
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"hello":"world"}"""))
        mockWebServer.enqueue(
            MockResponse().setResponseCode(200).setBody("""{"count":0,"results":[]}""")
        )

        val result = detector.tryProtocol("http", host())

        assertTrue("documents endpoint with results array confirms Paperless", result.isSuccess)
        assertEquals("http://${host()}", result.getOrNull())
    }

    @Test
    fun `tryProtocol fails when documents endpoint is 404`() = runTest {
        // /api/ 200 generic, /api/documents/ 404 → not Paperless.
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("""{"hello":"world"}"""))
        mockWebServer.enqueue(MockResponse().setResponseCode(404))

        val result = detector.tryProtocol("http", host())

        assertTrue(result.isFailure)
        assertTrue(
            "A 404 /api/documents/ means not Paperless",
            result.exceptionOrNull() is PaperlessException.NetworkError
        )
    }
}
