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

    init {
        checkBiometricAvailability()
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

        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = LoginUiState.Loading

            authRepository.login(serverUrl, username, password)
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
