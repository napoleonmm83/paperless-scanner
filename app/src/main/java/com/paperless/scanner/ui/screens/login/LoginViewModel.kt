package com.paperless.scanner.ui.screens.login

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.data.repository.AuthRepository
import com.paperless.scanner.util.BiometricHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class LoginViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val tokenManager: TokenManager,
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
            _serverStatus.value = ServerStatus.Idle
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
            _serverStatus.value = ServerStatus.Checking
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
                    _serverStatus.value = ServerStatus.Success(url, isHttps)
                    Log.d(TAG, "Status = Success, url = $url, isHttps = $isHttps")
                }
                .onFailure { exception ->
                    detectedServerUrl = null
                    val message = exception.message ?: "Server nicht erreichbar"
                    _serverStatus.value = ServerStatus.Error(message)
                    Log.d(TAG, "Status = Error, message = $message")
                }
        }
    }

    fun clearServerStatus() {
        detectionJob?.cancel()
        _serverStatus.value = ServerStatus.Idle
        detectedServerUrl = null
        Log.d(TAG, "Status cleared to Idle")
    }

    private fun checkBiometricAvailability() {
        val hasCredentials = tokenManager.hasStoredCredentials()
        val biometricEnabled = tokenManager.isBiometricEnabledSync()
        val biometricAvailable = biometricHelper.isAvailable()
        _canUseBiometric.value = hasCredentials && biometricEnabled && biometricAvailable
    }

    fun login(serverUrl: String, username: String, password: String) {
        if (serverUrl.isBlank() || username.isBlank() || password.isBlank()) {
            _uiState.value = LoginUiState.Error("Bitte alle Felder ausfüllen")
            return
        }

        // Get URL from serverStatus (preferred) or cached value
        val urlToUse = when (val status = _serverStatus.value) {
            is ServerStatus.Success -> status.url
            else -> detectedServerUrl
        }

        if (urlToUse == null) {
            Log.d(TAG, "No detected URL, triggering detection")
            onServerUrlChanged(serverUrl)
            return
        }

        Log.d(TAG, "Starting login with URL: $urlToUse")
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                _uiState.value = LoginUiState.Loading
            }

            authRepository.login(urlToUse, username, password)
                .onSuccess {
                    withContext(Dispatchers.Main) {
                        _uiState.value = LoginUiState.Success
                    }
                }
                .onFailure { exception ->
                    withContext(Dispatchers.Main) {
                        _uiState.value = LoginUiState.Error(
                            exception.message ?: "Login fehlgeschlagen"
                        )
                    }
                }
        }
    }

    fun loginWithToken(serverUrl: String, token: String) {
        if (serverUrl.isBlank() || token.isBlank()) {
            _uiState.value = LoginUiState.Error("Bitte alle Felder ausfüllen")
            return
        }

        // Get URL from serverStatus (preferred) or cached value
        val urlToUse = when (val status = _serverStatus.value) {
            is ServerStatus.Success -> status.url
            else -> detectedServerUrl
        }

        if (urlToUse == null) {
            Log.d(TAG, "No detected URL for token login, triggering detection")
            onServerUrlChanged(serverUrl)
            return
        }

        Log.d(TAG, "Starting token login with URL: $urlToUse")
        viewModelScope.launch(Dispatchers.IO) {
            withContext(Dispatchers.Main) {
                _uiState.value = LoginUiState.Loading
            }

            try {
                // Validate the token by trying to fetch something from the API
                authRepository.validateToken(urlToUse, token)
                    .onSuccess {
                        tokenManager.saveCredentials(urlToUse, token)
                        withContext(Dispatchers.Main) {
                            _uiState.value = LoginUiState.Success
                        }
                    }
                    .onFailure { exception ->
                        withContext(Dispatchers.Main) {
                            _uiState.value = LoginUiState.Error(
                                exception.message ?: "Token ungültig"
                            )
                        }
                    }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    _uiState.value = LoginUiState.Error(
                        e.message ?: "Verbindungsfehler"
                    )
                }
            }
        }
    }

    fun onBiometricSuccess() {
        _uiState.value = LoginUiState.Success
    }

    fun onBiometricError(message: String) {
        _uiState.value = LoginUiState.Error(message)
    }

    fun enableBiometric() {
        viewModelScope.launch {
            tokenManager.setBiometricEnabled(true)
            checkBiometricAvailability()
        }
    }

    fun isBiometricAvailable(): Boolean = biometricHelper.isAvailable()

    fun resetState() {
        _uiState.value = LoginUiState.Idle
    }
}

sealed class LoginUiState {
    data object Idle : LoginUiState()
    data object Loading : LoginUiState()
    data object Success : LoginUiState()
    data class Error(val message: String) : LoginUiState()
}

sealed class ServerStatus {
    data object Idle : ServerStatus()
    data object Checking : ServerStatus()
    data class Success(val url: String, val isHttps: Boolean) : ServerStatus()
    data class Error(val message: String) : ServerStatus()
}
