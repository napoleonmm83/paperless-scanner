package com.paperless.scanner.data.billing

import androidx.annotation.VisibleForTesting
import com.paperless.scanner.BuildConfig
import com.paperless.scanner.data.datastore.TokenManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central manager for Premium feature access control.
 *
 * This is the single source of truth for whether a Premium feature is available.
 *
 * ## PHASE 2: Production Mode (ACTIVE)
 * AI features are ONLY available with active Premium subscription.
 * - Uses billingManager.isSubscriptionActive
 * - No debug build bypass
 * - No debug mode bypass
 *
 * ARCHITECTURE: Subscription-Based Access
 * - Free users: Paperless API suggestions + Local tag matching only
 * - Premium users: Firebase AI + Paperless API + Local matching
 *
 * Usage:
 * ```kotlin
 * if (premiumFeatureManager.isFeatureAvailable(PremiumFeature.AI_ANALYSIS)) {
 *     // Use Firebase AI (Premium only)
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
     * Whether premium features are accessible.
     * PHASE 2 (ACTIVE): Based on actual subscription status from BillingManager.
     *
     * No bypass mechanisms:
     * - Debug builds DO NOT get free access
     * - Debug mode (7-tap) DOES NOT grant access
     * - ONLY active subscription grants access
     */
    val isPremiumAccessEnabled: Flow<Boolean> = billingManager.isSubscriptionActive

    /**
     * Sync check for premium access status.
     * PHASE 2 (ACTIVE): Returns actual subscription status.
     */
    private fun isPremiumAccessEnabledSync(): Boolean = billingManager.isSubscriptionActiveSync()

    /**
     * Whether AI suggestions are enabled and available.
     * Combines:
     * - Active Premium subscription (from BillingManager)
     * - User preference (AI suggestions enabled in settings)
     *
     * PHASE 2 (ACTIVE): Requires actual subscription.
     */
    val isAiEnabled: Flow<Boolean> = combine(
        billingManager.isSubscriptionActive,
        tokenManager.aiSuggestionsEnabled
    ) { hasAccess, userEnabled ->
        hasAccess && userEnabled
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
     * PHASE 2 (ACTIVE): Uses billingManager.isSubscriptionActiveSync()
     *
     * @param feature The feature to check
     * @return true if feature is available (subscription + preference enabled)
     */
    fun isFeatureAvailable(feature: PremiumFeature): Boolean {
        return when (feature) {
            PremiumFeature.AI_ANALYSIS -> {
                // PHASE 2 (ACTIVE): Subscription check + user preference
                isPremiumAccessEnabledSync() && isAiSuggestionsEnabledSync()
            }
            PremiumFeature.AI_NEW_TAGS -> {
                // Requires AI analysis + new tags enabled
                isPremiumAccessEnabledSync() && isAiSuggestionsEnabledSync() && isAiNewTagsEnabledSync()
            }
            PremiumFeature.AI_SUMMARY -> {
                // Future feature - not implemented yet
                false
            }
        }
    }

    /**
     * Sync check for AI suggestions enabled (user preference).
     */
    private fun isAiSuggestionsEnabledSync(): Boolean {
        return tokenManager.getAiSuggestionsEnabledSync()
    }

    /**
     * Sync check for AI new tags enabled (user preference).
     */
    private fun isAiNewTagsEnabledSync(): Boolean {
        return tokenManager.getAiNewTagsEnabledSync()
    }

    /**
     * Sync check for AI WiFi-only preference.
     */
    private fun isAiWifiOnlySync(): Boolean {
        return runBlocking {
            tokenManager.aiWifiOnly.first()
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
     * PHASE 2 (ACTIVE): Checks actual subscription status.
     *
     * @return FeatureAccessResult indicating access or upgrade required
     */
    suspend fun requireFeature(feature: PremiumFeature): FeatureAccessResult {
        return if (isFeatureAvailableAsync(feature)) {
            FeatureAccessResult.Granted
        } else {
            if (isPremiumAccessEnabledSync()) {
                // Has active subscription but feature disabled in settings
                FeatureAccessResult.DisabledInSettings
            } else {
                // No active subscription
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
