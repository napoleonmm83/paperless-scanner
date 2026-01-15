package com.paperless.scanner.ui.screens.login

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.R
import com.paperless.scanner.data.analytics.AnalyticsEvent
import com.paperless.scanner.data.analytics.AnalyticsService
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.data.repository.AuthRepository
import com.paperless.scanner.util.BiometricHelper
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
    val biometricHelper: BiometricHelper
) : ViewModel() {

    private val _uiState = MutableStateFlow<LoginUiState>(LoginUiState.Idle)
    val uiState: StateFlow<LoginUiState> = _uiState.asStateFlow()

    private val _canUseBiometric = MutableStateFlow(false)
    val canUseBiometric: StateFlow<Boolean> = _canUseBiometric.asStateFlow()

    private val _serverStatus = MutableStateFlow<ServerStatus>(ServerStatus.Idle)
    val serverStatus: StateFlow<ServerStatus> = _serverStatus.asStateFlow()

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

        if (serverUrl.isBlank() || serverUrl.length < 4) {
            _serverStatus.update { ServerStatus.Idle }
            detectedServerUrl = null
            Log.d(TAG, "URL too short, status = Idle")
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
                    detectedServerUrl = url
                    _serverStatus.update { ServerStatus.Success(url, isHttps) }
                    Log.d(TAG, "Status = Success, url = $url, isHttps = $isHttps")
                }
                .onFailure { exception ->
                    detectedServerUrl = null
                    val message = exception.message ?: context.getString(R.string.error_server_unreachable)
                    _serverStatus.update { ServerStatus.Error(message) }
                    Log.d(TAG, "Status = Error, message = $message")
                }
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
                .onSuccess {
                    analyticsService.trackEvent(AnalyticsEvent.LoginSuccess("password"))
                    withContext(Dispatchers.Main) {
                        _uiState.update { LoginUiState.Success }
                    }
                }
                .onFailure { exception ->
                    analyticsService.trackEvent(AnalyticsEvent.LoginFailed("auth_error"))
                    withContext(Dispatchers.Main) {
                        // Check if it's an SSL error
                        if (isSslError(exception)) {
                            val host = extractHostFromUrl(urlToUse)
                            _uiState.update {
                                LoginUiState.SslError(
                                    host = host,
                                    message = exception.message ?: context.getString(R.string.error_ssl_certificate)
                                )
                            }
                        } else {
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

    fun loginWithToken(serverUrl: String, token: String) {
        if (serverUrl.isBlank() || token.isBlank()) {
            _uiState.update { LoginUiState.Error(context.getString(R.string.error_login_fields)) }
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
                    tokenManager.saveCredentials(urlToUse, token)
                    analyticsService.trackEvent(AnalyticsEvent.LoginSuccess("token"))
                    withContext(Dispatchers.Main) {
                        _uiState.update { LoginUiState.Success }
                    }
                }
                .onFailure { exception ->
                    analyticsService.trackEvent(AnalyticsEvent.LoginFailed("invalid_token"))
                    withContext(Dispatchers.Main) {
                        // Check if it's an SSL error
                        if (isSslError(exception)) {
                            val host = extractHostFromUrl(urlToUse)
                            _uiState.update {
                                LoginUiState.SslError(
                                    host = host,
                                    message = exception.message ?: context.getString(R.string.error_ssl_certificate)
                                )
                            }
                        } else {
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

    private fun isSslError(exception: Throwable): Boolean {
        val message = exception.message?.lowercase() ?: ""
        return message.contains("ssl") ||
                message.contains("certificate") ||
                message.contains("zertifikat") ||
                exception is javax.net.ssl.SSLException ||
                exception is javax.net.ssl.SSLHandshakeException
    }

    private fun extractHostFromUrl(url: String): String {
        return url
            .removePrefix("https://")
            .removePrefix("http://")
            .split("/").first()
            .split(":").first()
    }
}

sealed class LoginUiState {
    data object Idle : LoginUiState()
    data object Loading : LoginUiState()
    data object Success : LoginUiState()
    data class Error(val message: String) : LoginUiState()
    data class SslError(val host: String, val message: String) : LoginUiState()
}

sealed class ServerStatus {
    data object Idle : ServerStatus()
    data object Checking : ServerStatus()
    data class Success(val url: String, val isHttps: Boolean) : ServerStatus()
    data class Error(val message: String) : ServerStatus()
}
