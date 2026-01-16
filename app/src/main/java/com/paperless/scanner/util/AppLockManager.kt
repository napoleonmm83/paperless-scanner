package com.paperless.scanner.util

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.Lifecycle
import com.paperless.scanner.data.datastore.TokenManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.mindrot.jbcrypt.BCrypt
import javax.inject.Inject
import javax.inject.Singleton

/**
 * App-Lock Manager
 *
 * Manages app-wide lock state with password and/or biometric protection.
 * Features:
 * - Session-based locking (only when user is logged in)
 * - Configurable timeout (immediate, 1min, 5min, 15min, 30min)
 * - BCrypt password hashing
 * - Biometric unlock support
 * - Fail-safe: 5 wrong attempts → logout + disable app-lock
 * - Lifecycle-aware: locks on background after timeout
 */
@Singleton
class AppLockManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tokenManager: TokenManager
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _lockState = MutableStateFlow<AppLockState>(AppLockState.Unlocked)
    val lockState: StateFlow<AppLockState> = _lockState.asStateFlow()

    private var backgroundTimestamp: Long = 0L
    private var failedAttempts: Int = 0

    companion object {
        private const val BCRYPT_ROUNDS = 10
        private const val MAX_FAILED_ATTEMPTS = 5
    }

    init {
        // Register lifecycle observer
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        // Initialize lock state based on settings
        scope.launch {
            tokenManager.isAppLockEnabled().collect { enabled ->
                if (enabled && tokenManager.hasStoredCredentials()) {
                    // Lock immediately if app-lock is enabled and user is logged in
                    _lockState.update { AppLockState.Locked }
                } else {
                    _lockState.update { AppLockState.Unlocked }
                }
            }
        }
    }

    /**
     * Set up app-lock with a new password.
     * Hashes password with BCrypt and stores hash in DataStore.
     */
    suspend fun setupAppLock(password: String) {
        val passwordHash = BCrypt.hashpw(password, BCrypt.gensalt(BCRYPT_ROUNDS))
        tokenManager.setAppLockPassword(passwordHash)
        tokenManager.setAppLockEnabled(true)
        failedAttempts = 0
        _lockState.update { AppLockState.Unlocked }
    }

    /**
     * Unlock app with password.
     * Returns true if password is correct, false otherwise.
     */
    suspend fun unlockWithPassword(password: String): Boolean {
        val storedHash = tokenManager.getAppLockPasswordHash() ?: return false

        return try {
            val isValid = BCrypt.checkpw(password, storedHash)
            if (isValid) {
                failedAttempts = 0
                _lockState.update { AppLockState.Unlocked }
                true
            } else {
                handleFailedAttempt()
                false
            }
        } catch (e: Exception) {
            // Invalid hash format or BCrypt error
            handleFailedAttempt()
            false
        }
    }

    /**
     * Unlock app with biometric authentication.
     * Should only be called after successful biometric authentication.
     */
    fun unlockWithBiometric() {
        scope.launch {
            if (tokenManager.isAppLockBiometricEnabled()) {
                failedAttempts = 0
                _lockState.update { AppLockState.Unlocked }
            }
        }
    }

    /**
     * Lock the app immediately.
     */
    fun lock() {
        _lockState.update { AppLockState.Locked }
    }

    /**
     * Disable app-lock completely.
     */
    suspend fun disableAppLock() {
        tokenManager.setAppLockEnabled(false)
        tokenManager.setAppLockPassword(null)
        tokenManager.setAppLockBiometricEnabled(false)
        failedAttempts = 0
        _lockState.update { AppLockState.Unlocked }
    }

    /**
     * Change app-lock password.
     * Requires current password for verification.
     */
    suspend fun changePassword(currentPassword: String, newPassword: String): Boolean {
        val storedHash = tokenManager.getAppLockPasswordHash() ?: return false

        return try {
            if (BCrypt.checkpw(currentPassword, storedHash)) {
                val newHash = BCrypt.hashpw(newPassword, BCrypt.gensalt(BCRYPT_ROUNDS))
                tokenManager.setAppLockPassword(newHash)
                failedAttempts = 0
                true
            } else {
                handleFailedAttempt()
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Check if app-lock should be active.
     * Returns true if user is logged in and app-lock is enabled.
     */
    suspend fun shouldLock(): Boolean {
        return tokenManager.hasStoredCredentials() && tokenManager.isAppLockEnabledSync()
    }

    /**
     * Get current failed attempts count.
     */
    fun getFailedAttempts(): Int = failedAttempts

    /**
     * Get remaining attempts before lockout.
     */
    fun getRemainingAttempts(): Int = maxOf(0, MAX_FAILED_ATTEMPTS - failedAttempts)

    private suspend fun handleFailedAttempt() {
        failedAttempts++
        if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
            // Fail-safe: logout and disable app-lock
            tokenManager.clearCredentials()
            disableAppLock()
            _lockState.update { AppLockState.LockedOut }
        }
    }

    // Lifecycle callbacks

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        // App moved to background
        backgroundTimestamp = System.currentTimeMillis()
    }

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        // App moved to foreground
        scope.launch {
            if (shouldLock()) {
                val timeoutMillis = getTimeoutMillis()
                val elapsed = System.currentTimeMillis() - backgroundTimestamp

                if (elapsed >= timeoutMillis) {
                    _lockState.update { AppLockState.Locked }
                }
            }
        }
    }

    private suspend fun getTimeoutMillis(): Long {
        return when (tokenManager.getAppLockTimeout()) {
            AppLockTimeout.IMMEDIATE -> 0L
            AppLockTimeout.ONE_MINUTE -> 60_000L
            AppLockTimeout.FIVE_MINUTES -> 300_000L
            AppLockTimeout.FIFTEEN_MINUTES -> 900_000L
            AppLockTimeout.THIRTY_MINUTES -> 1_800_000L
        }
    }
}

/**
 * App-Lock State
 */
sealed class AppLockState {
    data object Unlocked : AppLockState()
    data object Locked : AppLockState()
    data object LockedOut : AppLockState()  // After 5 failed attempts
}

/**
 * App-Lock Timeout Options
 */
enum class AppLockTimeout(val displayName: String) {
    IMMEDIATE("Sofort"),
    ONE_MINUTE("1 Minute"),
    FIVE_MINUTES("5 Minuten"),
    FIFTEEN_MINUTES("15 Minuten"),
    THIRTY_MINUTES("30 Minuten")
}
