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
        // Never cache the tasks endpoint: it is real-time document-processing
        // status. A 5-minute stale window hid freshly-uploaded tasks from the
        // "In Verarbeitung" list until expiry (regression from this interceptor).
        if (request.url.encodedPath.contains(TASKS_PATH)) return response

        return response.newBuilder()
            .header("Cache-Control", "public, max-age=$MAX_AGE_SECONDS")
            .build()
    }

    companion object {
        const val MAX_AGE_SECONDS = 300
        private const val TASKS_PATH = "/api/tasks"
    }
}
