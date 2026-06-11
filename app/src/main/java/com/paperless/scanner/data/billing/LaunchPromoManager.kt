package com.paperless.scanner.data.billing

import com.paperless.scanner.data.config.RemoteConfigManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/** UI-facing launch-promo state. Prices are localized strings straight from Play Billing. */
sealed interface LaunchPromoState {
    data object Hidden : LaunchPromoState
    data class Active(
        val promoPrice: String,
        val regularPrice: String,
        val endEpochMs: Long,
        val offerToken: String
    ) : LaunchPromoState
}

/**
 * Single source of truth for "is the launch promo live for THIS user right now".
 *
 * Active only when ALL four gates hold:
 *  1. Remote Config kill switch on,
 *  2. end date not passed (display gate only — the authoritative gate is the Play offer),
 *  3. Play currently serves the launch50 offer on the yearly plan,
 *  4. the user has no active subscription.
 * Anything else → Hidden (fail-closed).
 *
 * Eagerly shared: SettingsViewModel reads [promoOfferTokenFor] synchronously, so
 * `.value` must be fresh without a collector (WhileSubscribed would pin it).
 *
 * Known limitation: merely passing the end date does not re-emit by itself; the state
 * re-evaluates on the next source emission. Deactivating the Console offer and the
 * kill switch are the real off-switches.
 */
@Singleton
class LaunchPromoManager internal constructor(
    billingManager: BillingManager,
    remoteConfigManager: RemoteConfigManager,
    private val clock: () -> Long,
    private val scope: CoroutineScope
) {
    @Inject
    constructor(
        billingManager: BillingManager,
        remoteConfigManager: RemoteConfigManager
    ) : this(
        billingManager = billingManager,
        remoteConfigManager = remoteConfigManager,
        clock = { System.currentTimeMillis() },
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    )

    val state: StateFlow<LaunchPromoState> = combine(
        remoteConfigManager.launchPromoConfig,
        billingManager.launchOffer,
        billingManager.isSubscriptionActive
    ) { config, offer, premiumActive ->
        if (config.enabled && clock() < config.endEpochMs && offer != null && !premiumActive) {
            LaunchPromoState.Active(
                promoPrice = offer.introFormattedPrice,
                regularPrice = offer.regularFormattedPrice,
                endEpochMs = config.endEpochMs,
                offerToken = offer.offerToken
            )
        } else {
            LaunchPromoState.Hidden
        }
    }.stateIn(scope, SharingStarted.Eagerly, LaunchPromoState.Hidden)

    /** Offer token to purchase [productId] with, or null when no promo applies to it. */
    fun promoOfferTokenFor(productId: String): String? {
        val active = state.value as? LaunchPromoState.Active ?: return null
        return if (productId == BillingManager.PRODUCT_ID_YEARLY) active.offerToken else null
    }

    /** Explicit teardown seam (#142 pattern), wired in PaperlessApp.onTerminate. */
    fun destroy() {
        scope.cancel()
    }
}
