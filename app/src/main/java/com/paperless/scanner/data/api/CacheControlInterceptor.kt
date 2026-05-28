package com.paperless.scanner.data.api

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Network interceptor that enables OkHttp disk caching for safe, idempotent
 * Paperless-ngx read endpoints when the server omits explicit cache hints.
 *
 * Rationale: Paperless-ngx list endpoints (documents, tags, correspondents,
 * document types, tasks) do not consistently emit `Cache-Control` /
 * `ETag` / `Last-Modified`. Without server hints, OkHttp's [okhttp3.Cache]
 * stores nothing. This interceptor injects a conservative
 * `Cache-Control: public, max-age=300` on successful GET responses that lack
 * a `Cache-Control` header, giving the cache a 5-minute reuse window.
 *
 * Constraints (all must hold for injection):
 * - HTTP method is `GET` (mutations are never cached)
 * - Response is 2xx (`isSuccessful`)
 * - Response has no existing `Cache-Control` header (server wins if present)
 *
 * **Must be installed as a *network* interceptor** so the modified
 * `Cache-Control` is observed by OkHttp's cache layer when the response is
 * written to disk. Installing as an application interceptor would be a no-op
 * because the cache layer sits between the two.
 *
 * Cache is evicted on logout in [com.paperless.scanner.data.repository.AuthRepository.logout].
 */
class CacheControlInterceptor : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val response = chain.proceed(request)

        if (request.method != "GET") return response
        if (!response.isSuccessful) return response
        if (response.header("Cache-Control") != null) return response
        // Endpoints that reflect mutable, user-driven state must always go to
        // the network so write-through optimistic updates are not silently
        // reverted by stale cached list responses.
        //
        // * /api/tasks/      — real-time document-processing status. A 5-min
        //                      stale window hid freshly-uploaded tasks from
        //                      the "In Verarbeitung" list until expiry.
        // * /api/documents/  — mutable via soft-delete. A cached list still
        //                      including a just-deleted doc was upserted back
        //                      over the local `isDeleted=1` flag during the
        //                      next fullSync, so the doc reappeared on Home.
        //                      Also affects the per-id detail endpoint
        //                      (`/api/documents/{id}/`) — title/tags/etc.
        //                      edits from the web UI would stay invisible
        //                      for 5 min.
        // * /api/trash/      — the post-soft-delete view. Caching it hid the
        //                      second of two rapid swipe-to-trash actions
        //                      until the window expired.
        val path = request.url.encodedPath
        if (NEVER_CACHE_PATHS.any { it.matches(path) }) return response

        return response.newBuilder()
            .header("Cache-Control", "public, max-age=$MAX_AGE_SECONDS")
            .build()
    }

    companion object {
        const val MAX_AGE_SECONDS = 300

        // Anchored against the FULL request path (not `contains`) so that
        // document subresources like `/api/documents/{id}/download/`,
        // `/thumb/`, `/preview/`, and `/suggestions/` stay cacheable — their
        // bytes are immutable per id, and the app relies on the OkHttp cache
        // to avoid re-downloading PDFs on every open. Only the list and bare
        // detail routes hold mutable metadata that must bypass cache.
        private val NEVER_CACHE_PATHS = listOf(
            Regex("/api/tasks/"),
            Regex("/api/documents/"),
            Regex("/api/documents/\\d+/"),
            Regex("/api/trash/"),
        )
    }
}
