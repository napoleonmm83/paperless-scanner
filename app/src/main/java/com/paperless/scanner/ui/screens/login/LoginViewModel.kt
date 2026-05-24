package com.paperless.scanner.ui.screens.login

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.R
import com.paperless.scanner.data.analytics.AnalyticsEvent
import com.paperless.scanner.data.analytics.AnalyticsService
import com.paperless.scanner.data.analytics.AuthDebugService
import com.paperless.scanner.data.api.HttpAllowlistInterceptor
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.data.network.CertificatePinStore
import com.paperless.scanner.data.network.ObservedCertHolder
import com.paperless.scanner.data.repository.AuthRepository
import com.paperless.scanner.util.BiometricHelper
import com.paperless.scanner.util.LoginRateLimiter
import com.paperless.scanner.util.LoginRateLimitState
import com.paperless.scanner.util.ServerUrlParser
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val authRepository: AuthRepository,
    private val tokenManager: TokenManager,
    private val analyticsService: AnalyticsService,
    private val loginRateLimiter: LoginRateLimiter,
    val biometricHelper: BiometricHelper,
    private val authDebugService: AuthDebugService,
    private val certificatePinStore: CertificatePinStore,
    private val observedCertHolder: ObservedCertHolder
) : ViewModel() {

    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private val _canUseBiometric = MutableStateFlow(false)
    val canUseBiometric: StateFlow<Boolean> = _canUseBiometric.asStateFlow()

    private val _serverStatus = MutableStateFlow<ServerStatus>(ServerStatus.Idle)
    val serverStatus: StateFlow<ServerStatus> = _serverStatus.asStateFlow()

    /** Rate limit state for UI display (lockout countdown, remaining attempts) */
    val rateLimitState: StateFlow<LoginRateLimitState> = loginRateLimiter.rateLimitState

    // Cached detected URL for login
    private var detectedServerUrl: String? = null

    // Debounce job for server detection
    private var detectionJob: Job? = null

    companion object {
        private const val TAG = "LoginViewModel"
        private const val DEBOUNCE_MS = 800L
    }

    init {
        checkBiometricAvailability()
    }

    /**
     * Called on every URL change - handles debouncing internally
     */
    fun onServerUrlChanged(serverUrl: String) {
        // Cancel previous detection job
        detectionJob?.cancel()

        if (serverUrl.isBlank()) {
            _serverStatus.update { ServerStatus.Idle }
            detectedServerUrl = null
            return
        }

        // Issue #233: parse-shape gate. Cheaply validate the URL before
        // spending a network round-trip and (more importantly) before
        // surfacing a false error to the user while they are still typing.
        // "192." / "192" / ".com" are silently kept in Idle until the user
        // finishes typing something parseable.
        val parsed = ServerUrlParser.parse(serverUrl)
        if (parsed is ServerUrlParser.ParseResult.Error) {
            _serverStatus.update { ServerStatus.Idle }
            detectedServerUrl = null
            Log.d(TAG, "URL not parseable yet, status = Idle")
            return
        }

        // Start new debounced detection
        detectionJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            detectServerInternal(serverUrl)
        }
    }

    private suspend fun detectServerInternal(serverUrl: String) {
        Log.d(TAG, "Starting detection for: $serverUrl")

        // Issue #233: pre-detection gate. When the user typed http:// explicitly
        // for a non-loopback host they have not yet accepted, surface the
        // RequiresHttpAccept state BEFORE any network probe. This unblocks the
        // chicken-and-egg: previously the accept-dialog only fired on a
        // ServerStatus.Success branch that we could never reach because the
        // interceptor was blocking the HTTP probe.
        val parsed = ServerUrlParser.parse(serverUrl)
        if (parsed is ServerUrlParser.ParseResult.Success && parsed.userScheme == "http") {
            val host = parsed.host.lowercase()
            val isHardAllowed = host in HttpAllowlistInterceptor.HARD_ALLOWED_HOSTS
            val isUserAccepted = tokenManager.isHostAcceptedForHttp(host)
            if (!isHardAllowed && !isUserAccepted) {
                withContext(Dispatchers.Main) {
                    _serverStatus.update { ServerStatus.RequiresHttpAccept(host, ServerStatus.RequiresHttpAccept.Reason.USER_CHOSE_HTTP) }
                    Log.d(TAG, "Status = RequiresHttpAccept(USER_CHOSE_HTTP, host=$host)")
                }
                return
            }
        }

        // Check if user explicitly entered http:// (not a fallback in this case)
        val userExplicitlyUsedHttp = serverUrl.trim().lowercase().startsWith("http://")

        // Update status on Main thread
        withContext(Dispatchers.Main) {
            _serverStatus.update { ServerStatus.Checking }
            Log.d(TAG, "Status = Checking")
        }

        // Do network call on IO
        val result = withContext(Dispatchers.IO) {
            authRepository.detectServerProtocol(serverUrl)
        }

        // Update status on Main thread
        withContext(Dispatchers.Main) {
            result
                .onSuccess { url ->
                    val isHttps = url.startsWith("https://")
                    // HTTP fallback = we're using HTTP but user didn't explicitly request it
                    val isHttpFallback = !isHttps && !userExplicitlyUsedHttp
                    detectedServerUrl = url
                    _serverStatus.update { ServerStatus.Success(url, isHttps, isHttpFallback) }
                    Log.d(TAG, "Status = Success, url = $url, isHttps = $isHttps, isHttpFallback = $isHttpFallback")
                }
                .onFailure { exception ->
                    detectedServerUrl = null
                    // Issue #233: a CleartextBlocked failure means the interceptor
                    // refused a non-loopback HTTP request the user has not yet
                    // accepted. Surface the accept-dialog state with the
                    // HTTPS_FAILED_HTTP_BLOCKED reason so the dialog copy can
                    // explain "HTTPS unreachable, allow HTTP fallback?".
                    if (exception is PaperlessException.CleartextBlocked) {
                        _serverStatus.update { ServerStatus.RequiresHttpAccept(exception.host, ServerStatus.RequiresHttpAccept.Reason.HTTPS_FAILED_HTTP_BLOCKED) }
                        Log.d(TAG, "Status = RequiresHttpAccept(HTTPS_FAILED_HTTP_BLOCKED, host=${exception.host})")
                    } else {
                        val message = exception.message ?: context.getString(R.string.error_server_unreachable)
                        _serverStatus.update { ServerStatus.Error(message) }
                        Log.d(TAG, "Status = Error, message = $message")
                    }
                }
        }
    }

    /**
     * Issue #233: called by the setup UI when the user accepts the
     * cleartext-HTTP warning. Persists the host into the allowlist and
     * re-runs detection. The brief delay lets [TokenManager]'s DataStore
     * write propagate to [HttpAllowlistHolder]'s atomic cache before the
     * next request hits the interceptor.
     */
    fun onHttpAcceptedForRequiresHttpAccept(host: String, serverUrl: String) {
        viewModelScope.launch {
            tokenManager.acceptHttpForHost(host)
            Log.d(TAG, "HTTP accepted (RequiresHttpAccept path) for host: $host")
            // Allow allowlist Flow to propagate to HttpAllowlistHolder's atomic cache.
            delay(50L)
            detectServerInternal(serverUrl)
        }
    }

    fun clearServerStatus() {
        detectionJob?.cancel()
        _serverStatus.update { ServerStatus.Idle }
        detectedServerUrl = null
        Log.d(TAG, "Status cleared to Idle")
    }

    private fun checkBiometricAvailability() {
        val hasCredentials = tokenManager.hasStoredCredentials()
        val biometricEnabled = tokenManager.isBiometricEnabledSync()
        val biometricAvailable = biometricHelper.isAvailable()
        _canUseBiometric.update { hasCredentials && biometricEnabled && biometricAvailable }
    }

    fun login(serverUrl: String, username: String, password: String) {
        if (serverUrl.isBlank() || username.isBlank() || password.isBlank()) {
            _uiState.update { LoginUiState.Error(context.getString(R.string.error_login_fields)) }
            return
        }

        // Check rate limit before attempting login
        if (!loginRateLimiter.isLoginAllowed()) {
            val state = loginRateLimiter.rateLimitState.value
            when (state) {
                is LoginRateLimitState.PermanentlyLocked -> {
                    _uiState.update {
                        LoginUiState.RateLimited(
                            message = context.getString(R.string.login_permanently_locked),
                            remainingMs = 0L,
                            isPermanent = true
                        )
                    }
                }
                is LoginRateLimitState.TemporarilyLocked -> {
                    _uiState.update {
                        LoginUiState.RateLimited(
                            message = context.getString(R.string.login_temporarily_locked),
                            remainingMs = state.remainingMs,
                            isPermanent = false
                        )
                    }
                }
                else -> {} // Should not happen
            }
            return
        }

        // Get URL from serverStatus (preferred), cached value, or use the passed serverUrl directly
        // The passed serverUrl is already validated when coming from ServerSetupScreen
        val urlToUse = when (val status = _serverStatus.value) {
            is ServerStatus.Success -> status.url
            else -> detectedServerUrl ?: serverUrl.trimEnd('/')
        }

        Log.d(TAG, "Starting login with URL: $urlToUse")
        analyticsService.trackEvent(AnalyticsEvent.LoginStarted)

        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                _uiState.update { LoginUiState.Loading }
            }

            authRepository.login(urlToUse, username, password)
                .onSuccess { result ->
                    // LoginResult only has Success now - MFA is handled as error
                    loginRateLimiter.recordSuccessfulLogin()
                    analyticsService.trackEvent(AnalyticsEvent.LoginSuccess("password"))
                    withContext(Dispatchers.Main) {
                        _uiState.update { LoginUiState.Success }
                    }
                }
                .onFailure { exception ->
                    // Only count auth errors (wrong credentials) for rate limiting
                    // Don't count network/SSL errors as they're not brute-force attempts
                    if (isAuthError(exception)) {
                        loginRateLimiter.recordFailedAttempt()
                    }
                    analyticsService.trackEvent(AnalyticsEvent.LoginFailed("auth_error"))
                    withContext(Dispatchers.Main) {
                        when {
                            // Issue #36: pin mismatch must be checked BEFORE isSslError —
                            // its message contains "certificate" and would otherwise be
                            // misrouted to the generic SSL-accept dialog.
                            exception is PaperlessException.CertificatePinMismatch -> {
                                _uiState.update {
                                    LoginUiState.CertChanged(
                                        host = exception.host,
                                        expectedPin = exception.expectedPin,
                                        actualPin = exception.actualPin
                                    )
                                }
                            }
                            isSslError(exception) -> {
                                val host = extractHostFromUrl(urlToUse)
                                _uiState.update {
                                    LoginUiState.SslError(
                                        host = host,
                                        message = exception.message ?: context.getString(R.string.error_ssl_certificate)
                                    )
                                }
                            }
                            else -> {
                                _uiState.update {
                                    LoginUiState.Error(
                                        exception.message ?: context.getString(R.string.error_login_failed)
                                    )
                                }
                            }
                        }
                    }
                }
        }
    }

    fun loginWithToken(serverUrl: String, token: String) {
        if (serverUrl.isBlank() || token.isBlank()) {
            _uiState.update { LoginUiState.Error(context.getString(R.string.error_login_fields)) }
            return
        }

        // Check rate limit before attempting login
        if (!loginRateLimiter.isLoginAllowed()) {
            val state = loginRateLimiter.rateLimitState.value
            when (state) {
                is LoginRateLimitState.PermanentlyLocked -> {
                    _uiState.update {
                        LoginUiState.RateLimited(
                            message = context.getString(R.string.login_permanently_locked),
                            remainingMs = 0L,
                            isPermanent = true
                        )
                    }
                }
                is LoginRateLimitState.TemporarilyLocked -> {
                    _uiState.update {
                        LoginUiState.RateLimited(
                            message = context.getString(R.string.login_temporarily_locked),
                            remainingMs = state.remainingMs,
                            isPermanent = false
                        )
                    }
                }
                else -> {} // Should not happen
            }
            return
        }

        // Get URL from serverStatus (preferred), cached value, or use the passed serverUrl directly
        val urlToUse = when (val status = _serverStatus.value) {
            is ServerStatus.Success -> status.url
            else -> detectedServerUrl ?: serverUrl.trimEnd('/')
        }

        Log.d(TAG, "Starting token login with URL: $urlToUse")
        analyticsService.trackEvent(AnalyticsEvent.LoginStarted)

        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                _uiState.update { LoginUiState.Loading }
            }

            authRepository.validateToken(urlToUse, token)
                .onSuccess {
                    loginRateLimiter.recordSuccessfulLogin()
                    tokenManager.saveCredentials(urlToUse, token)
                    analyticsService.trackEvent(AnalyticsEvent.LoginSuccess("token"))
                    withContext(Dispatchers.Main) {
                        _uiState.update { LoginUiState.Success }
                    }
                }
                .onFailure { exception ->
                    // Only count auth errors (invalid token) for rate limiting
                    if (isAuthError(exception)) {
                        loginRateLimiter.recordFailedAttempt()
                    }
                    analyticsService.trackEvent(AnalyticsEvent.LoginFailed("invalid_token"))
                    withContext(Dispatchers.Main) {
                        when {
                            // Issue #36: pin mismatch must be checked BEFORE isSslError.
                            exception is PaperlessException.CertificatePinMismatch -> {
                                _uiState.update {
                                    LoginUiState.CertChanged(
                                        host = exception.host,
                                        expectedPin = exception.expectedPin,
                                        actualPin = exception.actualPin
                                    )
                                }
                            }
                            isSslError(exception) -> {
                                val host = extractHostFromUrl(urlToUse)
                                _uiState.update {
                                    LoginUiState.SslError(
                                        host = host,
                                        message = exception.message ?: context.getString(R.string.error_ssl_certificate)
                                    )
                                }
                            }
                            else -> {
                                _uiState.update {
                                    LoginUiState.Error(
                                        exception.message ?: context.getString(R.string.error_token_invalid)
                                    )
                                }
                            }
                        }
                    }
                }
        }
    }

    fun onBiometricSuccess() {
        analyticsService.trackEvent(AnalyticsEvent.LoginSuccess("biometric"))
        _uiState.update { LoginUiState.Success }
    }

    fun onBiometricError(message: String) {
        analyticsService.trackEvent(AnalyticsEvent.LoginFailed("biometric_error"))
        _uiState.update { LoginUiState.Error(message) }
    }

    fun enableBiometric() {
        viewModelScope.launch {
            tokenManager.setBiometricEnabled(true)
            analyticsService.trackEvent(AnalyticsEvent.BiometricEnabled)
            checkBiometricAvailability()
        }
    }

    fun isBiometricAvailable(): Boolean = biometricHelper.isAvailable()

    fun resetState() {
        _uiState.update { LoginUiState.Idle }
    }

    fun acceptSslCertificate(host: String) {
        viewModelScope.launch {
            tokenManager.acceptSslForHost(host)
            Log.d(TAG, "SSL certificate accepted for host: $host")
            // Reset state to allow retry
            _uiState.update { LoginUiState.Idle }
        }
    }

    /**
     * User explicitly re-trusts a changed server certificate (Issue #36). Replaces
     * the stored pin with the newly observed one so the next connection succeeds,
     * then resets to Idle for the caller to retry. The new pin is read from
     * [ObservedCertHolder] (recorded by the interceptor at mismatch time) rather
     * than trusting the [actualPin] passed up through state, so we pin exactly the
     * certificate that triggered the dialog.
     */
    fun acceptCertificateChange(host: String) {
        val observed = observedCertHolder.consume(host)
        val newPin = observed?.actualPin
        if (newPin != null) {
            certificatePinStore.replacePin(host, newPin)
            Log.d(TAG, "Certificate change re-trusted for host: $host")
        } else {
            // No observed mismatch (e.g. process death): drop the stale pin so the
            // next connection re-captures via TOFU instead of blocking forever.
            certificatePinStore.removePin(host)
            Log.w(TAG, "No observed cert for $host on re-trust; cleared pin for TOFU re-capture")
        }
        _uiState.update { LoginUiState.Idle }
    }

    /**
     * Accepts insecure HTTP connection for a specific host.
     * This stores the preference so the user won't be asked again for this domain.
     */
    fun acceptHttpForHost(host: String) {
        viewModelScope.launch {
            tokenManager.acceptHttpForHost(host)
            Log.d(TAG, "HTTP fallback accepted for host: $host")
        }
    }

    /**
     * Checks if the user has already accepted HTTP for a host.
     */
    fun isHttpAcceptedForHost(host: String): Boolean {
        return tokenManager.isHostAcceptedForHttp(host)
    }

    private fun isSslError(exception: Throwable): Boolean {
        val message = exception.message?.lowercase() ?: ""
        return message.contains("ssl") ||
                message.contains("certificate") ||
                message.contains("zertifikat") ||
                exception is javax.net.ssl.SSLException ||
                exception is javax.net.ssl.SSLHandshakeException
    }

    /**
     * Checks if the exception is an authentication error (wrong credentials/token).
     * Network errors and SSL errors should NOT count towards rate limiting.
     */
    private fun isAuthError(exception: Throwable): Boolean {
        // Check for PaperlessException.AuthError
        if (exception is com.paperless.scanner.data.api.PaperlessException.AuthError) {
            return true
        }
        // Check for common auth error patterns in message (English - base language)
        val message = exception.message?.lowercase() ?: ""
        return message.contains("401") ||
                message.contains("403") ||
                message.contains("unauthorized") ||
                message.contains("forbidden") ||
                message.contains("invalid") ||
                message.contains("incorrect")
    }

    private fun extractHostFromUrl(url: String): String {
        return url
            .removePrefix("https://")
            .removePrefix("http://")
            .split("/").first()
            .split(":").first()
    }

    // ==================== Auth Debug Report Methods ====================

    /**
     * Observe if there's a debug report available (for showing debug button on error).
     */
    val hasAuthDebugReport = authDebugService.lastReport

    /**
     * Get a shareable debug report string for GitHub issues.
     */
    fun getShareableAuthDebugReport(): String {
        return authDebugService.createShareableReport()
    }

    /**
     * Clear the auth debug report after copying.
     */
    fun clearAuthDebugReport() {
        authDebugService.clearLastReport()
    }
}

sealed class LoginUiState {
    data object Idle : LoginUiState()
    data object Loading : LoginUiState()
    data object Success : LoginUiState()
    data class Error(val message: String) : LoginUiState()
    data class SslError(val host: String, val message: String) : LoginUiState()

    /**
     * The server's certificate changed since it was pinned on first contact
     * (Issue #36). Surfaces a blocking re-trust dialog showing the previously
     * trusted [expectedPin] vs the newly presented [actualPin].
     */
    data class CertChanged(
        val host: String,
        val expectedPin: String,
        val actualPin: String
    ) : LoginUiState()

    /**
     * Login is blocked due to too many failed attempts.
     * @param message User-facing message explaining the lockout
     * @param remainingMs Milliseconds until lockout expires (0 for permanent)
     * @param isPermanent True if this is a permanent lockout requiring app data clear
     */
    data class RateLimited(
        val message: String,
        val remainingMs: Long,
        val isPermanent: Boolean
    ) : LoginUiState()
}

sealed class ServerStatus {
    data object Idle : ServerStatus()
    data object Checking : ServerStatus()

    /**
     * Server successfully detected.
     * @param url The full URL with detected protocol
     * @param isHttps Whether HTTPS is used (secure connection)
     * @param isHttpFallback True if HTTPS was attempted but failed, and we fell back to HTTP
     *                       False if user explicitly entered http:// or if HTTPS was successful
     */
    data class Success(
        val url: String,
        val isHttps: Boolean,
        val isHttpFallback: Boolean = false
    ) : ServerStatus()

    /**
     * Issue #233: the app needs the user's explicit consent before sending
     * traffic over cleartext HTTP to this host. Triggered in two scenarios
     * distinguished by [reason]:
     *
     * - [Reason.USER_CHOSE_HTTP] — user typed an http:// URL for a non-
     *   loopback host they have not previously accepted. We surface the
     *   dialog BEFORE any network probe to avoid spurious SSL-handshake
     *   errors against plain-HTTP servers.
     * - [Reason.HTTPS_FAILED_HTTP_BLOCKED] — user typed no scheme; the
     *   HTTPS probe failed and the HTTP fallback was blocked by
     *   HttpAllowlistInterceptor (CleartextBlocked). The dialog asks the
     *   user to allow HTTP for this host.
     */
    data class RequiresHttpAccept(
        val host: String,
        val reason: Reason
    ) : ServerStatus() {
        enum class Reason { USER_CHOSE_HTTP, HTTPS_FAILED_HTTP_BLOCKED }
    }

    data class Error(val message: String) : ServerStatus()
}
