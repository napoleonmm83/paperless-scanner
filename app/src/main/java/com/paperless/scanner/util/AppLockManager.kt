package com.paperless.scanner.util

import android.content.Context
import android.util.Log
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
    private var lockoutUntil: Long = 0L

    companion object {
        private const val TAG = "AppLockManager"
        private const val BCRYPT_ROUNDS = 10
        private const val MAX_FAILED_ATTEMPTS = 5
        private const val LOCKOUT_DURATION_MILLIS = 30 * 60 * 1000L // 30 minutes
        private const val MAX_TOTAL_ATTEMPTS = 15 // After this, permanent logout
    }

    init {
        Log.d(TAG, "AppLockManager init - Registering lifecycle observer")
        // Register lifecycle observer
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)

        // Check if app should be locked on startup
        scope.launch {
            if (shouldLock()) {
                Log.d(TAG, "Init: App-lock enabled, setting to Locked immediately")
                _lockState.update { AppLockState.Locked() }
            } else {
                Log.d(TAG, "Init: App-lock disabled or not logged in, starting Unlocked")
            }
        }

        // Observe changes to app-lock settings
        scope.launch {
            tokenManager.isAppLockEnabled().collect { isEnabled ->
                Log.d(TAG, "AppLockEnabled changed: $isEnabled, current lockState=${_lockState.value}")
                if (!isEnabled) {
                    // If app-lock is disabled, unlock immediately
                    // ONLY change state if not already unlocked to avoid unnecessary state updates
                    if (_lockState.value !is AppLockState.Unlocked) {
                        Log.d(TAG, "App-lock disabled, changing state from ${_lockState.value} to Unlocked")
                        _lockState.update { AppLockState.Unlocked }
                    } else {
                        Log.d(TAG, "App-lock disabled but already Unlocked, no state change needed")
                    }
                }
                // If enabled, let onStart() handle the locking
            }
        }
    }

    /**
     * Set up app-lock with a new password.
     * Hashes password with BCrypt and stores hash in DataStore.
     * NOTE: Does NOT change lock state - called from Settings, not from Lock Screen
     */
    suspend fun setupAppLock(password: String) {
        Log.d(TAG, "setupAppLock: Starting setup, current lockState=${_lockState.value}")
        val passwordHash = BCrypt.hashpw(password, BCrypt.gensalt(BCRYPT_ROUNDS))
        tokenManager.setAppLockPassword(passwordHash)
        tokenManager.setAppLockEnabled(true)
        failedAttempts = 0
        lockoutUntil = 0L
        // Initialize timestamp so timeout checks work correctly after setup
        backgroundTimestamp = System.currentTimeMillis()
        // DON'T change lock state here - we're in Settings, not on Lock Screen
        // Changing state would trigger AppLockNavigationInterceptor race condition
        Log.d(TAG, "setupAppLock: Setup complete, lockState remains ${_lockState.value}")
    }

    /**
     * Unlock app with password.
     * Returns true if password is correct, false otherwise.
     */
    suspend fun unlockWithPassword(password: String): Boolean {
        // Check if currently in temporary lockout
        if (isInTemporaryLockout()) {
            Log.d(TAG, "unlockWithPassword: Currently in temporary lockout until $lockoutUntil")
            return false
        }

        val storedHash = tokenManager.getAppLockPasswordHash() ?: return false

        return try {
            val isValid = BCrypt.checkpw(password, storedHash)
            if (isValid) {
                // Reset counters on successful unlock
                failedAttempts = 0
                lockoutUntil = 0L
                // Reset timestamp to current time so next lock check works correctly
                backgroundTimestamp = System.currentTimeMillis()
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
     * NOTE: Biometric works even during temporary lockout (only PIN is blocked)
     */
    fun unlockWithBiometric() {
        scope.launch {
            if (tokenManager.isAppLockBiometricEnabled()) {
                // Reset all counters on successful biometric unlock
                failedAttempts = 0
                lockoutUntil = 0L
                // Reset timestamp to current time so next lock check works correctly
                backgroundTimestamp = System.currentTimeMillis()
                _lockState.update { AppLockState.Unlocked }
                Log.d(TAG, "unlockWithBiometric: Success, counters reset")
            }
        }
    }

    /**
     * Lock the app immediately.
     */
    fun lock() {
        _lockState.update { AppLockState.Locked() }
    }

    /**
     * Disable app-lock completely.
     * NOTE: Does NOT change lock state - the isAppLockEnabled() observer will handle that
     */
    suspend fun disableAppLock() {
        Log.d(TAG, "disableAppLock: Disabling app-lock, current state=${_lockState.value}")
        tokenManager.setAppLockEnabled(false)
        tokenManager.setAppLockPassword(null)
        tokenManager.setAppLockBiometricEnabled(false)
        failedAttempts = 0
        lockoutUntil = 0L
        // DON'T change lock state here - the isAppLockEnabled() observer in init will handle it
        // This avoids double state updates that could trigger unwanted navigation
        Log.d(TAG, "disableAppLock: Complete, state remains ${_lockState.value} (will be changed by observer)")
    }

    /**
     * Change app-lock password.
     * Requires current password for verification.
     * NOTE: Does NOT change lock state - called from Settings, not from Lock Screen
     */
    suspend fun changePassword(currentPassword: String, newPassword: String): Boolean {
        val storedHash = tokenManager.getAppLockPasswordHash() ?: return false

        return try {
            if (BCrypt.checkpw(currentPassword, storedHash)) {
                val newHash = BCrypt.hashpw(newPassword, BCrypt.gensalt(BCRYPT_ROUNDS))
                tokenManager.setAppLockPassword(newHash)
                failedAttempts = 0
                lockoutUntil = 0L
                // DON'T change lock state here - we're in Settings, not on Lock Screen
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
     * Get remaining attempts before temporary lockout.
     */
    fun getRemainingAttempts(): Int {
        // If in temporary lockout, check if it expired
        if (isInTemporaryLockout()) {
            return 0
        }
        return maxOf(0, MAX_FAILED_ATTEMPTS - (failedAttempts % MAX_FAILED_ATTEMPTS))
    }

    /**
     * Get lockout end time (0 if not locked out).
     */
    fun getLockoutUntil(): Long = lockoutUntil

    /**
     * Check if currently in temporary lockout period.
     */
    fun isInTemporaryLockout(): Boolean {
        if (lockoutUntil == 0L) return false

        val now = System.currentTimeMillis()
        if (now >= lockoutUntil) {
            // Lockout expired, reset
            lockoutUntil = 0L
            // Keep failed attempts count but allow new attempts
            return false
        }
        return true
    }

    private suspend fun handleFailedAttempt() {
        failedAttempts++
        Log.d(TAG, "handleFailedAttempt: Total failed attempts: $failedAttempts")

        when {
            // After 15 total failed attempts: Permanent lockout (logout)
            failedAttempts >= MAX_TOTAL_ATTEMPTS -> {
                Log.w(TAG, "MAX_TOTAL_ATTEMPTS reached ($MAX_TOTAL_ATTEMPTS) - Permanent lockout")
                tokenManager.clearCredentials()
                disableAppLock()
                _lockState.update { AppLockState.LockedOut(isPermanent = true, lockoutUntil = 0L) }
            }
            // Every 5 failed attempts: Temporary lockout (30 minutes)
            failedAttempts % MAX_FAILED_ATTEMPTS == 0 -> {
                lockoutUntil = System.currentTimeMillis() + LOCKOUT_DURATION_MILLIS
                Log.w(TAG, "MAX_FAILED_ATTEMPTS reached (${failedAttempts / MAX_FAILED_ATTEMPTS}x) - Temporary lockout until $lockoutUntil")
                _lockState.update { AppLockState.LockedOut(isPermanent = false, lockoutUntil = lockoutUntil) }
            }
            // Continue allowing attempts
            else -> {
                Log.d(TAG, "Failed attempt $failedAttempts, ${getRemainingAttempts()} remaining")
            }
        }
    }

    // Lifecycle callbacks

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        // App moved to background
        backgroundTimestamp = System.currentTimeMillis()
        Log.d(TAG, "onStop: App moved to background, timestamp=$backgroundTimestamp")

        // IMPORTANT: Set to Unlocked so onStart() will trigger a state change
        // This ensures LaunchedEffect(lockState) in Compose always fires
        scope.launch {
            if (shouldLock()) {
                Log.d(TAG, "onStop: Setting to Unlocked (will be locked by onStart if timeout elapsed)")
                _lockState.update { AppLockState.Unlocked }
            }
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        Log.d(TAG, "onStart: App moved to foreground")
        // App moved to foreground - check if we should lock
        scope.launch {
            val shouldLock = shouldLock()
            val currentState = _lockState.value
            Log.d(TAG, "onStart: shouldLock=$shouldLock, currentState=$currentState, backgroundTimestamp=$backgroundTimestamp")

            if (shouldLock) {
                // If backgroundTimestamp is 0, this is the first start (or after cold boot)
                // In this case, lock immediately regardless of timeout
                if (backgroundTimestamp == 0L) {
                    Log.d(TAG, "onStart: First start or cold boot, locking immediately")
                    _lockState.update { AppLockState.Locked() }
                } else {
                    // Check if timeout has elapsed
                    val timeoutMillis = getTimeoutMillis()
                    val elapsed = System.currentTimeMillis() - backgroundTimestamp
                    Log.d(TAG, "onStart: elapsed=$elapsed ms, timeout=$timeoutMillis ms")

                    if (elapsed >= timeoutMillis) {
                        Log.d(TAG, "onStart: Timeout elapsed, locking")
                        _lockState.update { AppLockState.Locked() }
                    } else {
                        Log.d(TAG, "onStart: Timeout not elapsed, staying unlocked")
                    }
                }
            } else {
                Log.d(TAG, "onStart: shouldLock=false, keeping current state")
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
 *
 * SECURITY: Using immutable data classes with timestamp ensures every lock event
 * triggers a state change, preventing any edge cases where Compose wouldn't react.
 */
sealed class AppLockState {
    data object Unlocked : AppLockState()

    /**
     * Locked state with timestamp.
     * Each lock event creates a NEW instance, ensuring Compose always detects the change.
     */
    data class Locked(val timestamp: Long = System.currentTimeMillis()) : AppLockState()

    /**
     * Locked out state after too many failed attempts.
     * @param isPermanent true = logout required, false = temporary 30-min lockout
     * @param lockoutUntil Unix timestamp when lockout ends (0 for permanent)
     */
    data class LockedOut(
        val isPermanent: Boolean,
        val lockoutUntil: Long
    ) : AppLockState()
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
