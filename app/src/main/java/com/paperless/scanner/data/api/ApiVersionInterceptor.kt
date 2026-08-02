package com.paperless.scanner.data.api

import android.util.Log
import com.paperless.scanner.data.analytics.CrashlyticsHelperContract
import okhttp3.Interceptor
import okhttp3.Response
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pins the Paperless-ngx REST API to a known version instead of riding the
 * server's moving default.
 *
 * **Why this exists:** Paperless-ngx negotiates the API version through the
 * `Accept` header. A client that sends no version gets `DEFAULT_VERSION`, and
 * that default always points at the server's *newest* version — 2.14 served
 * `7`, 2.16 served `9`, and 3.0 serves `10`. So an unversioned client silently
 * changes contract every time the user upgrades their server.
 *
 * v10 is where that stopped being harmless: `/api/tasks/` became paginated and
 * the task field `type` was renamed to `trigger_source`, which would have
 * broken document-processing status on every 3.0 server.
 *
 * **The pin:** [PINNED_API_VERSION] is the newest version whose response shapes
 * the DTOs in `data.api.models` model directly. Paperless-ngx 3.0 still serves
 * it (`ALLOWED_VERSIONS = ["9", "10"]`), so 2.16+ and 3.x behave identically.
 *
 * **Safe methods only.** The pin is applied ONLY to GET/HEAD. That is not
 * conservatism, it is a correctness requirement: the 406 fallback below replays
 * the request, and replaying a POST duplicates whatever it created. A proxy or
 * WAF can return 406 *after* the origin already accepted an upload, and
 * `ProgressRequestBody` is file-backed and therefore replayable — so the replay
 * would succeed and produce a duplicate document. `NetworkRetry.withRetry`
 * already forbids retrying `create*`/`addNote`-style POSTs for exactly this
 * reason (PR #196); this interceptor must not reintroduce the hazard one layer
 * lower, where the repository-level guard cannot see it. Leaving unsafe methods
 * unpinned also keeps `POST /api/token/` working on pre-2.16 servers — login
 * runs through this same client, so pinning it and refusing to replay it would
 * lock those users out entirely.
 *
 * Nothing is lost by that restriction: every response whose JSON shape differs
 * between v9 and v10 is fetched with a GET.
 *
 * **Binary downloads are excluded** ([UNPINNED_PATHS]). A file download has no
 * versioned JSON contract, so pinning it is pure downside — and PDF download is
 * currently under investigation for an unexplained production failure (PR #403).
 *
 * **The fallback:** API v9 only arrived in paperless-ngx 2.16.0. Older servers
 * reject the pin with `406 Not Acceptable`, so the request is repeated without
 * the header — exactly the behaviour it had before this interceptor existed.
 * The host is remembered ONLY once the unversioned retry actually succeeds:
 * `406` is a generic content-negotiation failure, and a WAF or proxy that
 * answers 406 for unrelated reasons must not permanently drop a healthy 3.0
 * server back onto the moving default. Because the retry is limited to safe
 * methods, repeating it has no side effects.
 *
 * That fallback is also the escape hatch for the *future*: when paperless-ngx
 * eventually drops v9 (the deprecation policy guarantees it for at least a year
 * after v10), the pin starts 406-ing, this interceptor falls back to the server
 * default, and [com.paperless.scanner.data.api.models.TasksResponseDeserializer]
 * already understands the v10 shape. The app keeps working without a release.
 *
 * **Placement:** must run AFTER the token interceptor. The request captured
 * here is already token-decorated, so the 406 retry re-sends the credentials
 * without recomputing them.
 *
 * @see com.paperless.scanner.data.api.models.TasksResponseDeserializer for the
 *   matching tolerance on the response side.
 */
@Singleton
class ApiVersionInterceptor @Inject constructor(
    private val crashlyticsHelper: CrashlyticsHelperContract,
) : Interceptor {

    /**
     * Servers that answered [HTTP_NOT_ACCEPTABLE] to the pin and then served the
     * unversioned request (paperless-ngx < 2.16).
     *
     * Keyed on host **and port**: a self-hoster can run a legacy instance and a
     * 3.0 instance behind one hostname on different ports, and a host-only key
     * would let the legacy one downgrade the modern one.
     */
    private val serversRejectingPin = ConcurrentHashMap.newKeySet<String>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()

        if (!shouldPin(request.method, request.url.encodedPath)) {
            return chain.proceed(request)
        }

        val serverKey = "${request.url.host}:${request.url.port}"
        if (serversRejectingPin.contains(serverKey)) {
            return chain.proceed(request)
        }

        val pinned = request.newBuilder()
            .header("Accept", ACCEPT_HEADER_VALUE)
            .build()
        val response = chain.proceed(pinned)

        if (response.code != HTTP_NOT_ACCEPTABLE) {
            return response
        }

        // Safe method, so repeating it cannot have side effects. Discard the
        // rejection and ask again without the version.
        response.close()
        val retried = chain.proceed(request)

        // Only a 406 that dropping the header actually cured is evidence of a
        // pre-2.16 server. If the retry 406s too, the cause was something else
        // (WAF, proxy, captive portal) and this server keeps its pin.
        if (retried.code != HTTP_NOT_ACCEPTABLE && serversRejectingPin.add(serverKey)) {
            Log.i(
                TAG,
                "Server rejected API v$PINNED_API_VERSION (406) but served the " +
                    "unversioned request; using the server default from now on " +
                    "(paperless-ngx < 2.16)."
            )
            // A permanent, silent downgrade of the app's API contract needs a
            // trace that leaves the device — otherwise a later "processing
            // status is empty again" report is undiagnosable.
            crashlyticsHelper.logStateBreadcrumb("API_VERSION_FALLBACK", serverKey)
        }
        return retried
    }

    private fun shouldPin(method: String, encodedPath: String): Boolean {
        if (method != "GET" && method != "HEAD") return false
        return UNPINNED_PATHS.none { it.containsMatchIn(encodedPath) }
    }

    companion object {
        /**
         * Highest API version the DTOs in `data.api.models` are written against.
         * Supported by paperless-ngx 2.16.0 through 3.x.
         */
        const val PINNED_API_VERSION = 9

        /** Exact `Accept` header paperless-ngx parses the version out of. */
        const val ACCEPT_HEADER_VALUE = "application/json; version=$PINNED_API_VERSION"

        private const val HTTP_NOT_ACCEPTABLE = 406
        private const val TAG = "ApiVersion"

        /**
         * Binary/streaming subresources. Their bytes carry no versioned JSON
         * contract, so requesting `application/json` there would be semantically
         * wrong for no benefit. Mirrors the path-matching approach in
         * [CacheControlInterceptor].
         */
        private val UNPINNED_PATHS = listOf(
            Regex("/api/documents/\\d+/download/"),
            Regex("/api/documents/\\d+/preview/"),
            Regex("/api/documents/\\d+/thumb/"),
        )
    }
}
