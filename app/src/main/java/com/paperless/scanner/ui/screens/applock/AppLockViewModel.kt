package com.paperless.scanner.ui.screens.applock

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.util.AppLockManager
import com.paperless.scanner.util.AppLockState
import com.paperless.scanner.util.BiometricHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AppLockViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appLockManager: AppLockManager,
    private val biometricHelper: BiometricHelper
) : ViewModel() {

    private val _uiState = MutableStateFlow<AppLockUiState>(AppLockUiState.Idle)
    val uiState: StateFlow<AppLockUiState> = _uiState.asStateFlow()

    private val _canUseBiometric = MutableStateFlow(false)
    val canUseBiometric: StateFlow<Boolean> = _canUseBiometric.asStateFlow()

    init {
        checkBiometricAvailability()
        observeLockState()
    }

    private fun observeLockState() {
        viewModelScope.launch {
            appLockManager.lockState.collect { lockState ->
                when (lockState) {
                    is AppLockState.Unlocked -> _uiState.update { AppLockUiState.Unlocked }
                    is AppLockState.LockedOut -> _uiState.update { AppLockUiState.LockedOut }
                    is AppLockState.Locked -> {
                        // Keep current UI state (Idle, Error, etc.)
                        if (_uiState.value is AppLockUiState.Unlocked ||
                            _uiState.value is AppLockUiState.LockedOut
                        ) {
                            _uiState.update { AppLockUiState.Idle }
                        }
                    }
                }
            }
        }
    }

    private fun checkBiometricAvailability() {
        viewModelScope.launch {
            val biometricEnabled = com.paperless.scanner.data.datastore.TokenManager(context)
                .isAppLockBiometricEnabled()
            val biometricAvailable = biometricHelper.isAvailable()
            _canUseBiometric.update { biometricEnabled && biometricAvailable }
        }
    }

    fun unlockWithPassword(password: String) {
        if (password.isBlank()) return

        viewModelScope.launch {
            _uiState.update { AppLockUiState.Unlocking }

            val success = appLockManager.unlockWithPassword(password)

            if (success) {
                _uiState.update { AppLockUiState.Unlocked }
            } else {
                val remainingAttempts = appLockManager.getRemainingAttempts()
                if (remainingAttempts > 0) {
                    _uiState.update {
                        AppLockUiState.Error(
                            message = "Falsches Passwort",
                            remainingAttempts = remainingAttempts
                        )
                    }
                } else {
                    _uiState.update { AppLockUiState.LockedOut }
                }
            }
        }
    }

    fun showBiometricPrompt() {
        val activity = context as? FragmentActivity ?: return

        biometricHelper.authenticate(
            activity = activity,
            title = "App entsperren",
            subtitle = "Verwende deine Biometrie",
            negativeButtonText = "Abbrechen",
            onSuccess = {
                appLockManager.unlockWithBiometric()
            },
            onError = { errorMessage ->
                _uiState.update {
                    AppLockUiState.Error(
                        message = errorMessage,
                        remainingAttempts = appLockManager.getRemainingAttempts()
                    )
                }
            },
            onFallback = {
                // User cancelled or pressed negative button
                _uiState.update { AppLockUiState.Idle }
            }
        )
    }
}

sealed class AppLockUiState {
    data object Idle : AppLockUiState()
    data object Unlocking : AppLockUiState()
    data object Unlocked : AppLockUiState()
    data object LockedOut : AppLockUiState()
    data class Error(
        val message: String,
        val remainingAttempts: Int
    ) : AppLockUiState()
}
