package com.paperless.scanner.data.service

import android.content.Context
import android.util.Log
import com.paperless.scanner.R
import com.paperless.scanner.domain.error.PaperlessException
import com.paperless.scanner.di.AuthClient
import com.paperless.scanner.util.NetworkConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

/**
 * ProtocolDetector - isolates the raw-OkHttp protocol & Paperless-server detection
 * probe from [com.paperless.scanner.data.repository.AuthRepository] (Issue #48).
 *
 * AuthRepository keeps the Retrofit-managed authentication surface (login / token
 * validation); the parallel raw `.newCall()` HTTP surface used purely to discover
 * whether a host speaks HTTP/HTTPS and is a Paperless-ngx server lives here.
 *
 * **WHY [AuthClient] (not the default client):** the detection probe inherits the
 * same cleartext-allowlist, certificate-pinning, and self-signed-trust interceptor
 * stack as authentication. That is precisely why [tryProtocol] can surface
 * [PaperlessException.CleartextBlocked] and [PaperlessException.CertificatePinMismatch]
 * as typed errors — the interceptors abort the call and the typed catches below
 * (which must precede the generic `IOException` catch) translate them.
 *
 * @property context Application context for localized error strings
 * @property client [AuthClient] OkHttpClient (allowlist + pinning + self-signed trust)
 */
class ProtocolDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    @AuthClient private val client: OkHttpClient,
) {
    companion object {
        private const val TAG = "ProtocolDetector"
    }

    // Dedicated client with shorter timeout for protocol detection
    private val detectionClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(NetworkConfig.DETECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(NetworkConfig.DETECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    fun tryProtocol(protocol: String, host: String): Result<String> {
        val url = "$protocol://$host"
        Log.d(TAG, "Trying $protocol: $url")

        return try {
            // First, try to verify this is actually a Paperless server
            // by checking /api/ response for Paperless-specific endpoints
            val apiRequest = Request.Builder()
                .url("$url/api/")
                .get()
                .build()

            detectionClient.newCall(apiRequest).execute().use { response ->
                Log.d(TAG, "$protocol /api/ response: ${response.code}")

                when (response.code) {
                    in 200..299 -> {
                        // Got a response - check if it's actually Paperless
                        val body = response.body?.string() ?: ""
                        if (isPaperlessApiResponse(body)) {
                            Log.d(TAG, "$protocol - Verified as Paperless server")
                            return Result.success(url)
                        } else {
                            Log.d(TAG, "$protocol - /api/ exists but not Paperless format")
                            // Fall through to secondary check
                        }
                    }
                    401, 403 -> {
                        // Auth required at /api/ - verify with documents endpoint
                        return verifyPaperlessWithDocumentsEndpoint(url, protocol)
                    }
                    404 -> {
                        Log.d(TAG, "$protocol - /api/ not found")
                        return Result.failure(PaperlessException.NetworkError(
                            IOException(context.getString(R.string.error_not_paperless_server))
                        ))
                    }
                    502 -> {
                        return Result.failure(PaperlessException.NetworkError(
                            IOException(context.getString(R.string.error_bad_gateway_paperless))
                        ))
                    }
                    503 -> {
                        return Result.failure(PaperlessException.NetworkError(
                            IOException(context.getString(R.string.error_service_unavailable))
                        ))
                    }
                    504 -> {
                        return Result.failure(PaperlessException.NetworkError(
                            IOException(context.getString(R.string.error_server_timeout))
                        ))
                    }
                    else -> {
                        Log.d(TAG, "$protocol - Unexpected response: ${response.code}")
                        // Fall through to secondary check
                    }
                }
            }

            // Secondary check: try /api/documents/ endpoint
            // This is more specific to Paperless and should return 401 if it's Paperless
            verifyPaperlessWithDocumentsEndpoint(url, protocol)

        } catch (e: UnknownHostException) {
            Log.e(TAG, "$protocol - Unknown host: ${e.message}")
            val suggestion = if (host.contains(".local") || !host.contains(".")) {
                context.getString(R.string.error_server_not_found_local, host)
            } else {
                context.getString(R.string.error_server_not_found_typo, host)
            }
            Result.failure(PaperlessException.NetworkError(IOException(suggestion)))
        } catch (e: SSLHandshakeException) {
            Log.d(TAG, "$protocol - SSL handshake failed: ${e.message}")
            Result.failure(PaperlessException.NetworkError(
                IOException(context.getString(R.string.error_ssl_invalid))
            ))
        } catch (e: SSLException) {
            Log.d(TAG, "$protocol - SSL error: ${e.message}")
            val message = e.message?.lowercase() ?: ""
            val errorText = when {
                message.contains("handshake") -> context.getString(R.string.error_ssl_handshake)
                message.contains("certificate") -> context.getString(R.string.error_ssl_verify)
                else -> context.getString(R.string.error_ssl_not_possible)
            }
            Result.failure(PaperlessException.NetworkError(IOException(errorText)))
        } catch (e: SocketTimeoutException) {
            Log.d(TAG, "$protocol - Timeout: ${e.message}")
            Result.failure(PaperlessException.NetworkError(
                IOException(context.getString(R.string.error_timeout_slow))
            ))
        } catch (e: ConnectException) {
            Log.d(TAG, "$protocol - Connection refused: ${e.message}")
            val message = e.message?.lowercase() ?: ""
            val errorText = when {
                message.contains("refused") -> context.getString(R.string.error_connection_refused_port)
                message.contains("reset") -> context.getString(R.string.error_connection_reset)
                else -> context.getString(R.string.error_connection_check_server)
            }
            Result.failure(PaperlessException.NetworkError(IOException(errorText)))
        } catch (e: com.paperless.scanner.data.api.CleartextNotAllowlistedException) {
            // Issue #233: must come BEFORE the IOException catch because
            // CleartextNotAllowlistedException extends IOException. Surface
            // the typed exception so the UI can route to the accept-dialog
            // instead of a generic network-error toast.
            Log.d(TAG, "$protocol - Cleartext blocked for host: ${e.host}")
            Result.failure(PaperlessException.CleartextBlocked(e.host))
        } catch (e: com.paperless.scanner.data.network.CertificatePinMismatchException) {
            // Issue #36: must precede the IOException catch (it extends IOException).
            // Surface the typed mismatch so the UI routes to the re-trust dialog
            // instead of a generic "server unreachable" error during detection.
            Log.d(TAG, "$protocol - Certificate pin mismatch for host: ${e.host}")
            Result.failure(PaperlessException.CertificatePinMismatch(e.host, e.expectedPin, e.actualPin))
        } catch (e: IOException) {
            Log.d(TAG, "$protocol - IO error: ${e.message}")
            val message = e.message?.lowercase() ?: ""
            val errorText = when {
                message.contains("network") -> context.getString(R.string.error_no_network)
                message.contains("host") -> context.getString(R.string.error_invalid_address)
                else -> context.getString(R.string.error_connection_generic, e.message ?: context.getString(R.string.error_unknown))
            }
            Result.failure(PaperlessException.NetworkError(IOException(errorText)))
        }
    }

    /**
     * Checks if the API response body contains Paperless-specific endpoints.
     * Paperless-ngx /api/ returns JSON with links to: documents, tags, correspondents, etc.
     */
    private fun isPaperlessApiResponse(body: String): Boolean {
        if (body.isBlank()) return false

        val lowerBody = body.lowercase()

        // Check for multiple Paperless-specific API endpoints
        val paperlessEndpoints = listOf(
            "documents",
            "tags",
            "correspondents",
            "document_types",
            "saved_views"
        )

        // Must contain at least 3 of these endpoints to be considered Paperless
        val matchCount = paperlessEndpoints.count { lowerBody.contains("\"$it\"") || lowerBody.contains("/$it/") }
        Log.d(TAG, "Paperless endpoint match count: $matchCount")

        return matchCount >= 3
    }

    /**
     * Secondary verification using /api/documents/ endpoint.
     * This endpoint is specific to Paperless and should return:
     * - 401/403 if auth required (valid Paperless)
     * - 200 with results array if public (valid Paperless)
     * - 404 if not Paperless
     */
    private fun verifyPaperlessWithDocumentsEndpoint(url: String, protocol: String): Result<String> {
        Log.d(TAG, "$protocol - Verifying with /api/documents/ endpoint")

        return try {
            val request = Request.Builder()
                .url("$url/api/documents/?page_size=1")
                .get()
                .build()

            detectionClient.newCall(request).execute().use { response ->
                Log.d(TAG, "$protocol /api/documents/ response: ${response.code}")

                when (response.code) {
                    in 200..299 -> {
                        // Check if response has Paperless document structure
                        val body = response.body?.string() ?: ""
                        if (body.contains("\"results\"") && (body.contains("\"count\"") || body.contains("\"id\""))) {
                            Log.d(TAG, "$protocol - Verified as Paperless via documents endpoint")
                            Result.success(url)
                        } else {
                            Log.d(TAG, "$protocol - /api/documents/ exists but wrong format")
                            Result.failure(PaperlessException.NetworkError(
                                IOException(context.getString(R.string.error_not_paperless_has_api))
                            ))
                        }
                    }
                    401, 403 -> {
                        // Auth required - this is expected for Paperless
                        Log.d(TAG, "$protocol - Verified as Paperless (auth required)")
                        Result.success(url)
                    }
                    404 -> {
                        Log.d(TAG, "$protocol - /api/documents/ not found - not Paperless")
                        Result.failure(PaperlessException.NetworkError(
                            IOException(context.getString(R.string.error_not_paperless_has_api))
                        ))
                    }
                    else -> {
                        Log.d(TAG, "$protocol - Unexpected documents response: ${response.code}")
                        Result.failure(PaperlessException.NetworkError(
                            IOException(context.getString(R.string.error_unexpected_response_code, response.code))
                        ))
                    }
                }
            }
        } catch (e: com.paperless.scanner.data.api.CleartextNotAllowlistedException) {
            // Issue #233: typed exception must precede IOException catch.
            Log.d(TAG, "$protocol - Cleartext blocked at documents endpoint: ${e.host}")
            Result.failure(PaperlessException.CleartextBlocked(e.host))
        } catch (e: com.paperless.scanner.data.network.CertificatePinMismatchException) {
            // Issue #36: typed exception must precede IOException catch.
            Log.d(TAG, "$protocol - Certificate pin mismatch at documents endpoint: ${e.host}")
            Result.failure(PaperlessException.CertificatePinMismatch(e.host, e.expectedPin, e.actualPin))
        } catch (e: IOException) {
            Log.e(TAG, "$protocol - Documents endpoint check failed", e)
            Result.failure(PaperlessException.NetworkError(
                IOException(context.getString(R.string.error_connection_verification))
            ))
        }
    }
}
