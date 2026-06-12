package com.paperless.scanner.data.billing

import app.cash.turbine.test
import com.paperless.scanner.data.config.LaunchPromoConfig
import com.paperless.scanner.data.config.RemoteConfigManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// UnconfinedTestDispatcher matches the repo's SubscriptionAnalyticsSyncTest pattern for
// stateIn(Eagerly) and makes .value assertions order-independent without advanceUntilIdle
// bookkeeping. (Empirical note: the plain-runTest + advanceUntilIdle draft of this class
// failed the two Active-asserting .value tests with coroutines-test 1.9.0 — Hidden was
// observed — while the Turbine flip test passed; no mechanism claimed.)
//
// NOTE on virtual time: an Active emission schedules its own expiry (delay(remainingMs),
// 1ms with these fixtures) on the test scheduler. Active-asserting tests therefore use
// runCurrent() — advanceUntilIdle() would advance virtual time and fire the expiry,
// flipping the state to Hidden. Hidden-asserting tests keep advanceUntilIdle().
@OptIn(ExperimentalCoroutinesApi::class)
class LaunchPromoManagerTest {

    private val promoConfigFlow = MutableStateFlow(LaunchPromoConfig(enabled = false, endEpochMs = 0L))
    private val launchOfferFlow = MutableStateFlow<LaunchOfferDetails?>(null)
    private val subscriptionActiveFlow = MutableStateFlow(false)

    private val billingManager = mockk<BillingManager> {
        every { launchOffer } returns launchOfferFlow
        every { isSubscriptionActive } returns subscriptionActiveFlow
    }
    private val remoteConfigManager = mockk<RemoteConfigManager> {
        every { launchPromoConfig } returns promoConfigFlow
    }

    private val offer = LaunchOfferDetails(
        offerToken = "promo-token",
        introFormattedPrice = "CHF 19.99",
        regularFormattedPrice = "CHF 39.99"
    )

    private val now = 1_000_000L

    private val expectedActive = LaunchPromoState.Active(
        promoPrice = "CHF 19.99",
        regularPrice = "CHF 39.99",
        endEpochMs = now + 1,
        offerToken = "promo-token"
    )

    private fun openAllGates() {
        promoConfigFlow.value = LaunchPromoConfig(enabled = true, endEpochMs = now + 1)
        launchOfferFlow.value = offer
        subscriptionActiveFlow.value = false
    }

    private fun TestScope.manager() =
        LaunchPromoManager(billingManager, remoteConfigManager, clock = { now }, scope = backgroundScope)

    @Test
    fun `all four gates open - state is Active with offer data`() = runTest(UnconfinedTestDispatcher()) {
        openAllGates()
        val m = manager()
        runCurrent()
        assertEquals(expectedActive, m.state.value)
    }

    @Test
    fun `kill switch off - Hidden`() = runTest(UnconfinedTestDispatcher()) {
        openAllGates()
        promoConfigFlow.value = promoConfigFlow.value.copy(enabled = false)
        val m = manager()
        advanceUntilIdle()
        assertEquals(LaunchPromoState.Hidden, m.state.value)
    }

    @Test
    fun `end date reached - Hidden`() = runTest(UnconfinedTestDispatcher()) {
        openAllGates()
        promoConfigFlow.value = promoConfigFlow.value.copy(endEpochMs = now)
        val m = manager()
        advanceUntilIdle()
        assertEquals(LaunchPromoState.Hidden, m.state.value)
    }

    @Test
    fun `no launch offer served by Play - Hidden`() = runTest(UnconfinedTestDispatcher()) {
        openAllGates()
        launchOfferFlow.value = null
        val m = manager()
        advanceUntilIdle()
        assertEquals(LaunchPromoState.Hidden, m.state.value)
    }

    @Test
    fun `subscription already active - Hidden`() = runTest(UnconfinedTestDispatcher()) {
        openAllGates()
        subscriptionActiveFlow.value = true
        val m = manager()
        advanceUntilIdle()
        assertEquals(LaunchPromoState.Hidden, m.state.value)
    }

    @Test
    fun `state flips to Active when gates open while collecting`() = runTest(UnconfinedTestDispatcher()) {
        val m = manager()
        m.state.test {
            assertEquals(LaunchPromoState.Hidden, awaitItem())
            openAllGates()
            // Three openAllGates() writes may produce intermediate combine emissions,
            // but they all evaluate to Hidden == current value, and StateFlow dedups equal
            // values — so the next distinct item is exactly Active. No expectMostRecentItem()
            // needed: UnconfinedTestDispatcher makes each write synchronous, and StateFlow
            // deduplication ensures only the final Active value crosses the dedup barrier.
            assertEquals(expectedActive, awaitItem())
        }
    }

    @Test
    fun `state flips back to Hidden when the kill switch turns off while collecting`() = runTest(UnconfinedTestDispatcher()) {
        val m = manager()
        m.state.test {
            assertEquals(LaunchPromoState.Hidden, awaitItem())
            openAllGates()
            assertEquals(expectedActive, awaitItem())

            promoConfigFlow.value = promoConfigFlow.value.copy(enabled = false)

            assertEquals(LaunchPromoState.Hidden, awaitItem())
        }
    }

    @Test
    fun `destroy cancels the scope - state freezes and ignores later source changes`() = runTest(UnconfinedTestDispatcher()) {
        openAllGates()
        val m = manager()
        runCurrent()
        assertEquals(expectedActive, m.state.value)

        m.destroy()

        // The collector is cancelled: neither a source mutation that would flip the
        // state to Hidden nor the scheduled self-expiry (advanceUntilIdle advances
        // virtual time past it) may propagate after teardown.
        promoConfigFlow.value = promoConfigFlow.value.copy(enabled = false)
        advanceUntilIdle()
        assertEquals(expectedActive, m.state.value)
    }

    @Test
    fun `promoOfferTokenFor yearly returns token when active`() = runTest(UnconfinedTestDispatcher()) {
        openAllGates()
        val m = manager()
        runCurrent()
        assertEquals("promo-token", m.promoOfferTokenFor(BillingManager.PRODUCT_ID_YEARLY))
    }

    @Test
    fun `promoOfferTokenFor monthly returns null even when active`() = runTest(UnconfinedTestDispatcher()) {
        openAllGates()
        val m = manager()
        runCurrent() // keep state Active — the null must come from the product check, not Hidden
        assertNull(m.promoOfferTokenFor(BillingManager.PRODUCT_ID_MONTHLY))
    }

    @Test
    fun `promoOfferTokenFor returns null when hidden`() = runTest(UnconfinedTestDispatcher()) {
        val m = manager()
        advanceUntilIdle()
        assertNull(m.promoOfferTokenFor(BillingManager.PRODUCT_ID_YEARLY))
    }

    @Test
    fun `promoOfferTokenFor returns null past end time even before the scheduled expiry fires`() =
        runTest(UnconfinedTestDispatcher()) {
            var nowMs = now
            openAllGates() // endEpochMs = now + 1
            val m = LaunchPromoManager(billingManager, remoteConfigManager, clock = { nowMs }, scope = backgroundScope)
            runCurrent()
            assertEquals("promo-token", m.promoOfferTokenFor(BillingManager.PRODUCT_ID_YEARLY))

            // Belt and suspenders: the wall clock passes the end date but virtual time is
            // NOT advanced — the scheduled self-expiry (tested separately above) has not
            // fired yet, so state still shows Active. The purchase guard must not route anyway.
            nowMs = now + 2
            assertEquals(expectedActive, m.state.value)
            assertNull(m.promoOfferTokenFor(BillingManager.PRODUCT_ID_YEARLY))
        }

    @Test
    fun `state expires to Hidden when the end date passes without source emissions`() =
        runTest(UnconfinedTestDispatcher()) {
            var nowMs = now
            openAllGates() // endEpochMs = now + 1 → remainingMs = 1 at construction
            val m = LaunchPromoManager(billingManager, remoteConfigManager, clock = { nowMs }, scope = backgroundScope)
            runCurrent()
            assertEquals(expectedActive, m.state.value)

            // Wall clock passes the end date (realism); the expiry delay was computed
            // with the construction-time clock (1ms), so advancing virtual time by ≥1ms
            // fires it regardless — with ZERO further source emissions.
            nowMs = now + 2
            advanceTimeBy(2)
            runCurrent()
            assertEquals(LaunchPromoState.Hidden, m.state.value)
        }
}
