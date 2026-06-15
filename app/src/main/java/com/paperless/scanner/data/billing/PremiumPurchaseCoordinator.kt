package com.paperless.scanner.data.billing

import android.app.Activity
import com.paperless.scanner.data.analytics.AnalyticsEvent
import com.paperless.scanner.data.analytics.AnalyticsService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single owner of the premium purchase routing: resolves the launch-promo offer token
 * (yearly + promo active → launch50, otherwise default offer) and logs
 * [AnalyticsEvent.PremiumSubscribed] on success (GDPR-gated inside AnalyticsService).
 * Pending purchases are not logged — the conversion is only counted once Play
 * confirms payment. Restore is also funnelled through here so every premium money path
 * (purchase + restore) has a single owner and the entry points cannot drift.
 * Used by the Settings path and every PremiumUpgradeSheet host (Home/Upload/MultiPage/SmartTagging).
 *
 * Google Play's own purchase sheet remains the authoritative price display — a promo
 * flip between render and tap can only surface there, never silently change the charge.
 */
@Singleton
class PremiumPurchaseCoordinator @Inject constructor(
    private val billingManager: BillingManager,
    private val launchPromoManager: LaunchPromoManager,
    private val analyticsService: AnalyticsService
) {
    suspend fun purchase(activity: Activity, productId: String): PurchaseResult {
        val promoOfferToken = launchPromoManager.promoOfferTokenFor(productId)
        val result = billingManager.launchPurchaseFlow(activity, productId, promoOfferToken)
        if (result is PurchaseResult.Success) {
            analyticsService.trackEvent(
                AnalyticsEvent.PremiumSubscribed(
                    plan = if (productId == BillingManager.PRODUCT_ID_MONTHLY) "monthly" else "yearly",
                    offerTag = if (promoOfferToken != null) BillingManager.LAUNCH_PROMO_OFFER_TAG else "none"
                )
            )
        }
        return result
    }

    /**
     * Restore previously bought subscriptions. No offer-token routing or analytics applies —
     * restore re-grants an existing entitlement, it is not a new conversion.
     */
    suspend fun restorePurchases(): RestoreResult = billingManager.restorePurchases()
}
