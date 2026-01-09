package com.paperless.scanner.data.billing

import com.paperless.scanner.data.datastore.TokenManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central manager for Premium feature access control.
 *
 * This is the single source of truth for whether a Premium feature is available.
 * It combines:
 * - Subscription status (from BillingManager)
 * - User preferences (from TokenManager)
 * - Network requirements (for AI features)
 *
 * ARCHITECTURE: Graceful Degradation
 * - Free users: Paperless API suggestions + Local tag matching
 * - Premium users: Firebase AI + Paperless API + Local matching
 *
 * Usage:
 * ```kotlin
 * if (premiumFeatureManager.isFeatureAvailable(PremiumFeature.AI_ANALYSIS)) {
 *     // Use Firebase AI
 * } else {
 *     // Fallback to free alternatives
 * }
 * ```
 *
 * @see PremiumFeature for available Premium features
 * @see BillingManager for subscription management
 */
@Singleton
class PremiumFeatureManager @Inject constructor(
    private val billingManager: BillingManager,
    private val tokenManager: TokenManager
) {

    /**
     * Whether AI suggestions are enabled and available.
     * Combines:
     * - Active Premium subscription
     * - User preference (AI suggestions enabled in settings)
     */
    val isAiEnabled: Flow<Boolean> = combine(
        billingManager.isSubscriptionActive,
        tokenManager.aiSuggestionsEnabled
    ) { hasSubscription, userEnabled ->
        hasSubscription && userEnabled
    }

    /**
     * Whether AI can suggest new tags.
     * Requires Premium + AI enabled + new tags preference.
     */
    val isAiNewTagsEnabled: Flow<Boolean> = combine(
        isAiEnabled,
        tokenManager.aiNewTagsEnabled
    ) { aiEnabled, newTagsEnabled ->
        aiEnabled && newTagsEnabled
    }

    /**
     * Whether AI features should only work on WiFi.
     */
    val aiWifiOnly: Flow<Boolean> = tokenManager.aiWifiOnly

    /**
     * Check if a specific Premium feature is available.
     * Synchronous version for immediate decision-making.
     *
     * @param feature The feature to check
     * @return true if feature is available (subscription active + preference enabled)
     */
    fun isFeatureAvailable(feature: PremiumFeature): Boolean {
        return when (feature) {
            PremiumFeature.AI_ANALYSIS -> {
                // Requires active subscription (sync check)
                billingManager.isSubscriptionActiveSync()
            }
            PremiumFeature.AI_NEW_TAGS -> {
                // Requires AI analysis + new tags enabled
                billingManager.isSubscriptionActiveSync()
                // TODO: Check tokenManager.aiNewTagsEnabled sync
            }
            PremiumFeature.AI_SUMMARY -> {
                // Future feature - not implemented yet
                false
            }
        }
    }

    /**
     * Check if a feature is available (async version).
     * Use this when you need to respect user preferences.
     *
     * @param feature The feature to check
     * @return true if feature is available
     */
    suspend fun isFeatureAvailableAsync(feature: PremiumFeature): Boolean {
        return when (feature) {
            PremiumFeature.AI_ANALYSIS -> {
                isAiEnabled.first()
            }
            PremiumFeature.AI_NEW_TAGS -> {
                isAiNewTagsEnabled.first()
            }
            PremiumFeature.AI_SUMMARY -> {
                false // Not implemented yet
            }
        }
    }

    /**
     * Require a Premium feature or return a result indicating upgrade needed.
     * Useful for feature-gated actions.
     *
     * @return FeatureAccessResult indicating access or upgrade required
     */
    suspend fun requireFeature(feature: PremiumFeature): FeatureAccessResult {
        return if (isFeatureAvailableAsync(feature)) {
            FeatureAccessResult.Granted
        } else {
            if (billingManager.isSubscriptionActiveSync()) {
                // Has subscription but feature disabled in settings
                FeatureAccessResult.DisabledInSettings
            } else {
                // No subscription
                FeatureAccessResult.RequiresUpgrade
            }
        }
    }

    /**
     * Show upgrade dialog (to be implemented in UI layer).
     * This is a placeholder for now.
     */
    fun showUpgradeDialog() {
        // TODO: Emit event to show upgrade dialog
        // Could use SharedFlow or event bus pattern
    }
}

/**
 * Result of feature access check.
 */
sealed class FeatureAccessResult {
    /** Feature is available and can be used */
    data object Granted : FeatureAccessResult()

    /** User has subscription but disabled feature in settings */
    data object DisabledInSettings : FeatureAccessResult()

    /** User needs to upgrade to Premium */
    data object RequiresUpgrade : FeatureAccessResult()
}
