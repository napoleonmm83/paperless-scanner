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
        // Use a still-cacheable reference endpoint (tags/correspondents/types
        // change slowly and don't drive optimistic UI). /api/documents and
        // /api/trash are now in the never-cache list because they reflect
        // mutable, user-driven state.
        val request = Request.Builder().url("https://paperless.example.com/api/tags/").build()
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
    fun `does not inject on tasks endpoint - real-time processing status`() {
        // /api/tasks/ is real-time document-processing status. Caching it for
        // 5 minutes hid freshly-uploaded documents' tasks from the
        // "In Verarbeitung" list until expiry. Must always go to the network.
        val request = Request.Builder().url("https://paperless.example.com/api/tasks/").build()
        val response = buildResponse(request)

        val result = interceptor.intercept(chainFor(request, response))

        assertNull(result.header("Cache-Control"))
    }

    @Test
    fun `does not inject on tasks endpoint with query param`() {
        val request = Request.Builder().url("https://paperless.example.com/api/tasks/?task_id=abc-123").build()
        val response = buildResponse(request)

        val result = interceptor.intercept(chainFor(request, response))

        assertNull(result.header("Cache-Control"))
    }

    @Test
    fun `does not inject on documents list endpoint - mutable via soft delete`() {
        // /api/documents/ reflects mutable state: a successful DELETE on the
        // server is invisible to the client until the 5-minute stale window
        // expires, so a subsequent fullSync re-inserts the just-deleted doc
        // and overwrites the local optimistic soft-delete. Must always go to
        // the network so list reads see the post-DELETE truth.
        val request = Request.Builder().url("https://paperless.example.com/api/documents/").build()
        val response = buildResponse(request)

        val result = interceptor.intercept(chainFor(request, response))

        assertNull(result.header("Cache-Control"))
    }

    @Test
    fun `does not inject on documents list endpoint with query params`() {
        val request = Request.Builder()
            .url("https://paperless.example.com/api/documents/?page=1&page_size=100&ordering=-created")
            .build()
        val response = buildResponse(request)

        val result = interceptor.intercept(chainFor(request, response))

        assertNull(result.header("Cache-Control"))
    }

    @Test
    fun `does not inject on single document endpoint`() {
        // /api/documents/{id}/ also reflects mutable state (title, tags,
        // metadata can change at any time). Caching it for 5 min hides edits
        // made on the web UI from the just-opened detail screen.
        val request = Request.Builder().url("https://paperless.example.com/api/documents/42/").build()
        val response = buildResponse(request)

        val result = interceptor.intercept(chainFor(request, response))

        assertNull(result.header("Cache-Control"))
    }

    @Test
    fun `does not inject on trash endpoint - mutable list`() {
        // /api/trash/ is the post-soft-delete view. Caching it for 5 min
        // hid documents the user had JUST moved to trash (the second of
        // two rapid swipes never appeared until the cache expired).
        val request = Request.Builder().url("https://paperless.example.com/api/trash/").build()
        val response = buildResponse(request)

        val result = interceptor.intercept(chainFor(request, response))

        assertNull(result.header("Cache-Control"))
    }

    @Test
    fun `does not inject on trash endpoint with query params`() {
        val request = Request.Builder()
            .url("https://paperless.example.com/api/trash/?page=1&page_size=100")
            .build()
        val response = buildResponse(request)

        val result = interceptor.intercept(chainFor(request, response))

        assertNull(result.header("Cache-Control"))
    }

    @Test
    fun `injects Cache-Control on document download subresource - immutable bytes`() {
        // Codex P2 regression guard (2026-05-28 review): the previous
        // `path.contains("/api/documents")` match also caught
        // /api/documents/{id}/download/, even though the bytes are immutable
        // per id and the app's docs explicitly call this endpoint cacheable.
        // Repeated PDF opens would re-download large files when the server
        // omits cache headers. Only the mutable list/detail routes must
        // bypass cache.
        val request = Request.Builder()
            .url("https://paperless.example.com/api/documents/42/download/")
            .build()
        val response = buildResponse(request)

        val result = interceptor.intercept(chainFor(request, response))

        assertEquals("public, max-age=300", result.header("Cache-Control"))
    }

    @Test
    fun `injects Cache-Control on document thumbnail subresource`() {
        // /api/documents/{id}/thumb/ — small image, content-addressed by id.
        // Must remain cacheable so the trash and document list don't refetch
        // thumbnails on every scroll.
        val request = Request.Builder()
            .url("https://paperless.example.com/api/documents/42/thumb/")
            .build()
        val response = buildResponse(request)

        val result = interceptor.intercept(chainFor(request, response))

        assertEquals("public, max-age=300", result.header("Cache-Control"))
    }

    @Test
    fun `injects Cache-Control on document preview subresource`() {
        val request = Request.Builder()
            .url("https://paperless.example.com/api/documents/42/preview/")
            .build()
        val response = buildResponse(request)

        val result = interceptor.intercept(chainFor(request, response))

        assertEquals("public, max-age=300", result.header("Cache-Control"))
    }

    @Test
    fun `injects Cache-Control on document suggestions subresource`() {
        // /api/documents/{id}/suggestions/ — ML-derived metadata, expensive
        // to compute server-side. Cacheable; not user-mutable state.
        val request = Request.Builder()
            .url("https://paperless.example.com/api/documents/42/suggestions/")
            .build()
        val response = buildResponse(request)

        val result = interceptor.intercept(chainFor(request, response))

        assertEquals("public, max-age=300", result.header("Cache-Control"))
    }

    @Test
    fun `MAX_AGE_SECONDS constant matches injected header`() {
        // Pin the constant so a future tweak doesn't silently drift the
        // header and the documented behavior out of sync.
        assertEquals(300, CacheControlInterceptor.MAX_AGE_SECONDS)
    }
}
