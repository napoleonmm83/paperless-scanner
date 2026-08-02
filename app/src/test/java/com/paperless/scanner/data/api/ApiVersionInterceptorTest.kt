package com.paperless.scanner.data.api

import android.util.Log
import com.paperless.scanner.data.analytics.CrashlyticsHelperContract
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [ApiVersionInterceptor].
 *
 * Mocks the OkHttp [Interceptor.Chain] directly, mirroring
 * [CacheControlInterceptorTest]. Each test builds a fresh interceptor because
 * the "this server rejects the pin" decision is deliberately remembered for the
 * lifetime of the instance.
 */
class ApiVersionInterceptorTest {

    private val tasksUrl = "https://paperless.example.com/api/tasks/"

    private lateinit var crashlytics: CrashlyticsHelperContract

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any<String>()) } returns 0
        crashlytics = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun interceptor() = ApiVersionInterceptor(crashlytics)

    /** Tracks whether the interceptor released the response it discarded. */
    private class CountingBody(private val delegate: ResponseBody) : ResponseBody() {
        var closed = false
            private set

        override fun contentLength() = delegate.contentLength()
        override fun contentType() = delegate.contentType()
        override fun source() = delegate.source()
        override fun close() {
            closed = true
            delegate.close()
        }
    }

    private val bodies = mutableListOf<CountingBody>()

    private fun buildResponse(request: Request, code: Int): Response {
        val body = CountingBody("[]".toResponseBody(null)).also { bodies += it }
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Not Acceptable")
            .body(body)
            .build()
    }

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

    private fun get(url: String) = Request.Builder().url(url).build()

    private fun post(url: String) = Request.Builder()
        .url(url)
        .post("{}".toRequestBody("application/json".toMediaType()))
        .build()

    @Test
    fun `pins the negotiated API version on the outgoing request`() {
        // Without this header the server picks its own newest version — 2.16 serves
        // v9 but 3.0 serves v10, whose paginated /api/tasks/ the DTOs don't match.
        val seen = mutableListOf<Request>()

        interceptor().intercept(chainFor(get(tasksUrl), seen) { 200 })

        assertEquals(1, seen.size)
        assertEquals("application/json; version=9", seen[0].header("Accept"))
    }

    @Test
    fun `never pins a request that carries a body`() {
        // THE upload-duplication guard: the 406 path replays the request, and
        // replaying a POST duplicates whatever it created. Uploads and logins must
        // therefore never carry the pin in the first place.
        val seen = mutableListOf<Request>()

        interceptor().intercept(
            chainFor(post("https://paperless.example.com/api/documents/post_document/"), seen) { 200 }
        )

        assertEquals(1, seen.size)
        assertNull(seen[0].header("Accept"))
    }

    @Test
    fun `never pins the login endpoint so pre-2_16 servers stay usable`() {
        // Login runs through this same client. Pinning it would 406 on < 2.16 and,
        // because POSTs are never replayed, would lock those users out entirely.
        val seen = mutableListOf<Request>()

        interceptor().intercept(
            chainFor(post("https://paperless.example.com/api/token/"), seen) { 200 }
        )

        assertNull(seen[0].header("Accept"))
    }

    @Test
    fun `never pins binary document downloads`() {
        // A file download has no versioned JSON contract, so asking for
        // application/json there is wrong for no benefit.
        val seen = mutableListOf<Request>()

        interceptor().intercept(
            chainFor(get("https://paperless.example.com/api/documents/5/download/"), seen) { 200 }
        )

        assertEquals(1, seen.size)
        assertNull(seen[0].header("Accept"))
    }

    @Test
    fun `falls back to the unversioned request when the server rejects the pin`() {
        // paperless-ngx < 2.16 has no API v9 and answers 406. Those servers must
        // keep working exactly as they did before the pin existed.
        val seen = mutableListOf<Request>()

        val result = interceptor().intercept(
            chainFor(get(tasksUrl), seen) { attempt -> if (attempt == 1) 406 else 200 }
        )

        assertEquals(200, result.code)
        assertEquals(2, seen.size)
        assertEquals("application/json; version=9", seen[0].header("Accept"))
        // THE contract: the retry carries no pinned version, so the server falls
        // back to its own default instead of refusing the request.
        assertNull(seen[1].header("Accept"))
    }

    @Test
    fun `releases the rejected response before retrying`() {
        // Without the close() the connection leaks once per legacy-server probe.
        val seen = mutableListOf<Request>()

        interceptor().intercept(
            chainFor(get(tasksUrl), seen) { attempt -> if (attempt == 1) 406 else 200 }
        )

        assertTrue("the discarded 406 body must be closed", bodies[0].closed)
    }

    @Test
    fun `remembers the rejection so later requests skip the pin entirely`() {
        // The probe costs one extra round trip per server per process, not per request.
        val interceptor = interceptor()
        val seen = mutableListOf<Request>()

        interceptor.intercept(chainFor(get(tasksUrl), seen) { attempt -> if (attempt == 1) 406 else 200 })
        assertEquals(2, seen.size)

        val later = mutableListOf<Request>()
        val result = interceptor.intercept(chainFor(get(tasksUrl), later) { 200 })

        assertEquals(200, result.code)
        assertEquals(1, later.size)
        assertNull(later[0].header("Accept"))
    }

    @Test
    fun `does not un-pin the server when the unversioned retry also fails`() {
        // 406 is a generic content-negotiation failure. A WAF or captive portal that
        // 406s everything must NOT permanently drop a healthy 3.0 server back onto
        // the moving server default — that is the very bug this class prevents.
        val interceptor = interceptor()
        val first = mutableListOf<Request>()

        interceptor.intercept(chainFor(get(tasksUrl), first) { 406 })
        assertEquals(2, first.size)

        // Server recovered; the pin must still be applied.
        val later = mutableListOf<Request>()
        interceptor.intercept(chainFor(get(tasksUrl), later) { 200 })

        assertEquals("application/json; version=9", later[0].header("Accept"))
        verify(exactly = 0) { crashlytics.logStateBreadcrumb("API_VERSION_FALLBACK", any()) }
    }

    @Test
    fun `reports the permanent fallback to Crashlytics`() {
        // A silent, permanent downgrade of the API contract needs a trace that
        // leaves the device, or the next bug report is undiagnosable.
        val seen = mutableListOf<Request>()

        interceptor().intercept(
            chainFor(get(tasksUrl), seen) { attempt -> if (attempt == 1) 406 else 200 }
        )

        verify(exactly = 1) {
            crashlytics.logStateBreadcrumb("API_VERSION_FALLBACK", "paperless.example.com:443")
        }
    }

    @Test
    fun `keeps pinning other servers after one rejected the version`() {
        // The fallback is a property of one server, never of the whole app — a user
        // with a legacy server plus a 3.0 server must get correct behaviour on both.
        val interceptor = interceptor()
        val legacySeen = mutableListOf<Request>()
        interceptor.intercept(
            chainFor(get("https://old.example.com/api/tasks/"), legacySeen) { a -> if (a == 1) 406 else 200 }
        )
        assertNull(legacySeen[1].header("Accept"))

        val modernSeen = mutableListOf<Request>()
        interceptor.intercept(chainFor(get("https://new.example.com/api/tasks/"), modernSeen) { 200 })

        assertEquals(1, modernSeen.size)
        assertEquals("application/json; version=9", modernSeen[0].header("Accept"))
    }

    @Test
    fun `distinguishes two instances on the same host by port`() {
        // A self-hoster migrating between instances runs legacy and 3.0 behind one
        // hostname on different ports; a host-only key would downgrade both.
        val interceptor = interceptor()
        val legacySeen = mutableListOf<Request>()
        interceptor.intercept(
            chainFor(get("https://paperless.lan:8000/api/tasks/"), legacySeen) { a -> if (a == 1) 406 else 200 }
        )
        assertNull(legacySeen[1].header("Accept"))

        val modernSeen = mutableListOf<Request>()
        interceptor.intercept(chainFor(get("https://paperless.lan:8001/api/tasks/"), modernSeen) { 200 })

        assertEquals("application/json; version=9", modernSeen[0].header("Accept"))
    }

    @Test
    fun `leaves non-406 error responses untouched`() {
        // A 401 or 500 must surface to the caller unchanged; only 406 means
        // "this server does not speak that API version".
        val seen = mutableListOf<Request>()

        val result = interceptor().intercept(chainFor(get(tasksUrl), seen) { 401 })

        assertEquals(401, result.code)
        assertEquals(1, seen.size)
    }
}
