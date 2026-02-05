package com.paperless.scanner.data.repository

import android.content.Context
import android.util.Log
import com.paperless.scanner.R
import com.paperless.scanner.data.api.CloudflareDetectionInterceptor
import dagger.hilt.android.qualifiers.ApplicationContext
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.util.ServerUrlParser
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException
import com.paperless.scanner.data.analytics.AuthDebugReport
import com.paperless.scanner.data.analytics.AuthDebugService
import com.paperless.scanner.data.analytics.CrashlyticsHelper

/**
 * AuthRepository - Repository for authentication and server connection handling.
 *
 * **AUTHENTICATION FLOW:**
 * 1. [detectServerProtocol] - Auto-detect HTTPS/HTTP and verify Paperless server
 * 2. [login] - Authenticate with username/password to obtain API token
 * 3. OR [validateToken] - Validate existing API token (for token-based auth)
 * 4. [logout] - Clear credentials and reset connection state
 *
 * **SECURITY FEATURES:**
 * - HTTPS preferred over HTTP (tries HTTPS first)
 * - 2FA/MFA detection with user-friendly error messages
 * - Token-based authentication for accounts with 2FA enabled
 * - Cloudflare detection reset on logout for server switching
 *
 * **SERVER DETECTION:**
 * Supports various address formats:
 * - Domain names: `paperless.example.com`, `paperless.example.com:8000`
 * - IPv4 addresses: `192.168.1.100`, `192.168.1.100:8000`
 * - IPv6 addresses: `[::1]`, `[2001:db8::1]:8000`
 *
 * **ERROR HANDLING:**
 * Provides localized (German) error messages for common scenarios:
 * - Invalid credentials
 * - 2FA requirement
 * - SSL certificate issues
 * - Network connectivity problems
 * - Server not found
 *
 * @property tokenManager Secure storage for server URL and API token
 * @property client OkHttpClient for network requests
 * @property cloudflareDetectionInterceptor Handles Cloudflare-protected servers
 * @property crashlyticsHelper Analytics breadcrumb logging
 *
 * @see PaperlessApi.getToken For underlying token endpoint
 * @see TokenManager For credential persistence
 * @see LoginViewModel For UI layer usage
 */
class AuthRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokenManager: TokenManager,
    private val client: OkHttpClient,
    private val cloudflareDetectionInterceptor: CloudflareDetectionInterceptor,
    private val crashlyticsHelper: CrashlyticsHelper,
    private val authDebugService: AuthDebugService
) {
    companion object {
        private const val TAG = "AuthRepository"
    }

    // Dedicated client with shorter timeout for protocol detection
    private val detectionClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(com.paperless.scanner.util.NetworkConfig.DETECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(com.paperless.scanner.util.NetworkConfig.DETECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Detects whether the server uses HTTPS or HTTP.
     * Tries HTTPS first (preferred), falls back to HTTP if SSL fails.
     *
     * Supports:
     * - Domain names: paperless.example.com, paperless.example.com:8000
     * - IPv4 addresses: 192.168.1.100, 192.168.1.100:8000
     * - IPv6 addresses: [::1], [2001:db8::1]:8000
     *
     * @param host The hostname/IP with optional port (protocol and path are stripped)
     * @return The full URL with detected protocol (e.g., "https://paperless.example.com")
     */
    suspend fun detectServerProtocol(host: String): Result<String> {
        crashlyticsHelper.logActionBreadcrumb("SERVER_DETECT", host)
        // Use ServerUrlParser for comprehensive URL validation
        val parseResult = ServerUrlParser.parse(host)

        val cleanHost = when (parseResult) {
            is ServerUrlParser.ParseResult.Success -> parseResult.toHostString()
            is ServerUrlParser.ParseResult.Error -> {
                return Result.failure(PaperlessException.ContentError(parseResult.messageResId))
            }
        }

        Log.d(TAG, "Detecting protocol for: $cleanHost")

        // Try HTTPS first (preferred)
        val httpsResult = tryProtocol("https", cleanHost)
        if (httpsResult.isSuccess) {
            crashlyticsHelper.logActionBreadcrumb("SERVER_DETECT_SUCCESS", "https://$cleanHost")
            return httpsResult
        }

        // Try HTTP as fallback
        val httpResult = tryProtocol("http", cleanHost)
        if (httpResult.isSuccess) {
            crashlyticsHelper.logActionBreadcrumb("SERVER_DETECT_SUCCESS", "http://$cleanHost")
            return httpResult
        }

        // Both failed - return the most informative error
        val httpsError = httpsResult.exceptionOrNull()
        val httpError = httpResult.exceptionOrNull()

        crashlyticsHelper.logStateBreadcrumb("SERVER_DETECT_ERROR", "HTTPS: ${httpsError?.message}, HTTP: ${httpError?.message}")
        Log.e(TAG, "Both protocols failed. HTTPS: ${httpsError?.message}, HTTP: ${httpError?.message}")

        // Log server detection failure to auth debug service
        authDebugService.logAuthFailure(
            authType = AuthDebugReport.AuthType.SERVER_DETECTION,
            serverUrl = cleanHost,
            errorType = "SERVER_DETECT_FAILED",
            errorMessage = "HTTPS: ${httpsError?.message}, HTTP: ${httpError?.message}",
            serverDetection = AuthDebugReport.ServerDetectionInfo(
                httpsAttempted = true,
                httpsResult = httpsError?.message,
                httpAttempted = true,
                httpResult = httpError?.message
            )
        )

        // Return the most specific error message
        // Priority: specific errors > generic errors
        val httpsMsg = httpsError?.message ?: ""
        val httpMsg = httpError?.message ?: ""

        return when {
            // Server not found errors (English patterns - base language)
            httpsMsg.contains("not found", ignoreCase = true) -> Result.failure(httpsError!!)
            httpMsg.contains("not found", ignoreCase = true) -> Result.failure(httpError!!)

            // Not a Paperless server
            httpsMsg.contains("not a paperless", ignoreCase = true) -> Result.failure(httpsError!!)
            httpMsg.contains("not a paperless", ignoreCase = true) -> Result.failure(httpError!!)

            // SSL specific errors (prefer these as they're actionable)
            httpsMsg.contains("SSL", ignoreCase = true) || httpsMsg.contains("certificate", ignoreCase = true) -> Result.failure(httpsError!!)

            // Connection refused (server might be down)
            httpsMsg.contains("refused", ignoreCase = true) || httpMsg.contains("refused", ignoreCase = true) -> {
                Result.failure(PaperlessException.NetworkError(
                    IOException(context.getString(R.string.error_connection_refused_reachable))
                ))
            }

            // Timeout
            httpsMsg.contains("timeout", ignoreCase = true) || httpMsg.contains("timeout", ignoreCase = true) -> {
                Result.failure(PaperlessException.NetworkError(
                    IOException(context.getString(R.string.error_timeout_slow_server))
                ))
            }

            // Network issues
            httpsMsg.contains("network", ignoreCase = true) || httpMsg.contains("network", ignoreCase = true) -> {
                Result.failure(PaperlessException.NetworkError(
                    IOException(context.getString(R.string.error_no_network))
                ))
            }

            // Fallback with more context
            else -> Result.failure(
                PaperlessException.NetworkError(
                    IOException(context.getString(R.string.error_server_unreachable_at, cleanHost))
                )
            )
        }
    }

    private fun tryProtocol(protocol: String, host: String): Result<String> {
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
        } catch (e: IOException) {
            Log.e(TAG, "$protocol - Documents endpoint check failed", e)
            Result.failure(PaperlessException.NetworkError(
                IOException(context.getString(R.string.error_connection_verification))
            ))
        }
    }

    /**
     * Authenticate user with username and password.
     *
     * **AUTHENTICATION FLOW:**
     * 1. Sends POST to `/api/token/` with form-encoded credentials
     * 2. On success: Saves token to [TokenManager] and returns [LoginResult.Success]
     * 3. On failure: Returns appropriate [PaperlessException] with localized message
     *
     * **2FA/MFA HANDLING:**
     * If the server requires 2FA, this method detects it and returns a
     * user-friendly message directing users to use token-based authentication.
     *
     * @param serverUrl Full server URL (e.g., "https://paperless.example.com")
     * @param username Paperless-ngx username
     * @param password Paperless-ngx password
     * @return [Result] containing [LoginResult.Success] with token, or failure with [PaperlessException]
     * @see validateToken For token-based authentication (required when 2FA is enabled)
     * @see TokenManager.saveCredentials For credential persistence
     */
    suspend fun login(serverUrl: String, username: String, password: String): Result<LoginResult> {
        crashlyticsHelper.logActionBreadcrumb("LOGIN", "username=$username")
        return try {
            val normalizedUrl = serverUrl.trimEnd('/')
            val formBody = FormBody.Builder()
                .add("username", username)
                .add("password", password)
                .build()

            val request = Request.Builder()
                .url("$normalizedUrl/api/token/")
                .post(formBody)
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                val json = JSONObject(responseBody ?: "{}")

                // Extract token from successful response
                val token = json.optString("token", "")
                if (token.isNotBlank()) {
                    tokenManager.saveCredentials(normalizedUrl, token)
                    crashlyticsHelper.logActionBreadcrumb("LOGIN_SUCCESS")
                    Result.success(LoginResult.Success(token))
                } else {
                    crashlyticsHelper.logStateBreadcrumb("LOGIN_ERROR", "Token not found in response")
                    Result.failure(PaperlessException.ParseError(context.getString(R.string.error_token_not_in_response)))
                }
            } else {
                val errorBody = response.body?.string() ?: ""
                Log.d(TAG, "Login error - Code: ${response.code}, Body: $errorBody")

                // Log to auth debug service with full context
                authDebugService.logAuthFailure(
                    authType = AuthDebugReport.AuthType.PASSWORD_LOGIN,
                    serverUrl = normalizedUrl,
                    httpStatusCode = response.code,
                    errorType = "HTTP_${response.code}",
                    errorMessage = errorBody.take(200),
                    response = response,
                    responseBody = errorBody
                )

                // Check if error indicates 2FA requirement - if so, return specific message
                val mfaErrorMessage = check2FARequirement(
                    errorBody = errorBody,
                    responseCode = response.code
                )
                if (mfaErrorMessage != null) {
                    val exception = PaperlessException.AuthError(
                        code = response.code,
                        customMessage = mfaErrorMessage
                    )
                    return Result.failure(exception)
                }

                val errorMessage = parseLoginError(errorBody, response.code)
                crashlyticsHelper.logStateBreadcrumb("LOGIN_ERROR", "HTTP ${response.code}")
                val exception = PaperlessException.AuthError(
                    code = response.code,
                    customMessage = errorMessage
                )
                Result.failure(exception)
            }
        } catch (e: IOException) {
            crashlyticsHelper.logStateBreadcrumb("LOGIN_ERROR", "NetworkError: ${e.message}")
            // Log network errors to auth debug service
            authDebugService.logAuthFailure(
                authType = AuthDebugReport.AuthType.PASSWORD_LOGIN,
                serverUrl = serverUrl,
                errorType = e.javaClass.simpleName,
                errorMessage = e.message
            )
            Result.failure(PaperlessException.NetworkError(e))
        } catch (e: Exception) {
            crashlyticsHelper.logStateBreadcrumb("LOGIN_ERROR", "${e.javaClass.simpleName}: ${e.message}")
            // Log unexpected errors to auth debug service
            authDebugService.logAuthFailure(
                authType = AuthDebugReport.AuthType.PASSWORD_LOGIN,
                serverUrl = serverUrl,
                errorType = e.javaClass.simpleName,
                errorMessage = e.message
            )
            Result.failure(PaperlessException.from(e))
        }
    }

    sealed class LoginResult {
        data class Success(val token: String) : LoginResult()
    }

    /**
     * Checks if error response indicates 2FA requirement.
     * Returns a special error message directing users to use API token authentication instead.
     */
    private fun check2FARequirement(
        errorBody: String,
        responseCode: Int
    ): String? {
        try {
            // Try to parse as JSON first
            val json = JSONObject(errorBody)

            // Method 1: Check for requires_2fa flag (some versions)
            val requires2FA = json.optBoolean("requires_2fa", false)
            if (requires2FA) {
                Log.d(TAG, "2FA required - requires_2fa flag found")
                return context.getString(R.string.error_2fa_enabled)
            }

            // Method 2: Check for MFA-related errors in non_field_errors
            if (responseCode == 400) {
                val nonFieldErrors = json.optJSONArray("non_field_errors")
                if (nonFieldErrors != null) {
                    for (i in 0 until nonFieldErrors.length()) {
                        val error = nonFieldErrors.optString(i, "").lowercase()

                        // Check for explicit MFA messages
                        if (error.contains("mfa") || error.contains("totp") || error.contains("code is required")) {
                            Log.d(TAG, "2FA required - MFA error message found: $error")
                            return context.getString(R.string.error_2fa_enabled)
                        }

                        // Some Paperless-ngx versions return generic "Unable to log in" when MFA is active
                        if (error.contains("unable to log in")) {
                            Log.d(TAG, "2FA potentially required - generic login error with MFA possible: $error")
                            return context.getString(R.string.error_2fa_enabled)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Not JSON or doesn't contain 2FA info - continue with normal error handling
            Log.d(TAG, "Error body is not JSON or doesn't contain 2FA info: ${e.message}")
        }

        return null // No 2FA requirement detected
    }

    /**
     * Parses login error response and returns a user-friendly message
     */
    private fun parseLoginError(errorBody: String, responseCode: Int): String {
        return when (responseCode) {
            401, 403 -> context.getString(R.string.error_username_password_incorrect)
            else -> context.getString(R.string.error_login_failed)
        }
    }

    /**
     * Validate an API token against the server.
     *
     * **USE CASES:**
     * - Initial token validation during token-based login
     * - Token validation for accounts with 2FA enabled
     * - Token obtained via OCR scan from settings page
     *
     * **VALIDATION METHOD:**
     * Makes a lightweight GET request to `/api/tags/?page_size=1` with
     * the token in the Authorization header. A 200 response confirms validity.
     *
     * @param serverUrl Full server URL (e.g., "https://paperless.example.com")
     * @param token API token to validate (whitespace/newlines are automatically cleaned)
     * @return [Result] with Unit on success, or failure with [PaperlessException]
     * @see login For username/password authentication
     * @see TokenManager.saveCredentials Called after successful validation
     */
    suspend fun validateToken(serverUrl: String, token: String): Result<Unit> {
        crashlyticsHelper.logActionBreadcrumb("TOKEN_VALIDATE")
        return try {
            val normalizedUrl = serverUrl.trimEnd('/')
            // Clean the token - remove any whitespace or line breaks from OCR
            val cleanToken = token.trim().replace("\\s+".toRegex(), "")

            Log.d(TAG, "Validating token (length: ${cleanToken.length}) against: $normalizedUrl")

            val request = Request.Builder()
                .url("$normalizedUrl/api/tags/?page_size=1")
                .header("Authorization", "Token $cleanToken")
                .get()
                .build()

            val response = client.newCall(request).execute()
            Log.d(TAG, "Token validation response: ${response.code}")

            if (response.isSuccessful) {
                crashlyticsHelper.logActionBreadcrumb("TOKEN_VALIDATE_SUCCESS")
                Result.success(Unit)
            } else {
                val errorBody = response.body?.string() ?: ""

                // Log to auth debug service with full context
                authDebugService.logAuthFailure(
                    authType = AuthDebugReport.AuthType.TOKEN_VALIDATION,
                    serverUrl = normalizedUrl,
                    httpStatusCode = response.code,
                    errorType = "HTTP_${response.code}",
                    errorMessage = errorBody.take(200),
                    response = response,
                    responseBody = errorBody
                )

                // Provide better error message for token validation failures
                val errorMessage = when (response.code) {
                    401 -> context.getString(R.string.error_token_invalid_check)
                    403 -> context.getString(R.string.error_token_no_permission)
                    else -> context.getString(R.string.error_connection_failed_code, response.code)
                }
                crashlyticsHelper.logStateBreadcrumb("TOKEN_ERROR", "HTTP ${response.code}")
                Result.failure(PaperlessException.AuthError(response.code, customMessage = errorMessage))
            }
        } catch (e: IOException) {
            crashlyticsHelper.logStateBreadcrumb("TOKEN_ERROR", "NetworkError: ${e.message}")
            Log.e(TAG, "Token validation network error", e)
            // Log network errors to auth debug service
            authDebugService.logAuthFailure(
                authType = AuthDebugReport.AuthType.TOKEN_VALIDATION,
                serverUrl = serverUrl,
                errorType = e.javaClass.simpleName,
                errorMessage = e.message
            )
            Result.failure(PaperlessException.NetworkError(e))
        } catch (e: Exception) {
            crashlyticsHelper.logStateBreadcrumb("TOKEN_ERROR", "${e.javaClass.simpleName}: ${e.message}")
            Log.e(TAG, "Token validation error", e)
            // Log unexpected errors to auth debug service
            authDebugService.logAuthFailure(
                authType = AuthDebugReport.AuthType.TOKEN_VALIDATION,
                serverUrl = serverUrl,
                errorType = e.javaClass.simpleName,
                errorMessage = e.message
            )
            Result.failure(PaperlessException.from(e))
        }
    }

    /**
     * Log out and clear all stored credentials.
     *
     * **CLEANUP ACTIONS:**
     * 1. Resets Cloudflare detection state (allows fresh detection on next login)
     * 2. Clears all credentials from [TokenManager] (server URL, token, settings)
     *
     * **IMPORTANT:** This enables server switching - after logout, the user can
     * connect to a different Paperless-ngx instance.
     *
     * @see TokenManager.clearCredentials For credential cleanup details
     * @see CloudflareDetectionInterceptor.resetDetection For Cloudflare state reset
     */
    suspend fun logout() {
        // Reset Cloudflare detection to allow fresh detection on next login
        // (important if user switches to a different server)
        cloudflareDetectionInterceptor.resetDetection()

        // Clear all credentials and settings (includes Cloudflare flag in DataStore)
        tokenManager.clearCredentials()
    }
}
