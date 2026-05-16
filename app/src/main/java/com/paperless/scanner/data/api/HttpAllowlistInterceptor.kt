package com.paperless.scanner.data.api

import android.util.Log
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thrown by [HttpAllowlistInterceptor] when a cleartext-HTTP request targets
 * a host that is neither in the loopback hard-allowlist nor in the
 * user-accepted set. Subclass of [IOException] so existing catch-IOException
 * chains keep matching, but typed so upstream layers (repository, ViewModel)
 * can distinguish "interceptor blocked, prompt the user to accept" from
 * "real network failure". Issue #233.
 *
 * @param host The lowercased host that was blocked. UI layers use this to
 *             populate the accept-dialog.
 */
class CleartextNotAllowlistedException(val host: String) : IOException(
    "Cleartext HTTP not permitted for non-allowlisted host: $host. " +
        "Accept the insecure-connection warning in app settings to allow this host."
)

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

        throw CleartextNotAllowlistedException(host)
    }

    companion object {
        private const val TAG = "HttpAllowlist"

        /**
         * Loopback hosts never leave the device, so they're hard-allowed
         * without requiring the user to accept the in-app warning.
         * 10.0.2.2 is the Android emulator's host-machine loopback alias.
         *
         * Visibility is `internal` so LoginViewModel can mirror this set when
         * deciding whether to prompt the cleartext-accept dialog pre-detection
         * (Issue #233). Source of truth lives here.
         */
        internal val HARD_ALLOWED_HOSTS: Set<String> = setOf(
            "localhost",
            "127.0.0.1",
            "::1",
            "10.0.2.2",
        )
    }
}
