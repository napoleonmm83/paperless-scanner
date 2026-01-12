package com.paperless.scanner.data.billing

import androidx.annotation.VisibleForTesting
import com.paperless.scanner.BuildConfig
import com.paperless.scanner.data.datastore.TokenManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central manager for Premium feature access control.
 *
 * This is the single source of truth for whether a Premium feature is available.
 *
 * ## PHASE 1: Debug-Only Mode (CURRENT)
 * AI features are only available in debug builds for internal testing.
 * No billing/subscription required.
 *
 * ## PHASE 2: Production Mode (FUTURE)
 * After successful testing, switch from BuildConfig.DEBUG to BillingManager.
 * - Replace [_premiumAccessEnabled] with billingManager.isSubscriptionActive
 * - Enable billing UI and purchase flow
 *
 * ARCHITECTURE: Graceful Degradation
 * - Release builds: Paperless API suggestions + Local tag matching only
 * - Debug builds: Firebase AI + Paperless API + Local matching
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
 * @see BillingManager for subscription management (Phase 2)
 */
@Singleton
class PremiumFeatureManager @Inject constructor(
    private val billingManager: BillingManager,
    private val tokenManager: TokenManager
) {

    /**
     * Internal state for premium access.
     *
     * PHASE 1: Initialized with BuildConfig.DEBUG OR user-activated debug mode
     * PHASE 2: Replace with billingManager.isSubscriptionActive
     *
     * Debug mode can be activated by tapping 7x on app version in Settings.
     * This allows testers to access AI features in release builds.
     *
     * @VisibleForTesting allows tests to override this value
     */
    @VisibleForTesting
    internal val _premiumAccessEnabled = MutableStateFlow(
        BuildConfig.DEBUG || tokenManager.isAiDebugModeEnabledSync()
    )

    /**
     * Whether premium features are accessible.
     * PHASE 1: Based on debug build status
     * PHASE 2: Based on subscription status
     */
    val isPremiumAccessEnabled: Flow<Boolean> = _premiumAccessEnabled
    // PHASE 2: Replace with: billingManager.isSubscriptionActive

    /**
     * Sync check for premium access status.
     * PHASE 1: Returns BuildConfig.DEBUG (via MutableStateFlow)
     * PHASE 2: Return billingManager.isSubscriptionActiveSync()
     */
    private fun isPremiumAccessEnabledSync(): Boolean = _premiumAccessEnabled.value
    // PHASE 2: Replace with: billingManager.isSubscriptionActiveSync()

    /**
     * Whether AI suggestions are enabled and available.
     * Combines:
     * - Debug build (Phase 1) OR Active Premium subscription (Phase 2)
     * - User preference (AI suggestions enabled in settings)
     */
    val isAiEnabled: Flow<Boolean> = combine(
        _premiumAccessEnabled,
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
     * PHASE 1: Uses BuildConfig.DEBUG check
     * PHASE 2: Will use billingManager.isSubscriptionActiveSync()
     *
     * @param feature The feature to check
     * @return true if feature is available (debug/subscription + preference enabled)
     */
    fun isFeatureAvailable(feature: PremiumFeature): Boolean {
        return when (feature) {
            PremiumFeature.AI_ANALYSIS -> {
                // PHASE 1: Debug build check
                // PHASE 2: Replace with billingManager.isSubscriptionActiveSync()
                isPremiumAccessEnabledSync()
            }
            PremiumFeature.AI_NEW_TAGS -> {
                // Requires AI analysis + new tags enabled
                isPremiumAccessEnabledSync()
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
     * PHASE 1: Debug builds always have access (unless user disabled in settings)
     * PHASE 2: Will check actual subscription status
     *
     * @return FeatureAccessResult indicating access or upgrade required
     */
    suspend fun requireFeature(feature: PremiumFeature): FeatureAccessResult {
        return if (isFeatureAvailableAsync(feature)) {
            FeatureAccessResult.Granted
        } else {
            if (isPremiumAccessEnabledSync()) {
                // Has access (debug/subscription) but feature disabled in settings
                FeatureAccessResult.DisabledInSettings
            } else {
                // No access (release build without subscription)
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

    /**
     * Refresh premium access state.
     * Call this after enabling/disabling AI debug mode to update access immediately.
     */
    fun refreshPremiumAccess() {
        _premiumAccessEnabled.value = BuildConfig.DEBUG || tokenManager.isAiDebugModeEnabledSync()
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
