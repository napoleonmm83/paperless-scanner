package com.paperless.scanner.ui.screens.applock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.util.AppLockManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SetupAppLockViewModel @Inject constructor(
    private val appLockManager: AppLockManager
) : ViewModel() {

    private val _uiState = MutableStateFlow<SetupAppLockUiState>(SetupAppLockUiState.Idle)
    val uiState: StateFlow<SetupAppLockUiState> = _uiState.asStateFlow()

    companion object {
        private const val MIN_PASSWORD_LENGTH = 4
    }

    /**
     * Setup new app-lock password.
     * Validates password length and confirmation match.
     */
    fun setupPassword(password: String, confirmPassword: String) {
        viewModelScope.launch {
            _uiState.update { SetupAppLockUiState.Loading }

            // Validate password length
            if (password.length < MIN_PASSWORD_LENGTH) {
                _uiState.update {
                    SetupAppLockUiState.Error("Das Passwort muss mindestens $MIN_PASSWORD_LENGTH Zeichen lang sein")
                }
                return@launch
            }

            // Validate passwords match
            if (password != confirmPassword) {
                _uiState.update {
                    SetupAppLockUiState.Error("Die Passwörter stimmen nicht überein")
                }
                return@launch
            }

            try {
                appLockManager.setupAppLock(password)
                _uiState.update { SetupAppLockUiState.Success }
            } catch (e: Exception) {
                _uiState.update {
                    SetupAppLockUiState.Error("Fehler beim Einrichten: ${e.localizedMessage}")
                }
            }
        }
    }

    /**
     * Change existing app-lock password.
     * Validates current password, new password length, and confirmation match.
     */
    fun changePassword(currentPassword: String, newPassword: String, confirmPassword: String) {
        viewModelScope.launch {
            _uiState.update { SetupAppLockUiState.Loading }

            // Validate current password not empty
            if (currentPassword.isBlank()) {
                _uiState.update {
                    SetupAppLockUiState.Error("Bitte gib dein aktuelles Passwort ein")
                }
                return@launch
            }

            // Validate new password length
            if (newPassword.length < MIN_PASSWORD_LENGTH) {
                _uiState.update {
                    SetupAppLockUiState.Error("Das neue Passwort muss mindestens $MIN_PASSWORD_LENGTH Zeichen lang sein")
                }
                return@launch
            }

            // Validate passwords match
            if (newPassword != confirmPassword) {
                _uiState.update {
                    SetupAppLockUiState.Error("Die Passwörter stimmen nicht überein")
                }
                return@launch
            }

            try {
                val success = appLockManager.changePassword(currentPassword, newPassword)
                if (success) {
                    _uiState.update { SetupAppLockUiState.Success }
                } else {
                    _uiState.update {
                        SetupAppLockUiState.Error("Aktuelles Passwort ist falsch")
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    SetupAppLockUiState.Error("Fehler beim Ändern: ${e.localizedMessage}")
                }
            }
        }
    }
}

/**
 * UI State for Setup App-Lock Screen
 */
sealed class SetupAppLockUiState {
    data object Idle : SetupAppLockUiState()
    data object Loading : SetupAppLockUiState()
    data object Success : SetupAppLockUiState()
    data class Error(val message: String) : SetupAppLockUiState()
}
