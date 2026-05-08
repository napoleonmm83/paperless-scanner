package com.paperless.scanner.data.api

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fail-closed runtime enforcement of the user's cleartext-HTTP allowlist.
 *
 * The base `network_security_config.xml` permits cleartext to all domains
 * because Android's NetworkSecurityConfig cannot express a runtime,
 * user-managed allowlist (only static hostnames or IP literals — no CIDR,
 * no programmatic add). This interceptor is the runtime gate that satisfies
 * the F-004 / Issue #31 acceptance criterion "HTTP requests to non-allowlisted
 * hosts fail closed":
 *
 * - HTTPS requests pass through unchanged.
 * - Cleartext requests to loopback hosts ([HARD_ALLOWED_HOSTS]) pass through —
 *   they never leave the device.
 * - Cleartext requests to hosts the user explicitly accepted via the in-app
 *   warning dialog ([HttpAllowlistHolder]) pass through, with a single WARN
 *   log (host only — no path, no query).
 * - All other cleartext requests fail with [IOException] before
 *   `chain.proceed()` — no socket open, no auth-token leak, no DNS lookup
 *   beyond what OkHttp may have done while building the request.
 *
 * **Ordering requirement:** this interceptor MUST be installed before the
 * auth-token interceptor in every OkHttp client, otherwise a misconfigured
 * request to a malicious host could leak the bearer token in the failed
 * connection attempt. See `AppModule` for wiring.
 */
@Singleton
class HttpAllowlistInterceptor @Inject constructor(
    private val holder: HttpAllowlistHolder,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val url = request.url

        if (url.isHttps) {
            return chain.proceed(request)
        }

        val host = url.host.lowercase()

        if (host in HARD_ALLOWED_HOSTS) {
            return chain.proceed(request)
        }

        if (host in holder.snapshot()) {
            Log.w(TAG, "Cleartext HTTP allowed for accepted host: $host")
            return chain.proceed(request)
        }

        throw IOException(
            "Cleartext HTTP not permitted for non-allowlisted host: $host. " +
                "Accept the insecure-connection warning in app settings to allow this host."
        )
    }

    companion object {
        private const val TAG = "HttpAllowlist"

        // Loopback hosts never leave the device, so they're hard-allowed
        // without requiring the user to accept the in-app warning.
        // 10.0.2.2 is the Android emulator's host-machine loopback alias.
        private val HARD_ALLOWED_HOSTS: Set<String> = setOf(
            "localhost",
            "127.0.0.1",
            "10.0.2.2",
        )
    }
}
