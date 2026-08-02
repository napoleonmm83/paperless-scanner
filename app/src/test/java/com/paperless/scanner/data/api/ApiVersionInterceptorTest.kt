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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ApiVersionInterceptor].
 *
 * Mocks the OkHttp [Interceptor.Chain] directly, mirroring
 * [CacheControlInterceptorTest]. Each test builds a fresh interceptor because
 * the "this host rejects the pin" decision is deliberately remembered for the
 * lifetime of the instance.
 */
class ApiVersionInterceptorTest {

    private val apiUrl = "https://paperless.example.com/api/tasks/"

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun buildResponse(request: Request, code: Int): Response =
        Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Not Acceptable")
            .body("[]".toResponseBody(null))
            .build()

    /**
     * Chain that records every request it is asked to proceed with, so a test can
     * assert on the retry as well as the first attempt.
     */
    private fun chainFor(
        request: Request,
        seen: MutableList<Request>,
        codeFor: (attempt: Int) -> Int,
    ): Interceptor.Chain {
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        every { chain.proceed(any()) } answers {
            val proceeded = firstArg<Request>()
            seen += proceeded
            buildResponse(proceeded, codeFor(seen.size))
        }
        return chain
    }

    @Test
    fun `pins the negotiated API version on the outgoing request`() {
        // Without this header the server picks its own newest version — 2.16 serves
        // v9 but 3.0 serves v10, whose paginated /api/tasks/ the DTOs don't match.
        val interceptor = ApiVersionInterceptor()
        val request = Request.Builder().url(apiUrl).build()
        val seen = mutableListOf<Request>()

        interceptor.intercept(chainFor(request, seen) { 200 })

        assertEquals(1, seen.size)
        assertEquals("application/json; version=9", seen[0].header("Accept"))
    }

    @Test
    fun `falls back to the unversioned request when the server rejects the pin`() {
        // paperless-ngx < 2.16 has no API v9 and answers 406. Those servers must
        // keep working exactly as they did before the pin existed.
        val interceptor = ApiVersionInterceptor()
        val request = Request.Builder().url(apiUrl).build()
        val seen = mutableListOf<Request>()

        val result = interceptor.intercept(
            chainFor(request, seen) { attempt -> if (attempt == 1) 406 else 200 }
        )

        assertEquals(200, result.code)
        assertEquals(2, seen.size)
        assertEquals("application/json; version=9", seen[0].header("Accept"))
        // THE contract: the retry carries no pinned version, so the server falls
        // back to its own default instead of refusing the request.
        assertNull(seen[1].header("Accept"))
    }

    @Test
    fun `remembers the rejection so later requests skip the pin entirely`() {
        // The probe costs one extra round trip per host per process, not per request.
        val interceptor = ApiVersionInterceptor()
        val request = Request.Builder().url(apiUrl).build()
        val seen = mutableListOf<Request>()

        // First call: pinned attempt 406s, unversioned retry succeeds (2 requests).
        interceptor.intercept(chainFor(request, seen) { attempt -> if (attempt == 1) 406 else 200 })
        assertEquals(2, seen.size)

        // Second call on the same host: straight to unversioned, no second probe.
        val later = mutableListOf<Request>()
        val result = interceptor.intercept(chainFor(request, later) { 200 })

        assertEquals(200, result.code)
        assertEquals(1, later.size)
        assertNull(later[0].header("Accept"))
    }

    @Test
    fun `keeps pinning other hosts after one host rejected the version`() {
        // The fallback is a property of one server, never of the whole app — a user
        // with a legacy server plus a 3.0 server must get correct behaviour on both.
        val interceptor = ApiVersionInterceptor()
        val legacy = Request.Builder().url("https://old.example.com/api/tasks/").build()
        val modern = Request.Builder().url("https://new.example.com/api/tasks/").build()

        val legacySeen = mutableListOf<Request>()
        interceptor.intercept(chainFor(legacy, legacySeen) { attempt -> if (attempt == 1) 406 else 200 })
        assertNull(legacySeen[1].header("Accept"))

        val modernSeen = mutableListOf<Request>()
        interceptor.intercept(chainFor(modern, modernSeen) { 200 })

        assertEquals(1, modernSeen.size)
        assertEquals("application/json; version=9", modernSeen[0].header("Accept"))
    }

    @Test
    fun `leaves non-406 error responses untouched`() {
        // A 401 or 500 must surface to the caller unchanged; only 406 means
        // "this server does not speak that API version".
        val interceptor = ApiVersionInterceptor()
        val request = Request.Builder().url(apiUrl).build()
        val seen = mutableListOf<Request>()

        val result = interceptor.intercept(chainFor(request, seen) { 401 })

        assertEquals(401, result.code)
        assertEquals(1, seen.size)
    }
}
