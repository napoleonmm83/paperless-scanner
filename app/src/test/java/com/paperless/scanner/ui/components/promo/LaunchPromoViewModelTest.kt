package com.paperless.scanner.ui.components.promo

import app.cash.turbine.test
import com.paperless.scanner.data.analytics.AnalyticsEvent
import com.paperless.scanner.data.analytics.AnalyticsService
import com.paperless.scanner.data.billing.BasePlanPrices
import com.paperless.scanner.data.billing.BillingManager
import com.paperless.scanner.data.billing.LaunchPromoManager
import com.paperless.scanner.data.billing.LaunchPromoState
import com.paperless.scanner.data.billing.PremiumPurchaseCoordinator
import com.paperless.scanner.data.billing.PurchaseResult
import com.paperless.scanner.data.datastore.TokenManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LaunchPromoViewModelTest {

    private val promoStateFlow = MutableStateFlow<LaunchPromoState>(LaunchPromoState.Hidden)
    private val dismissedFlow = MutableStateFlow(false)
    private val basePlanPricesFlow = MutableStateFlow<BasePlanPrices?>(null)

    private val launchPromoManager = mockk<LaunchPromoManager> {
        every { state } returns promoStateFlow
    }
    private val billingManager = mockk<BillingManager> {
        every { basePlanPrices } returns basePlanPricesFlow
    }
    private val tokenManager = mockk<TokenManager> {
        every { launchPromoBannerDismissed } returns dismissedFlow
        coEvery { setLaunchPromoBannerDismissed() } answers { dismissedFlow.value = true }
    }
    private val analyticsService = mockk<AnalyticsService>(relaxed = true)
    private val purchaseCoordinator = mockk<PremiumPurchaseCoordinator>(relaxed = true)

    private val activePromo = LaunchPromoState.Active(
        promoPrice = "CHF 19.99",
        regularPrice = "CHF 39.99",
        endEpochMs = 1_750_000_000_000,
        offerToken = "promo-token"
    )

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = LaunchPromoViewModel(launchPromoManager, billingManager, tokenManager, analyticsService, purchaseCoordinator)

    @Test
    fun `banner visible with promo data when active and not dismissed`() = runTest {
        promoStateFlow.value = activePromo
        viewModel().bannerState.test {
            var item = awaitItem()
            if (item is LaunchPromoBannerState.Hidden) item = awaitItem()
            val visible = item as LaunchPromoBannerState.Visible
            assertEquals("CHF 19.99", visible.promoPrice)
            assertEquals("CHF 39.99", visible.regularPrice)
            assertTrue(visible.endDateFormatted.isNotBlank())
        }
    }

    @Test
    fun `banner hidden when previously dismissed`() = runTest {
        promoStateFlow.value = activePromo
        viewModel().bannerState.test {
            var item = awaitItem()
            if (item is LaunchPromoBannerState.Hidden) item = awaitItem()
            assertTrue(item is LaunchPromoBannerState.Visible)
            dismissedFlow.value = true
            assertEquals(LaunchPromoBannerState.Hidden, awaitItem())
        }
    }

    @Test
    fun `dismissBanner persists flag, hides banner and logs dismissal`() = runTest {
        promoStateFlow.value = activePromo
        val vm = viewModel()
        vm.bannerState.test {
            var item = awaitItem()
            if (item is LaunchPromoBannerState.Hidden) item = awaitItem()
            assertTrue(item is LaunchPromoBannerState.Visible)
            vm.dismissBanner()
            assertEquals(LaunchPromoBannerState.Hidden, awaitItem())
        }
        coVerify(exactly = 1) { tokenManager.setLaunchPromoBannerDismissed() }
        verify(exactly = 1) {
            analyticsService.trackEvent(AnalyticsEvent.PremiumPromptDismissed(trigger = LAUNCH_PROMO_TRIGGER))
        }
    }

    @Test
    fun `banner impression is logged only once per view model`() = runTest {
        val vm = viewModel()
        vm.onBannerVisible()
        vm.onBannerVisible()
        verify(exactly = 1) { analyticsService.trackEvent(AnalyticsEvent.LaunchPromoBannerShown) }
    }

    @Test
    fun `banner click logs PremiumPromptShown with launch promo trigger`() = runTest {
        viewModel().onBannerClicked()
        verify(exactly = 1) {
            analyticsService.trackEvent(AnalyticsEvent.PremiumPromptShown(trigger = LAUNCH_PROMO_TRIGGER))
        }
    }

    @Test
    fun `sheetPromo maps active promo and ignores dismiss flag`() = runTest {
        promoStateFlow.value = activePromo
        dismissedFlow.value = true
        viewModel().sheetPromo.test {
            var item = awaitItem()
            if (item == null) item = awaitItem()
            assertEquals("CHF 19.99", item!!.promoPrice)
            assertEquals("CHF 39.99", item.regularPrice)
            assertTrue(item.endDateFormatted.isNotBlank())
        }
    }

    @Test
    fun `purchase delegates to PremiumPurchaseCoordinator`() = runTest {
        val activity = mockk<android.app.Activity>(relaxed = true)
        coEvery {
            purchaseCoordinator.purchase(activity, BillingManager.PRODUCT_ID_YEARLY)
        } returns PurchaseResult.Success

        val result = viewModel().purchase(activity, BillingManager.PRODUCT_ID_YEARLY)

        assertEquals(PurchaseResult.Success, result)
        coVerify { purchaseCoordinator.purchase(activity, BillingManager.PRODUCT_ID_YEARLY) }
    }

    @Test
    fun `basePlanPrices exposes billing manager flow value`() = runTest {
        val prices = BasePlanPrices(monthlyFormatted = "€3.99", yearlyFormatted = "€39.99")
        basePlanPricesFlow.value = prices

        viewModel().basePlanPrices.test {
            // stateIn may emit the initial null before the upstream value arrives
            var item = awaitItem()
            if (item == null) item = awaitItem()
            assertEquals(prices, item)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
