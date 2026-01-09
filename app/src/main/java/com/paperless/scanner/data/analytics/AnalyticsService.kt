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

    // ==================== AI-Specific User Properties ====================

    /**
     * Set user's subscription status.
     * Used for segmentation in analytics and monthly reports.
     *
     * @param status Subscription status: "free", "monthly", or "yearly"
     */
    fun setSubscriptionStatus(status: String) {
        setUserProperty("subscription_status", status)
    }

    /**
     * Update AI calls count for current month.
     * Used for tracking heavy users and usage limits.
     *
     * @param callCount Number of AI calls this month
     */
    fun setAiCallsThisMonth(callCount: Int) {
        setUserProperty("ai_calls_this_month", callCount.toString())

        // Mark as heavy user if >100 calls/month
        val isHeavyUser = callCount > 100
        setUserProperty("ai_heavy_user", if (isHeavyUser) "true" else "false")
    }

    /**
     * Track a successful AI feature usage with cost calculation.
     * This is the primary event for AI analytics and BigQuery export.
     *
     * @param featureType Type of AI feature used
     * @param inputTokens Number of input tokens
     * @param outputTokens Number of output tokens
     * @param subscriptionType User's subscription type
     */
    fun trackAiFeatureUsage(
        featureType: String,
        inputTokens: Int,
        outputTokens: Int,
        subscriptionType: String
    ) {
        val costUsd = AiCostCalculator.calculateCost(inputTokens, outputTokens)

        trackEvent(
            AnalyticsEvent.AiFeatureUsed(
                featureType = featureType,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                estimatedCostUsd = costUsd,
                subscriptionType = subscriptionType,
                success = true
            )
        )
    }

    /**
     * Track a failed AI feature usage.
     *
     * @param featureType Type of AI feature
     * @param inputTokens Number of input tokens (may be 0 if failed before sending)
     * @param subscriptionType User's subscription type
     */
    fun trackAiFeatureFailure(
        featureType: String,
        inputTokens: Int,
        subscriptionType: String
    ) {
        val costUsd = if (inputTokens > 0) {
            // Partial cost if request was sent but failed
            AiCostCalculator.calculateCost(inputTokens, 0)
        } else {
            0.0
        }

        trackEvent(
            AnalyticsEvent.AiFeatureUsed(
                featureType = featureType,
                inputTokens = inputTokens,
                outputTokens = 0,
                estimatedCostUsd = costUsd,
                subscriptionType = subscriptionType,
                success = false
            )
        )
    }
}

/**
 * Utility for calculating AI API costs.
 * Uses Gemini Flash 1.5 pricing (as of January 2026).
 *
 * Pricing:
 * - Input: $0.30 per 1M tokens
 * - Output: $2.50 per 1M tokens
 */
object AiCostCalculator {
    private const val INPUT_COST_PER_MILLION = 0.30  // USD
    private const val OUTPUT_COST_PER_MILLION = 2.50 // USD

    /**
     * Calculate estimated cost for an AI API call.
     *
     * @param inputTokens Number of input tokens
     * @param outputTokens Number of output tokens
     * @return Estimated cost in USD
     */
    fun calculateCost(inputTokens: Int, outputTokens: Int): Double {
        val inputCost = (inputTokens.toDouble() / 1_000_000) * INPUT_COST_PER_MILLION
        val outputCost = (outputTokens.toDouble() / 1_000_000) * OUTPUT_COST_PER_MILLION
        return inputCost + outputCost
    }

    /**
     * Calculate cost for input tokens only.
     * Useful for estimating cost before making API call.
     */
    fun calculateInputCost(inputTokens: Int): Double {
        return (inputTokens.toDouble() / 1_000_000) * INPUT_COST_PER_MILLION
    }

    /**
     * Get average cost per AI call (based on typical usage).
     * Assumes ~1500 input tokens and ~200 output tokens per call.
     *
     * @return Average cost in USD
     */
    fun getAverageCostPerCall(): Double {
        return calculateCost(inputTokens = 1500, outputTokens = 200)
    }
}
