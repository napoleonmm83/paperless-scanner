package com.paperless.scanner.data.api

import android.util.Log
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
 * **The fallback:** API v9 only arrived in paperless-ngx 2.16.0. Older servers
 * reject the pin with `406 Not Acceptable`, so the first 406 for a host drops
 * that host back to the unversioned request — exactly the behaviour it had
 * before this interceptor existed. The decision is memoised per host, so a
 * legacy server costs one extra round trip once per process, not per request.
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
class ApiVersionInterceptor @Inject constructor() : Interceptor {

    /** Hosts that answered [HTTP_NOT_ACCEPTABLE] to the pin (paperless-ngx < 2.16). */
    private val hostsRejectingPin = ConcurrentHashMap.newKeySet<String>()

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host

        if (hostsRejectingPin.contains(host)) {
            return chain.proceed(request)
        }

        val pinned = request.newBuilder()
            .header("Accept", ACCEPT_HEADER_VALUE)
            .build()
        val response = chain.proceed(pinned)

        if (response.code != HTTP_NOT_ACCEPTABLE) {
            return response
        }

        // Server predates API v9. Discard the rejection and repeat the original,
        // unversioned request so the server falls back to its own default.
        response.close()
        if (hostsRejectingPin.add(host)) {
            Log.i(
                TAG,
                "Server rejected API v$PINNED_API_VERSION (406); falling back to the " +
                    "server default for this host (paperless-ngx < 2.16)."
            )
        }
        return chain.proceed(request)
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
    }
}
