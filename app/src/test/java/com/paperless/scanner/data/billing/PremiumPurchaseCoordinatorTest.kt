package com.paperless.scanner.data.billing

import com.paperless.scanner.data.analytics.AnalyticsEvent
import com.paperless.scanner.data.analytics.AnalyticsService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class PremiumPurchaseCoordinatorTest {

    private val billingManager = mockk<BillingManager>()
    private val launchPromoManager = mockk<LaunchPromoManager>()
    private val analyticsService = mockk<AnalyticsService>(relaxed = true)

    private val coordinator = PremiumPurchaseCoordinator(
        billingManager = billingManager,
        launchPromoManager = launchPromoManager,
        analyticsService = analyticsService
    )

    @Test
    fun `yearly purchase with promo routes promo token and logs yearly launch50`() = runTest {
        every { launchPromoManager.promoOfferTokenFor(BillingManager.PRODUCT_ID_YEARLY) } returns "promo-token"
        coEvery {
            billingManager.launchPurchaseFlow(any(), BillingManager.PRODUCT_ID_YEARLY, "promo-token")
        } returns PurchaseResult.Success

        val activity = mockk<android.app.Activity>(relaxed = true)
        coordinator.purchase(activity, BillingManager.PRODUCT_ID_YEARLY)

        coVerify {
            billingManager.launchPurchaseFlow(activity, BillingManager.PRODUCT_ID_YEARLY, "promo-token")
        }
        verify {
            analyticsService.trackEvent(
                AnalyticsEvent.PremiumSubscribed(plan = "yearly", offerTag = BillingManager.LAUNCH_PROMO_OFFER_TAG)
            )
        }
    }

    @Test
    fun `monthly purchase without promo uses null token and logs monthly none`() = runTest {
        every { launchPromoManager.promoOfferTokenFor(BillingManager.PRODUCT_ID_MONTHLY) } returns null
        coEvery {
            billingManager.launchPurchaseFlow(any(), BillingManager.PRODUCT_ID_MONTHLY, null)
        } returns PurchaseResult.Success

        val activity = mockk<android.app.Activity>(relaxed = true)
        coordinator.purchase(activity, BillingManager.PRODUCT_ID_MONTHLY)

        coVerify {
            billingManager.launchPurchaseFlow(activity, BillingManager.PRODUCT_ID_MONTHLY, null)
        }
        verify {
            analyticsService.trackEvent(
                AnalyticsEvent.PremiumSubscribed(plan = "monthly", offerTag = "none")
            )
        }
    }

    @Test
    fun `yearly purchase without promo uses null token and logs yearly none`() = runTest {
        every { launchPromoManager.promoOfferTokenFor(BillingManager.PRODUCT_ID_YEARLY) } returns null
        coEvery {
            billingManager.launchPurchaseFlow(any(), BillingManager.PRODUCT_ID_YEARLY, null)
        } returns PurchaseResult.Success

        val activity = mockk<android.app.Activity>(relaxed = true)
        coordinator.purchase(activity, BillingManager.PRODUCT_ID_YEARLY)

        coVerify {
            billingManager.launchPurchaseFlow(activity, BillingManager.PRODUCT_ID_YEARLY, null)
        }
        verify {
            analyticsService.trackEvent(
                AnalyticsEvent.PremiumSubscribed(plan = "yearly", offerTag = "none")
            )
        }
    }

    @Test
    fun `error result logs no PremiumSubscribed event`() = runTest {
        every { launchPromoManager.promoOfferTokenFor(BillingManager.PRODUCT_ID_YEARLY) } returns null
        coEvery {
            billingManager.launchPurchaseFlow(any(), BillingManager.PRODUCT_ID_YEARLY, null)
        } returns PurchaseResult.Error("nope")

        val activity = mockk<android.app.Activity>(relaxed = true)
        coordinator.purchase(activity, BillingManager.PRODUCT_ID_YEARLY)

        verify(exactly = 0) { analyticsService.trackEvent(any<AnalyticsEvent.PremiumSubscribed>()) }
    }

    @Test
    fun `pending purchase logs no subscription event`() = runTest {
        every { launchPromoManager.promoOfferTokenFor(BillingManager.PRODUCT_ID_YEARLY) } returns null
        coEvery {
            billingManager.launchPurchaseFlow(any(), BillingManager.PRODUCT_ID_YEARLY, null)
        } returns PurchaseResult.Pending

        val activity = mockk<android.app.Activity>(relaxed = true)
        val result = coordinator.purchase(activity, BillingManager.PRODUCT_ID_YEARLY)

        assertEquals(PurchaseResult.Pending, result)
        verify(exactly = 0) { analyticsService.trackEvent(any<AnalyticsEvent.PremiumSubscribed>()) }
    }

    @Test
    fun `restorePurchases delegates to billingManager and returns its result`() = runTest {
        coEvery { billingManager.restorePurchases() } returns RestoreResult.Success(restoredCount = 2)

        val result = coordinator.restorePurchases()

        assertEquals(RestoreResult.Success(restoredCount = 2), result)
        coVerify { billingManager.restorePurchases() }
    }

    @Test
    fun `restorePurchases is not a conversion and logs no PremiumSubscribed event`() = runTest {
        coEvery { billingManager.restorePurchases() } returns RestoreResult.Success(restoredCount = 1)

        coordinator.restorePurchases()

        verify(exactly = 0) { analyticsService.trackEvent(any<AnalyticsEvent.PremiumSubscribed>()) }
    }
}
