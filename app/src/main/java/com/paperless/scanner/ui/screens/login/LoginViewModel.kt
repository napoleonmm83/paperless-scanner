package com.paperless.scanner.ui.screens.login

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.data.repository.AuthRepository
import com.paperless.scanner.util.BiometricHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    init {
        checkBiometricAvailability()
    }

    /**
     * Detect server protocol - called automatically after URL input with debounce
     */
    fun detectServer(serverUrl: String) {
        if (serverUrl.isBlank() || serverUrl.length < 4) {
            _serverStatus.value = ServerStatus.Idle
            detectedServerUrl = null
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _serverStatus.value = ServerStatus.Checking

            authRepository.detectServerProtocol(serverUrl)
                .onSuccess { url ->
                    val isHttps = url.startsWith("https://")
                    detectedServerUrl = url
                    _serverStatus.value = ServerStatus.Success(url, isHttps)
                }
                .onFailure { exception ->
                    detectedServerUrl = null
                    _serverStatus.value = ServerStatus.Error(
                        exception.message ?: "Server nicht erreichbar"
                    )
                }
        }
    }

    fun clearServerStatus() {
        _serverStatus.value = ServerStatus.Idle
        detectedServerUrl = null
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
            // Don't show snackbar error - the URL field already shows the status
            // Just trigger a new detection
            detectServer(serverUrl)
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = LoginUiState.Loading

            authRepository.login(urlToUse, username, password)
                .onSuccess {
                    _uiState.value = LoginUiState.Success
                }
                .onFailure { exception ->
                    _uiState.value = LoginUiState.Error(
                        exception.message ?: "Login fehlgeschlagen"
                    )
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
            // Don't show snackbar error - trigger detection instead
            detectServer(serverUrl)
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = LoginUiState.Loading

            try {
                // Validate the token by trying to fetch something from the API
                authRepository.validateToken(urlToUse, token)
                    .onSuccess {
                        tokenManager.saveCredentials(urlToUse, token)
                        _uiState.value = LoginUiState.Success
                    }
                    .onFailure { exception ->
                        _uiState.value = LoginUiState.Error(
                            exception.message ?: "Token ungültig"
                        )
                    }
            } catch (e: Exception) {
                _uiState.value = LoginUiState.Error(
                    e.message ?: "Verbindungsfehler"
                )
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
