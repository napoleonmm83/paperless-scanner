package com.paperless.scanner.util

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

private val Context.loginRateLimitDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "login_rate_limit"
)

/**
 * Login Rate Limiter - Prevents brute-force attacks on login credentials.
 *
 * Security Features:
 * - Tracks failed login attempts (persisted across app restarts)
 * - Exponential lockout: 5 failures → 5 min, 10 failures → 15 min, 15 failures → 30 min
 * - Maximum 20 total attempts before requiring app data clear
 * - Resets on successful login
 * - Provides real-time lockout state via StateFlow
 *
 * Lockout Schedule:
 * - 5 failed attempts: 5 minute lockout
 * - 10 failed attempts: 15 minute lockout
 * - 15 failed attempts: 30 minute lockout
 * - 20 failed attempts: Permanent lockout (clear app data required)
 */
@Singleton
class LoginRateLimiter @Inject constructor(
    private val context: Context
) {
    companion object {
        private const val TAG = "LoginRateLimiter"

        // DataStore keys
        private val FAILED_ATTEMPTS_KEY = intPreferencesKey("login_failed_attempts")
        private val LOCKOUT_UNTIL_KEY = longPreferencesKey("login_lockout_until")

        // Lockout thresholds and durations
        private const val FIRST_LOCKOUT_THRESHOLD = 5
        private const val SECOND_LOCKOUT_THRESHOLD = 10
        private const val THIRD_LOCKOUT_THRESHOLD = 15
        private const val PERMANENT_LOCKOUT_THRESHOLD = 20

        private const val FIRST_LOCKOUT_DURATION_MS = 5 * 60 * 1000L    // 5 minutes
        private const val SECOND_LOCKOUT_DURATION_MS = 15 * 60 * 1000L  // 15 minutes
        private const val THIRD_LOCKOUT_DURATION_MS = 30 * 60 * 1000L   // 30 minutes
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _rateLimitState = MutableStateFlow<LoginRateLimitState>(LoginRateLimitState.Ready())
    val rateLimitState: StateFlow<LoginRateLimitState> = _rateLimitState.asStateFlow()

    private var failedAttempts: Int = 0
    private var lockoutUntil: Long = 0L

    init {
        // Load persisted state SYNCHRONOUSLY on initialization
        // This is critical for security - must block to ensure lockout state is ready
        // before any isLoginAllowed() check can happen
        runBlocking {
            loadPersistedState()
        }
        updateState()
    }

    /**
     * Loads persisted lockout state from DataStore.
     */
    private suspend fun loadPersistedState() {
        try {
            val prefs = context.loginRateLimitDataStore.data.first()
            failedAttempts = prefs[FAILED_ATTEMPTS_KEY] ?: 0
            lockoutUntil = prefs[LOCKOUT_UNTIL_KEY] ?: 0L
            Log.d(TAG, "Loaded persisted state: attempts=$failedAttempts, lockoutUntil=$lockoutUntil")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load persisted state", e)
        }
    }

    /**
     * Persists current state to DataStore.
     */
    private suspend fun persistState() {
        try {
            context.loginRateLimitDataStore.edit { prefs ->
                prefs[FAILED_ATTEMPTS_KEY] = failedAttempts
                prefs[LOCKOUT_UNTIL_KEY] = lockoutUntil
            }
            Log.d(TAG, "Persisted state: attempts=$failedAttempts, lockoutUntil=$lockoutUntil")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist state", e)
        }
    }

    /**
     * Updates the StateFlow based on current state.
     */
    private fun updateState() {
        val now = System.currentTimeMillis()

        // Check for permanent lockout
        if (failedAttempts >= PERMANENT_LOCKOUT_THRESHOLD) {
            _rateLimitState.update { LoginRateLimitState.PermanentlyLocked }
            return
        }

        // Check for temporary lockout
        if (lockoutUntil > now) {
            val remainingMs = lockoutUntil - now
            _rateLimitState.update {
                LoginRateLimitState.TemporarilyLocked(
                    lockoutUntilMs = lockoutUntil,
                    remainingMs = remainingMs,
                    failedAttempts = failedAttempts
                )
            }
            return
        }

        // Lockout expired or no lockout
        if (lockoutUntil > 0 && lockoutUntil <= now) {
            // Lockout just expired
            lockoutUntil = 0L
            scope.launch { persistState() }
        }

        // Calculate remaining attempts before next lockout
        val nextThreshold = when {
            failedAttempts < FIRST_LOCKOUT_THRESHOLD -> FIRST_LOCKOUT_THRESHOLD
            failedAttempts < SECOND_LOCKOUT_THRESHOLD -> SECOND_LOCKOUT_THRESHOLD
            failedAttempts < THIRD_LOCKOUT_THRESHOLD -> THIRD_LOCKOUT_THRESHOLD
            else -> PERMANENT_LOCKOUT_THRESHOLD
        }
        val remainingAttempts = nextThreshold - failedAttempts

        _rateLimitState.update {
            LoginRateLimitState.Ready(
                failedAttempts = failedAttempts,
                remainingAttempts = remainingAttempts
            )
        }
    }

    /**
     * Checks if login is currently allowed.
     * Also refreshes the state in case lockout has expired.
     *
     * @return true if login attempt is allowed, false if locked out
     */
    fun isLoginAllowed(): Boolean {
        val now = System.currentTimeMillis()

        // Check permanent lockout
        if (failedAttempts >= PERMANENT_LOCKOUT_THRESHOLD) {
            return false
        }

        // Check temporary lockout
        if (lockoutUntil > now) {
            return false
        }

        // Lockout expired
        if (lockoutUntil > 0) {
            lockoutUntil = 0L
            scope.launch { persistState() }
        }

        updateState()
        return true
    }

    /**
     * Records a failed login attempt.
     * Increments counter and potentially triggers lockout.
     */
    fun recordFailedAttempt() {
        failedAttempts++
        Log.w(TAG, "Login failed. Total failed attempts: $failedAttempts")

        // Check if lockout should be triggered
        val now = System.currentTimeMillis()
        when (failedAttempts) {
            FIRST_LOCKOUT_THRESHOLD -> {
                lockoutUntil = now + FIRST_LOCKOUT_DURATION_MS
                Log.w(TAG, "First lockout triggered (5 minutes)")
            }
            SECOND_LOCKOUT_THRESHOLD -> {
                lockoutUntil = now + SECOND_LOCKOUT_DURATION_MS
                Log.w(TAG, "Second lockout triggered (15 minutes)")
            }
            THIRD_LOCKOUT_THRESHOLD -> {
                lockoutUntil = now + THIRD_LOCKOUT_DURATION_MS
                Log.w(TAG, "Third lockout triggered (30 minutes)")
            }
            PERMANENT_LOCKOUT_THRESHOLD -> {
                Log.e(TAG, "PERMANENT LOCKOUT triggered - too many failed attempts")
            }
        }

        // Persist and update state
        scope.launch {
            persistState()
            updateState()
        }
    }

    /**
     * Records a successful login.
     * Resets all counters and clears lockout.
     */
    fun recordSuccessfulLogin() {
        if (failedAttempts > 0) {
            Log.i(TAG, "Login successful. Resetting failed attempts counter (was: $failedAttempts)")
        }
        failedAttempts = 0
        lockoutUntil = 0L

        scope.launch {
            persistState()
            updateState()
        }
    }

    /**
     * Gets the current number of failed attempts.
     */
    fun getFailedAttempts(): Int = failedAttempts

    /**
     * Gets remaining time in lockout (milliseconds).
     * Returns 0 if not locked out.
     */
    fun getRemainingLockoutMs(): Long {
        val now = System.currentTimeMillis()
        return if (lockoutUntil > now) lockoutUntil - now else 0L
    }

    /**
     * Forces a state refresh.
     * Useful for UI countdown updates.
     */
    fun refreshState() {
        updateState()
    }

    /**
     * Clears all rate limit data.
     * Should only be called during logout or app data clear.
     */
    suspend fun clearAll() {
        failedAttempts = 0
        lockoutUntil = 0L
        persistState()
        updateState()
        Log.i(TAG, "Rate limit data cleared")
    }
}

/**
 * Sealed class representing the current login rate limit state.
 */
sealed class LoginRateLimitState {
    /**
     * Login is allowed.
     * @param failedAttempts Number of previous failed attempts
     * @param remainingAttempts Attempts remaining before next lockout
     */
    data class Ready(
        val failedAttempts: Int = 0,
        val remainingAttempts: Int = 5
    ) : LoginRateLimitState()

    /**
     * Login is temporarily blocked.
     * @param lockoutUntilMs Timestamp when lockout expires
     * @param remainingMs Milliseconds remaining in lockout
     * @param failedAttempts Total failed attempts so far
     */
    data class TemporarilyLocked(
        val lockoutUntilMs: Long,
        val remainingMs: Long,
        val failedAttempts: Int
    ) : LoginRateLimitState()

    /**
     * Login is permanently blocked (too many attempts).
     * User must clear app data or wait for manual reset.
     */
    data object PermanentlyLocked : LoginRateLimitState()
}
