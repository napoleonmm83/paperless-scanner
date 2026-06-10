package com.paperless.scanner.data.repository

import android.content.Context
import android.util.Log
import com.paperless.scanner.R
import com.paperless.scanner.data.api.CloudflareDetectionInterceptor
import dagger.hilt.android.qualifiers.ApplicationContext
import com.paperless.scanner.domain.error.PaperlessException
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.data.service.ProtocolDetector
import com.paperless.scanner.util.LogSanitizer
import com.paperless.scanner.util.ServerUrlParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import com.paperless.scanner.data.analytics.AuthDebugReport
import com.paperless.scanner.data.analytics.AuthDebugService
import com.paperless.scanner.data.analytics.CrashlyticsHelperContract

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
 * @property httpCache Disk cache shared with the Paperless-ngx OkHttpClient;
 *           evicted on logout so a new account/server starts cold (Issue #131)
 * @property protocolDetector Raw-OkHttp HTTP/HTTPS + Paperless-server detection
 *           probe extracted out of this repository (Issue #48)
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
    private val crashlyticsHelper: CrashlyticsHelperContract,
    private val authDebugService: AuthDebugService,
    private val httpCache: Cache,
    private val protocolDetector: ProtocolDetector,
) {
    companion object {
        private const val TAG = "AuthRepository"
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

        val (cleanHost, userScheme) = when (parseResult) {
            is ServerUrlParser.ParseResult.Success ->
                parseResult.toHostString() to parseResult.userScheme
            is ServerUrlParser.ParseResult.Error -> {
                return Result.failure(PaperlessException.ContentError(parseResult.messageResId))
            }
        }

        Log.d(TAG, "Detecting protocol for: $cleanHost (userScheme=$userScheme)")

        // Issue #233: honor the user's explicit scheme. If they typed http://,
        // do NOT probe https — that produces a TLS handshake error against a
        // plain-HTTP server. If they typed https://, do NOT silently fall
        // back to cleartext. Only when no scheme was given do we keep the
        // HTTPS-first / HTTP-fallback dual probe.
        if (userScheme == "http") {
            val httpResult = protocolDetector.tryProtocol("http", cleanHost)
            if (httpResult.isSuccess) {
                crashlyticsHelper.logActionBreadcrumb("SERVER_DETECT_SUCCESS", "http://$cleanHost")
                return httpResult
            }
            val httpError: Throwable = httpResult.exceptionOrNull()
                ?: IOException("HTTP detection failed without exception")
            authDebugService.logAuthFailure(
                authType = AuthDebugReport.AuthType.SERVER_DETECTION,
                serverUrl = cleanHost,
                errorType = "SERVER_DETECT_FAILED_HTTP_ONLY",
                errorMessage = "HTTP: ${httpError.message}",
                serverDetection = AuthDebugReport.ServerDetectionInfo(
                    httpsAttempted = false,
                    httpsResult = null,
                    httpAttempted = true,
                    httpResult = httpError.message
                )
            )
            // Surface CleartextBlocked verbatim so LoginViewModel can route
            // to the accept-dialog instead of a generic error.
            return Result.failure(httpError)
        }

        if (userScheme == "https") {
            val httpsResult = protocolDetector.tryProtocol("https", cleanHost)
            if (httpsResult.isSuccess) {
                crashlyticsHelper.logActionBreadcrumb("SERVER_DETECT_SUCCESS", "https://$cleanHost")
                return httpsResult
            }
            val httpsError: Throwable = httpsResult.exceptionOrNull()
                ?: IOException("HTTPS detection failed without exception")
            authDebugService.logAuthFailure(
                authType = AuthDebugReport.AuthType.SERVER_DETECTION,
                serverUrl = cleanHost,
                errorType = "SERVER_DETECT_FAILED_HTTPS_ONLY",
                errorMessage = "HTTPS: ${httpsError.message}",
                serverDetection = AuthDebugReport.ServerDetectionInfo(
                    httpsAttempted = true,
                    httpsResult = httpsError.message,
                    httpAttempted = false,
                    httpResult = null
                )
            )
            return Result.failure(httpsError)
        }

        // userScheme == null: keep HTTPS-first / HTTP-fallback (current behavior).
        val httpsResult = protocolDetector.tryProtocol("https", cleanHost)
        if (httpsResult.isSuccess) {
            crashlyticsHelper.logActionBreadcrumb("SERVER_DETECT_SUCCESS", "https://$cleanHost")
            return httpsResult
        }

        // Try HTTP as fallback
        val httpResult = protocolDetector.tryProtocol("http", cleanHost)
        if (httpResult.isSuccess) {
            crashlyticsHelper.logActionBreadcrumb("SERVER_DETECT_SUCCESS", "http://$cleanHost")
            return httpResult
        }

        // Both failed - return the most informative error.
        // Fallbacks ensure non-null types so the when-branches below are NPE-safe even
        // if a Result.failure was somehow constructed without an exception (defensive).
        val httpsError: Throwable = httpsResult.exceptionOrNull()
            ?: IOException("HTTPS detection failed without exception")
        val httpError: Throwable = httpResult.exceptionOrNull()
            ?: IOException("HTTP detection failed without exception")

        crashlyticsHelper.logStateBreadcrumb("SERVER_DETECT_ERROR", "HTTPS: ${httpsError.message}, HTTP: ${httpError.message}")
        Log.e(TAG, "Both protocols failed. HTTPS: ${httpsError.message}, HTTP: ${httpError.message}")

        // Log server detection failure to auth debug service
        authDebugService.logAuthFailure(
            authType = AuthDebugReport.AuthType.SERVER_DETECTION,
            serverUrl = cleanHost,
            errorType = "SERVER_DETECT_FAILED",
            errorMessage = "HTTPS: ${httpsError.message}, HTTP: ${httpError.message}",
            serverDetection = AuthDebugReport.ServerDetectionInfo(
                httpsAttempted = true,
                httpsResult = httpsError.message,
                httpAttempted = true,
                httpResult = httpError.message
            )
        )

        // Return the most specific error message
        // Priority: specific errors > generic errors
        val httpsMsg = httpsError.message ?: ""
        val httpMsg = httpError.message ?: ""

        return when {
            // Issue #36: a changed pinned certificate must win the priority race so
            // the UI routes to the blocking re-trust dialog instead of a generic
            // "unreachable" error. Only HTTPS can mismatch — cleartext HTTP has no
            // handshake and is never pinned — so we check the HTTPS error only.
            httpsError is PaperlessException.CertificatePinMismatch -> Result.failure(httpsError)

            // Issue #233: CleartextBlocked must win the priority race so the
            // UI can route to the accept-dialog instead of a generic error.
            // HTTP-attempt-blocked is the deadlock fingerprint this PR fixes.
            httpError is PaperlessException.CleartextBlocked -> Result.failure(httpError)
            httpsError is PaperlessException.CleartextBlocked -> Result.failure(httpsError)

            // Server not found errors (English patterns - base language)
            httpsMsg.contains("not found", ignoreCase = true) -> Result.failure(httpsError)
            httpMsg.contains("not found", ignoreCase = true) -> Result.failure(httpError)

            // Not a Paperless server
            httpsMsg.contains("not a paperless", ignoreCase = true) -> Result.failure(httpsError)
            httpMsg.contains("not a paperless", ignoreCase = true) -> Result.failure(httpError)

            // SSL specific errors (prefer these as they're actionable)
            httpsMsg.contains("SSL", ignoreCase = true) || httpsMsg.contains("certificate", ignoreCase = true) -> Result.failure(httpsError)

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
                Log.d(TAG, "Login error - Code: ${response.code}, Body: ${LogSanitizer.sanitizeErrorBody(errorBody)}")

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

                // Issue #27: a Cloudflare/edge-proxy WAF block (HTML challenge) is
                // NOT a credential error. Surface a distinct message so the user
                // isn't told their (correct) login is wrong.
                if (isEdgeProxyBlock(response, errorBody)) {
                    crashlyticsHelper.logStateBreadcrumb("LOGIN_ERROR", "EdgeProxyBlock HTTP ${response.code}")
                    // Return the typed error only; the UI layer resolves the
                    // user-facing message from messageResId (project localization rule).
                    return Result.failure(PaperlessException.ProxyBlocked(code = response.code))
                }

                val errorMessage = parseLoginError(errorBody, response.code)
                crashlyticsHelper.logStateBreadcrumb("LOGIN_ERROR", "HTTP ${response.code}")
                val exception = PaperlessException.AuthError(
                    code = response.code,
                    customMessage = errorMessage
                )
                Result.failure(exception)
            }
        } catch (e: com.paperless.scanner.data.network.CertificatePinMismatchException) {
            // Issue #36: must precede the IOException catch (CertificatePinMismatchException
            // extends IOException). Surface the typed mismatch so LoginViewModel can route
            // to the blocking re-trust dialog instead of a generic network-error toast.
            crashlyticsHelper.logStateBreadcrumb("LOGIN_ERROR", "CertPinMismatch host=${e.host}")
            Result.failure(PaperlessException.CertificatePinMismatch(e.host, e.expectedPin, e.actualPin))
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
     * Issue #27: detects whether a 4xx response was produced by an edge proxy /
     * WAF (e.g. Cloudflare) rather than by the Paperless-ngx backend.
     *
     * A Cloudflare/edge WAF challenge is returned as an HTML page with a 403 (or
     * similar) status, which we previously mis-mapped to "bad credentials" /
     * "no permission". The user's login is fine — the request never reached
     * Paperless — so we surface a distinct, actionable message instead.
     *
     * Detection requires Cloudflare challenge/block-SPECIFIC evidence (codex P2):
     * - the `cf-mitigated` response header — set ONLY on an active Cloudflare
     *   challenge/block, so it is definitive on its own; OR
     * - a `text/html` body carrying a Cloudflare block-page phrase
     *   ("Attention Required" or "Sorry, you have been blocked").
     *
     * Generic signals are excluded on purpose: a bare HTML `<title>` matches any
     * error page (nginx 502 / 404 / maintenance), and the word "cloudflare"
     * appears on Cloudflare's branded *outage* pages too (502/521/522/525 when
     * the ORIGIN is down) which are server errors, not WAF blocks. Such responses
     * fall through to the normal HTTP-specific handling instead of the misleading
     * WAF/Cloudflare guidance.
     *
     * **IMPORTANT:** [errorBody] is the string already read from
     * `response.body?.string()` at the call site. We MUST NOT call
     * `response.body?.string()` again here — an OkHttp response body is a
     * one-shot stream and a second read throws. We only inspect headers on
     * [response] and re-use the passed-in [errorBody] for the body match.
     */
    private fun isEdgeProxyBlock(response: okhttp3.Response, errorBody: String): Boolean {
        // `cf-mitigated` is set ONLY when Cloudflare actively challenged/blocked the
        // request, so it is a definitive block signal on its own.
        if (response.header("cf-mitigated") != null) return true

        // Otherwise require an actual HTML challenge/block PAGE. A Paperless instance that
        // simply sits behind Cloudflare returns its legitimate 401/403 as JSON yet still
        // carries `cf-ray` / `Server: cloudflare` headers — so those headers (and `text/html`
        // alone) must NOT be treated as a block, or real bad-credential/permission errors get
        // mis-mapped to the proxy message (codex P2). Gate the body markers on text/html.
        val isHtml = response.header("Content-Type")?.contains("text/html", ignoreCase = true) == true
        if (!isHtml) return false

        // Require a Cloudflare block-page-SPECIFIC body phrase. Generic markers
        // are deliberately excluded because they also appear on non-block pages:
        //   - a bare `<title>` matches ANY HTML error page (nginx 502 / 404 / …);
        //   - the word "cloudflare" appears on Cloudflare's branded *outage*
        //     pages too (502/521/522/525 when the ORIGIN is down), which are
        //     server errors, not WAF blocks.
        // "Attention Required" / "Sorry, you have been blocked" are specific to
        // Cloudflare's block/challenge page; the cf-mitigated header (above) is
        // the language-independent backstop. Everything else falls through to
        // normal HTTP handling (codex P2 ×2).
        val lowerBody = errorBody.lowercase()
        return lowerBody.contains("attention required") ||
            lowerBody.contains("sorry, you have been blocked")
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

                // Issue #27: a Cloudflare/edge-proxy WAF block (HTML challenge) is
                // NOT a token/permission error. Surface a distinct message so the
                // user isn't told their (valid) token is invalid or lacks access.
                if (isEdgeProxyBlock(response, errorBody)) {
                    crashlyticsHelper.logStateBreadcrumb("TOKEN_ERROR", "EdgeProxyBlock HTTP ${response.code}")
                    // Return the typed error only; the UI layer resolves the
                    // user-facing message from messageResId (project localization rule).
                    return Result.failure(PaperlessException.ProxyBlocked(code = response.code))
                }

                // Provide better error message for token validation failures
                val errorMessage = when (response.code) {
                    401 -> context.getString(R.string.error_token_invalid_check)
                    403 -> context.getString(R.string.error_token_no_permission)
                    else -> context.getString(R.string.error_connection_failed_code, response.code)
                }
                crashlyticsHelper.logStateBreadcrumb("TOKEN_ERROR", "HTTP ${response.code}")
                Result.failure(PaperlessException.AuthError(response.code, customMessage = errorMessage))
            }
        } catch (e: com.paperless.scanner.data.network.CertificatePinMismatchException) {
            // Issue #36: precede the IOException catch (it extends IOException).
            crashlyticsHelper.logStateBreadcrumb("TOKEN_ERROR", "CertPinMismatch host=${e.host}")
            Result.failure(PaperlessException.CertificatePinMismatch(e.host, e.expectedPin, e.actualPin))
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
     * 3. Evicts the shared OkHttp disk cache (Issue #131) so the next account
     *    or server does not see responses cached under the previous identity
     *
     * **IMPORTANT:** This enables server switching - after logout, the user can
     * connect to a different Paperless-ngx instance.
     *
     * @see TokenManager.clearCredentials For credential cleanup details
     * @see CloudflareDetectionInterceptor.resetDetection For Cloudflare state reset
     * @see okhttp3.Cache.evictAll Disk cache eviction
     */
    suspend fun logout() {
        // Reset Cloudflare detection to allow fresh detection on next login
        // (important if user switches to a different server)
        cloudflareDetectionInterceptor.resetDetection()

        // Clear all credentials and settings (includes Cloudflare flag in DataStore)
        tokenManager.clearCredentials()

        // Evict the OkHttp disk cache so a new login on the same device does
        // not serve responses cached under the previous account/server.
        // evictAll() does blocking disk I/O — confine it to Dispatchers.IO and
        // swallow IOException: credentials are already cleared, so a failed
        // disk eviction must not surface as a logout failure to the UI. Stale
        // cache entries are isolated by Vary-less key collisions only on the
        // same server+path, which the next login renders harmless via a fresh
        // identity, and the next clean logout will clear them.
        withContext(Dispatchers.IO) {
            try {
                httpCache.evictAll()
            } catch (e: IOException) {
                Log.w(TAG, "Failed to evict OkHttp cache on logout", e)
                crashlyticsHelper.logStateBreadcrumb(
                    "LOGOUT_CACHE_EVICT_FAILED",
                    "${e.javaClass.simpleName}: ${e.message}"
                )
            }
        }
    }
}
