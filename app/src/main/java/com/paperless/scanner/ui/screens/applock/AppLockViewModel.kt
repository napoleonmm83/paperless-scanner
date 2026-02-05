package com.paperless.scanner.ui.screens.applock

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.R
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
                    is AppLockState.Unlocked -> {
                        _uiState.update { AppLockUiState.Unlocked }
                    }
                    is AppLockState.LockedOut -> {
                        val lockedOut = lockState as AppLockState.LockedOut
                        _uiState.update {
                            AppLockUiState.LockedOut(
                                isPermanent = lockedOut.isPermanent,
                                lockoutUntil = lockedOut.lockoutUntil,
                                refreshTimestamp = lockedOut.refreshTimestamp
                            )
                        }
                    }
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
                            message = context.getString(R.string.app_lock_error_wrong_password),
                            remainingAttempts = remainingAttempts
                        )
                    }
                } else {
                    // No remaining attempts - get lockout state from manager
                    val lockState = appLockManager.lockState.value
                    if (lockState is AppLockState.LockedOut) {
                        _uiState.update {
                            AppLockUiState.LockedOut(
                                isPermanent = lockState.isPermanent,
                                lockoutUntil = lockState.lockoutUntil,
                                refreshTimestamp = lockState.refreshTimestamp
                            )
                        }
                    } else {
                        // Edge case: remainingAttempts == 0 but lockState not yet updated
                        // Get lockout info directly from manager
                        val lockoutUntil = appLockManager.getLockoutUntil()
                        if (lockoutUntil > System.currentTimeMillis()) {
                            _uiState.update {
                                AppLockUiState.LockedOut(
                                    isPermanent = false,
                                    lockoutUntil = lockoutUntil
                                    // refreshTimestamp defaults to currentTimeMillis()
                                )
                            }
                        } else {
                            // Lockout expired or no lockout - show error and allow retry
                            _uiState.update {
                                AppLockUiState.Error(
                                    message = context.getString(R.string.app_lock_error_wrong_password),
                                    remainingAttempts = 5 // Reset to max for next cycle
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    fun showBiometricPrompt(activity: FragmentActivity) {
        biometricHelper.authenticate(
            activity = activity,
            title = context.getString(R.string.biometric_unlock_title),
            subtitle = context.getString(R.string.biometric_unlock_subtitle),
            negativeButtonText = context.getString(R.string.biometric_cancel),
            onSuccess = {
                appLockManager.unlockWithBiometric()
            },
            onError = { errorMessage ->
                // IMPORTANT: Preserve LockedOut state if we're in lockout!
                val lockState = appLockManager.lockState.value
                if (lockState is AppLockState.LockedOut && !lockState.isPermanent) {
                    // Keep LockedOut state - biometric error doesn't change lockout
                    _uiState.update {
                        AppLockUiState.LockedOut(
                            isPermanent = false,
                            lockoutUntil = lockState.lockoutUntil,
                            refreshTimestamp = lockState.refreshTimestamp
                        )
                    }
                } else {
                    _uiState.update {
                        AppLockUiState.Error(
                            message = errorMessage,
                            remainingAttempts = appLockManager.getRemainingAttempts()
                        )
                    }
                }
            },
            onFallback = {
                // User cancelled or pressed negative button
                // IMPORTANT: Preserve LockedOut state if we're in lockout!
                val lockState = appLockManager.lockState.value
                if (lockState is AppLockState.LockedOut && !lockState.isPermanent) {
                    // Keep LockedOut state - user is still locked out
                    _uiState.update {
                        AppLockUiState.LockedOut(
                            isPermanent = false,
                            lockoutUntil = lockState.lockoutUntil,
                            refreshTimestamp = lockState.refreshTimestamp
                        )
                    }
                } else {
                    _uiState.update { AppLockUiState.Idle }
                }
            }
        )
    }

    /**
     * Refresh lockout state - called when countdown timer reaches 0.
     * Triggers state transition from LockedOut back to Locked/Idle.
     */
    fun refreshLockoutState() {
        appLockManager.refreshLockoutState()
    }

    /**
     * Get remaining lockout time in seconds.
     * More reliable than computing from lockoutUntil timestamp.
     */
    fun getRemainingLockoutSeconds(): Int {
        return appLockManager.getRemainingLockoutSeconds()
    }
}

sealed class AppLockUiState {
    data object Idle : AppLockUiState()
    data object Unlocking : AppLockUiState()
    data object Unlocked : AppLockUiState()
    data class LockedOut(
        val isPermanent: Boolean,
        val lockoutUntil: Long,
        val refreshTimestamp: Long = System.currentTimeMillis()
    ) : AppLockUiState()
    data class Error(
        val message: String,
        val remainingAttempts: Int
    ) : AppLockUiState()
}
