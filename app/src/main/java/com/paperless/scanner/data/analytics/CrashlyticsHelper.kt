package com.paperless.scanner.data.analytics

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.crashlytics.crashlytics
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Helper class for Crashlytics breadcrumb logging and custom keys.
 *
 * Provides structured logging methods for:
 * - Navigation breadcrumbs (screen transitions)
 * - Action breadcrumbs (user/system actions like UPLOAD_START, LOGIN)
 * - State breadcrumbs (state changes like UPLOAD_ERROR, OFFLINE)
 *
 * All methods respect GDPR consent via AnalyticsService.isAnalyticsEnabled().
 *
 * Privacy: Server URLs are hashed (SHA-256, first 16 chars) to prevent PII leakage.
 */
@Singleton
class CrashlyticsHelper @Inject constructor(
    private val analyticsService: AnalyticsService
) {
    companion object {
        private const val TAG = "CrashlyticsHelper"

        // Custom key names for Firebase Crashlytics
        private const val KEY_SERVER_URL_HASH = "server_url_hash"
        private const val KEY_APP_VERSION = "app_version"
        private const val KEY_VERSION_CODE = "version_code"
        private const val KEY_SUBSCRIPTION_STATUS = "subscription_status"
        private const val KEY_IS_OFFLINE = "is_offline"
        private const val KEY_LAST_SCREEN = "last_screen"
    }

    /**
     * Log a navigation breadcrumb when user navigates to a new screen.
     * Format: "NAV: {screenName}"
     *
     * @param screenName Human-readable screen name (e.g., "Home", "DocumentDetail")
     */
    fun logNavigationBreadcrumb(screenName: String) {
        if (!analyticsService.isAnalyticsEnabled()) return

        val message = "NAV: $screenName"
        Firebase.crashlytics.log(message)
        Firebase.crashlytics.setCustomKey(KEY_LAST_SCREEN, screenName)
        Log.d(TAG, message)
    }

    /**
     * Log an action breadcrumb for user/system actions.
     * Format: "ACTION: {action}" or "ACTION: {action} - {details}"
     *
     * @param action Action identifier (e.g., "UPLOAD_START", "LOGIN", "SCAN")
     * @param details Optional details (e.g., "3 pages", "multi-page")
     */
    fun logActionBreadcrumb(action: String, details: String? = null) {
        if (!analyticsService.isAnalyticsEnabled()) return

        val message = if (details != null) {
            "ACTION: $action - $details"
        } else {
            "ACTION: $action"
        }
        Firebase.crashlytics.log(message)
        Log.d(TAG, message)
    }

    /**
     * Log a state breadcrumb for state changes.
     * Format: "STATE: {state}" or "STATE: {state} - {details}"
     *
     * @param state State identifier (e.g., "UPLOAD_ERROR", "OFFLINE", "TOKEN_EXPIRED")
     * @param details Optional details (e.g., error message, HTTP code)
     */
    fun logStateBreadcrumb(state: String, details: String? = null) {
        if (!analyticsService.isAnalyticsEnabled()) return

        val message = if (details != null) {
            "STATE: $state - $details"
        } else {
            "STATE: $state"
        }
        Firebase.crashlytics.log(message)
        Log.d(TAG, message)
    }

    /**
     * Set the server URL hash as a custom key.
     * Privacy: URL is hashed with SHA-256 (first 16 chars only).
     *
     * @param serverUrl The server URL (will be hashed)
     */
    fun setServerUrlHash(serverUrl: String?) {
        if (!analyticsService.isAnalyticsEnabled()) return

        val hash = hashServerUrl(serverUrl)
        Firebase.crashlytics.setCustomKey(KEY_SERVER_URL_HASH, hash)
        Log.d(TAG, "Server URL hash set: $hash")
    }

    /**
     * Set app version information as custom keys.
     *
     * @param versionName Version name (e.g., "1.5.38")
     * @param versionCode Version code (e.g., 10538)
     */
    fun setAppVersion(versionName: String, versionCode: Int) {
        if (!analyticsService.isAnalyticsEnabled()) return

        Firebase.crashlytics.setCustomKey(KEY_APP_VERSION, versionName)
        Firebase.crashlytics.setCustomKey(KEY_VERSION_CODE, versionCode)
        Log.d(TAG, "App version set: $versionName ($versionCode)")
    }

    /**
     * Set subscription status as a custom key.
     *
     * @param status Subscription status (e.g., "free", "monthly", "yearly")
     */
    fun setSubscriptionStatus(status: String) {
        if (!analyticsService.isAnalyticsEnabled()) return

        Firebase.crashlytics.setCustomKey(KEY_SUBSCRIPTION_STATUS, status)
        Log.d(TAG, "Subscription status set: $status")
    }

    /**
     * Set offline state as a custom key.
     *
     * @param isOffline Whether the app is currently offline
     */
    fun setOfflineState(isOffline: Boolean) {
        if (!analyticsService.isAnalyticsEnabled()) return

        Firebase.crashlytics.setCustomKey(KEY_IS_OFFLINE, isOffline)
        Log.d(TAG, "Offline state set: $isOffline")
    }

    /**
     * Hash a server URL for privacy.
     * Uses SHA-256 and returns first 16 characters.
     *
     * @param url The URL to hash
     * @return First 16 chars of SHA-256 hash, or "none" if URL is null/empty
     */
    private fun hashServerUrl(url: String?): String {
        if (url.isNullOrBlank()) return "none"

        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(url.toByteArray(Charsets.UTF_8))
            val hexString = hashBytes.joinToString("") { "%02x".format(it) }
            hexString.take(16)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to hash server URL", e)
            "error"
        }
    }
}
