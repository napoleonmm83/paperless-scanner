package com.paperless.scanner.data.analytics

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.analytics
import com.google.firebase.crashlytics.crashlytics
import com.google.firebase.perf.performance
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wrapper service for Firebase Analytics, Crashlytics, and Performance Monitoring.
 * Provides a clean API for tracking events throughout the app.
 *
 * All data collection is:
 * - Anonymized (no PII collected)
 * - GDPR-compliant (respects user consent)
 * - Disabled by default until user grants consent
 */
@Singleton
class AnalyticsService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val firebaseAnalytics: FirebaseAnalytics by lazy {
        Firebase.analytics
    }

    private var isEnabled = false

    companion object {
        private const val TAG = "AnalyticsService"
    }

    /**
     * Enable or disable all analytics collection.
     * Should be called based on user consent.
     */
    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
        firebaseAnalytics.setAnalyticsCollectionEnabled(enabled)
        Firebase.crashlytics.setCrashlyticsCollectionEnabled(enabled)
        Firebase.performance.isPerformanceCollectionEnabled = enabled

        Log.d(TAG, "Analytics collection ${if (enabled) "enabled" else "disabled"}")
    }

    /**
     * Track an analytics event.
     * Events are only sent if analytics is enabled.
     */
    fun trackEvent(event: AnalyticsEvent) {
        if (!isEnabled) {
            Log.d(TAG, "Event '${event.name}' skipped (analytics disabled)")
            return
        }

        val bundle = Bundle().apply {
            event.params.forEach { (key, value) ->
                when (value) {
                    is String -> putString(key, value)
                    is Int -> putInt(key, value)
                    is Long -> putLong(key, value)
                    is Double -> putDouble(key, value)
                    is Boolean -> putBoolean(key, value)
                    else -> putString(key, value.toString())
                }
            }
        }

        firebaseAnalytics.logEvent(event.name, bundle)
        Log.d(TAG, "Event tracked: ${event.name} with params: ${event.params}")
    }

    /**
     * Track a screen view.
     * Automatically called when navigating between screens.
     */
    fun trackScreen(screenName: String, screenClass: String? = null) {
        if (!isEnabled) return

        val bundle = Bundle().apply {
            putString(FirebaseAnalytics.Param.SCREEN_NAME, screenName)
            screenClass?.let { putString(FirebaseAnalytics.Param.SCREEN_CLASS, it) }
        }

        firebaseAnalytics.logEvent(FirebaseAnalytics.Event.SCREEN_VIEW, bundle)
        Log.d(TAG, "Screen tracked: $screenName")
    }

    /**
     * Set a user property for segmentation.
     * Properties are anonymized and don't contain PII.
     */
    fun setUserProperty(name: String, value: String?) {
        if (!isEnabled) return

        firebaseAnalytics.setUserProperty(name, value)
    }

    /**
     * Log a non-fatal error to Crashlytics.
     * Useful for tracking handled exceptions.
     */
    fun logError(throwable: Throwable, message: String? = null) {
        if (!isEnabled) return

        message?.let { Firebase.crashlytics.log(it) }
        Firebase.crashlytics.recordException(throwable)
        Log.e(TAG, "Error logged: ${message ?: throwable.message}", throwable)
    }

    /**
     * Log a message to Crashlytics.
     * Messages are attached to crash reports for debugging.
     */
    fun logMessage(message: String) {
        if (!isEnabled) return

        Firebase.crashlytics.log(message)
    }

    /**
     * Check if analytics collection is currently enabled.
     */
    fun isAnalyticsEnabled(): Boolean = isEnabled
}
