package com.paperless.scanner.data.api

import android.util.Log
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class HttpAllowlistInterceptorTest {

    @Before
    fun setup() {
        // android.util.Log is a stub in JVM unit tests; mock it so the
        // interceptor's Log.w call does not throw UnsatisfiedLinkError.
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    /**
     * Tests mock [Interceptor.Chain] directly — that's a stable OkHttp public
     * boundary, not an internal collaborator. For the deny path we assert
     * the [IOException] before any `chain.proceed()` call (no socket open).
     * For allow paths the stubbed Response is returned unchanged, proving
     * the interceptor passes the request through.
     */

    private fun stubResponse(request: Request): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body("ok".toResponseBody(null))
        .build()

    private fun chainFor(request: Request, response: Response = stubResponse(request)): Interceptor.Chain {
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        every { chain.proceed(request) } returns response
        return chain
    }

    @Test
    fun `https request passes through without consulting allowlist`() {
        val holder = mockk<HttpAllowlistHolder>(relaxed = true)
        val interceptor = HttpAllowlistInterceptor(holder)
        val request = Request.Builder().url("https://example.com/api/").build()

        val response = interceptor.intercept(chainFor(request))

        assertEquals(200, response.code)
    }

    @Test
    fun `http to localhost passes without allowlist entry`() {
        val holder = mockk<HttpAllowlistHolder>()
        every { holder.snapshot() } returns emptySet()
        val interceptor = HttpAllowlistInterceptor(holder)
        val request = Request.Builder().url("http://localhost:8000/api/").build()

        val response = interceptor.intercept(chainFor(request))

        assertEquals(200, response.code)
    }

    @Test
    fun `http to 127_0_0_1 passes without allowlist entry`() {
        val holder = mockk<HttpAllowlistHolder>()
        every { holder.snapshot() } returns emptySet()
        val interceptor = HttpAllowlistInterceptor(holder)
        val request = Request.Builder().url("http://127.0.0.1:8000/api/").build()

        val response = interceptor.intercept(chainFor(request))

        assertEquals(200, response.code)
    }

    @Test
    fun `http to emulator host 10_0_2_2 passes without allowlist entry`() {
        val holder = mockk<HttpAllowlistHolder>()
        every { holder.snapshot() } returns emptySet()
        val interceptor = HttpAllowlistInterceptor(holder)
        val request = Request.Builder().url("http://10.0.2.2:8000/api/").build()

        val response = interceptor.intercept(chainFor(request))

        assertEquals(200, response.code)
    }

    @Test
    fun `http to IPv6 loopback passes without allowlist entry`() {
        val holder = mockk<HttpAllowlistHolder>()
        every { holder.snapshot() } returns emptySet()
        val interceptor = HttpAllowlistInterceptor(holder)
        val request = Request.Builder().url("http://[::1]:8000/api/").build()

        val response = interceptor.intercept(chainFor(request))

        assertEquals(200, response.code)
    }

    // Issue #222: edge cases — IPv6 with explicit port, 0.0.0.0, and IDN hosts.

    @Test
    fun `http to user-accepted IPv6 host with explicit port passes`() {
        val holder = mockk<HttpAllowlistHolder>()
        // OkHttp normalizes the bracketed authority to the bare host for url.host.
        every { holder.snapshot() } returns setOf("2001:db8::1")
        val interceptor = HttpAllowlistInterceptor(holder)
        val request = Request.Builder().url("http://[2001:db8::1]:8000/api/").build()

        val response = interceptor.intercept(chainFor(request))

        assertEquals(200, response.code)
    }

    @Test
    fun `http to 0_0_0_0 is denied by default`() {
        val holder = mockk<HttpAllowlistHolder>()
        every { holder.snapshot() } returns emptySet()
        val interceptor = HttpAllowlistInterceptor(holder)
        // 0.0.0.0 is not loopback and not user-accepted → must fail closed.
        val request = Request.Builder().url("http://0.0.0.0:8000/api/").build()
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request

        val thrown = assertThrows(CleartextNotAllowlistedException::class.java) {
            interceptor.intercept(chain)
        }
        assertEquals("0.0.0.0", thrown.host)
    }

    @Test
    fun `http to accepted IDN host passes when allowlist holds the ASCII form`() {
        // TokenManager.acceptHttpForHost normalizes Unicode hosts to Punycode
        // (issue #222), so the allowlist holds the ASCII form. OkHttp likewise
        // exposes the Punycode form via url.host for a Unicode request URL, so
        // the two match and the accepted host is NOT silently denied.
        val asciiHost = java.net.IDN.toASCII("päperless.lan")
        val holder = mockk<HttpAllowlistHolder>()
        every { holder.snapshot() } returns setOf(asciiHost)
        val interceptor = HttpAllowlistInterceptor(holder)
        val request = Request.Builder().url("http://päperless.lan/api/").build()

        val response = interceptor.intercept(chainFor(request))

        assertEquals(200, response.code)
    }

    @Test
    fun `http to user-accepted host passes`() {
        val holder = mockk<HttpAllowlistHolder>()
        every { holder.snapshot() } returns setOf("paperless.lan")
        val interceptor = HttpAllowlistInterceptor(holder)
        val request = Request.Builder().url("http://paperless.lan/api/").build()

        val response = interceptor.intercept(chainFor(request))

        assertEquals(200, response.code)
    }

    @Test
    fun `host comparison is case-insensitive`() {
        val holder = mockk<HttpAllowlistHolder>()
        every { holder.snapshot() } returns setOf("paperless.lan")
        val interceptor = HttpAllowlistInterceptor(holder)
        val request = Request.Builder().url("http://Paperless.LAN/api/").build()

        val response = interceptor.intercept(chainFor(request))

        assertEquals(200, response.code)
    }

    @Test
    fun `http to non-allowlisted host throws IOException`() {
        val holder = mockk<HttpAllowlistHolder>()
        every { holder.snapshot() } returns emptySet()
        val interceptor = HttpAllowlistInterceptor(holder)
        val request = Request.Builder().url("http://evil.example.com/api/").build()
        // Chain.proceed must NOT be called on the deny path; using a chain
        // that doesn't stub proceed would surface a MissingMockException if
        // the interceptor accidentally fell through.
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request

        val thrown = assertThrows(IOException::class.java) {
            interceptor.intercept(chain)
        }
        assertTrue(
            "Error message should name the host",
            thrown.message?.contains("evil.example.com") == true
        )
    }

    @Test
    fun `http to host similar to allowlist entry but different is denied`() {
        val holder = mockk<HttpAllowlistHolder>()
        every { holder.snapshot() } returns setOf("paperless.lan")
        val interceptor = HttpAllowlistInterceptor(holder)
        // Suffix match must NOT pass — strict equality only.
        val request = Request.Builder().url("http://attacker.paperless.lan.evil.com/").build()
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request

        assertThrows(IOException::class.java) {
            interceptor.intercept(chain)
        }
    }

    // Issue #233: typed exception so upstream can route to accept-dialog
    // instead of a generic network error.

    @Test
    fun `denied request throws CleartextNotAllowlistedException with host field`() {
        val holder = mockk<HttpAllowlistHolder>()
        every { holder.snapshot() } returns emptySet()
        val interceptor = HttpAllowlistInterceptor(holder)
        val request = Request.Builder().url("http://192.168.178.19:80/api/").build()
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request

        val thrown = assertThrows(CleartextNotAllowlistedException::class.java) {
            interceptor.intercept(chain)
        }
        assertEquals("192.168.178.19", thrown.host)
    }

    @Test
    fun `CleartextNotAllowlistedException is an IOException for backwards compat`() {
        val holder = mockk<HttpAllowlistHolder>()
        every { holder.snapshot() } returns emptySet()
        val interceptor = HttpAllowlistInterceptor(holder)
        val request = Request.Builder().url("http://blocked.example.com/").build()
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request

        // Existing catch(IOException) chains must keep matching the new
        // typed exception, otherwise OkHttp Call.execute()'s contract and
        // PaperlessException.from(...) both break.
        try {
            interceptor.intercept(chain)
            error("expected throw")
        } catch (e: IOException) {
            assertTrue("should be the typed subclass", e is CleartextNotAllowlistedException)
            assertEquals("blocked.example.com", (e as CleartextNotAllowlistedException).host)
        }
    }
}
