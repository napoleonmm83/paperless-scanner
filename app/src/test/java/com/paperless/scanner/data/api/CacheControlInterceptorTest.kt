package com.paperless.scanner.data.api

import io.mockk.every
import io.mockk.mockk
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Unit tests for [CacheControlInterceptor].
 *
 * Mocks the OkHttp [Interceptor.Chain] directly — that's a stable public API
 * boundary, not an internal collaborator. Pattern mirrors
 * [HttpAllowlistInterceptorTest].
 */
class CacheControlInterceptorTest {

    private val interceptor = CacheControlInterceptor()

    private fun buildResponse(
        request: Request,
        code: Int = 200,
        cacheControl: String? = null,
    ): Response {
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message(if (code in 200..299) "OK" else "Error")
            .body("body".toResponseBody(null))
        if (cacheControl != null) {
            builder.header("Cache-Control", cacheControl)
        }
        return builder.build()
    }

    private fun chainFor(request: Request, response: Response): Interceptor.Chain {
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns request
        every { chain.proceed(request) } returns response
        return chain
    }

    @Test
    fun `injects Cache-Control on successful GET without existing header`() {
        val request = Request.Builder().url("https://paperless.example.com/api/documents/").build()
        val response = buildResponse(request)

        val result = interceptor.intercept(chainFor(request, response))

        assertEquals("public, max-age=300", result.header("Cache-Control"))
    }

    @Test
    fun `respects existing Cache-Control header from server`() {
        // If Paperless-ngx (or a reverse proxy) sets its own policy, the
        // server wins. Never overwrite a present header.
        val request = Request.Builder().url("https://paperless.example.com/api/documents/").build()
        val response = buildResponse(request, cacheControl = "no-store")

        val result = interceptor.intercept(chainFor(request, response))

        assertEquals("no-store", result.header("Cache-Control"))
    }

    @Test
    fun `does not inject on POST`() {
        // Mutations must never be cached. Tagging post_document, login, etc.
        val request = Request.Builder()
            .url("https://paperless.example.com/api/documents/post_document/")
            .post("payload".toRequestBody(null))
            .build()
        val response = buildResponse(request)

        val result = interceptor.intercept(chainFor(request, response))

        assertNull(result.header("Cache-Control"))
    }

    @Test
    fun `does not inject on PATCH`() {
        val request = Request.Builder()
            .url("https://paperless.example.com/api/documents/1/")
            .patch("payload".toRequestBody(null))
            .build()
        val response = buildResponse(request)

        val result = interceptor.intercept(chainFor(request, response))

        assertNull(result.header("Cache-Control"))
    }

    @Test
    fun `does not inject on PUT`() {
        val request = Request.Builder()
            .url("https://paperless.example.com/api/documents/1/")
            .put("payload".toRequestBody(null))
            .build()
        val response = buildResponse(request)

        val result = interceptor.intercept(chainFor(request, response))

        assertNull(result.header("Cache-Control"))
    }

    @Test
    fun `does not inject on DELETE`() {
        val request = Request.Builder()
            .url("https://paperless.example.com/api/documents/1/")
            .delete()
            .build()
        val response = buildResponse(request)

        val result = interceptor.intercept(chainFor(request, response))

        assertNull(result.header("Cache-Control"))
    }

    @Test
    fun `does not inject on 4xx response`() {
        // 401/403/404 must not be cached even on GET — they reflect auth or
        // existence state that can flip at any time.
        val request = Request.Builder().url("https://paperless.example.com/api/documents/").build()
        val response = buildResponse(request, code = 401)

        val result = interceptor.intercept(chainFor(request, response))

        assertNull(result.header("Cache-Control"))
    }

    @Test
    fun `does not inject on 5xx response`() {
        val request = Request.Builder().url("https://paperless.example.com/api/documents/").build()
        val response = buildResponse(request, code = 503)

        val result = interceptor.intercept(chainFor(request, response))

        assertNull(result.header("Cache-Control"))
    }

    @Test
    fun `does not inject on 304 Not Modified`() {
        // 304 is not isSuccessful in OkHttp's classification (3xx range). The
        // upstream cache layer handles revalidation itself; we don't touch it.
        val request = Request.Builder().url("https://paperless.example.com/api/documents/").build()
        val response = buildResponse(request, code = 304)

        val result = interceptor.intercept(chainFor(request, response))

        assertNull(result.header("Cache-Control"))
    }

    @Test
    fun `MAX_AGE_SECONDS constant matches injected header`() {
        // Pin the constant so a future tweak doesn't silently drift the
        // header and the documented behavior out of sync.
        assertEquals(300, CacheControlInterceptor.MAX_AGE_SECONDS)
    }
}
