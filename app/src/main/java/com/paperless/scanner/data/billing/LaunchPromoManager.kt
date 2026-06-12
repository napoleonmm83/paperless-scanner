package com.paperless.scanner.data.billing

import com.paperless.scanner.data.config.RemoteConfigManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
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
 * An Active state schedules its own expiry: when the end date passes mid-session,
 * Hidden is emitted on time even without further source emissions. promoOfferTokenFor
 * additionally re-checks the clock on every call (belt and suspenders for the
 * purchase path).
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

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: StateFlow<LaunchPromoState> = combine(
        remoteConfigManager.launchPromoConfig,
        billingManager.launchOffer,
        billingManager.isSubscriptionActive
    ) { config, offer, premiumActive -> Triple(config, offer, premiumActive) }
        .flatMapLatest { (config, offer, premiumActive) ->
            flow {
                val remainingMs = config.endEpochMs - clock()
                if (config.enabled && remainingMs > 0 && offer != null && !premiumActive) {
                    emit(
                        LaunchPromoState.Active(
                            promoPrice = offer.introFormattedPrice,
                            regularPrice = offer.regularFormattedPrice,
                            endEpochMs = config.endEpochMs,
                            offerToken = offer.offerToken
                        )
                    )
                    // Self-expiry: emit Hidden once the end date passes, even when no
                    // source flow ever emits again (long-running session). Any source
                    // emission cancels this via flatMapLatest and re-evaluates.
                    delay(remainingMs)
                    emit(LaunchPromoState.Hidden)
                } else {
                    emit(LaunchPromoState.Hidden)
                }
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, LaunchPromoState.Hidden)

    /**
     * Offer token to purchase [productId] with, or null when no promo applies to it.
     * Re-checks the clock even though [state] self-expires — belt and suspenders so a
     * purchase tap racing the scheduled expiry can never route through a stale token.
     */
    fun promoOfferTokenFor(productId: String): String? {
        val active = state.value as? LaunchPromoState.Active ?: return null
        if (clock() >= active.endEpochMs) return null
        return if (productId == BillingManager.PRODUCT_ID_YEARLY) active.offerToken else null
    }

    /** Explicit teardown seam (#142 pattern), wired in PaperlessApp.onTerminate. */
    fun destroy() {
        scope.cancel()
    }
}
