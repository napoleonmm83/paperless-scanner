package com.paperless.scanner.data.analytics

import com.paperless.scanner.data.billing.BillingManager
import com.paperless.scanner.data.billing.SubscriptionStatus
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test

/**
 * #296: the subscription_status mirror must combine consent state with billing state —
 * billing connects asynchronously and may emit ACTIVE while consent is still ungranted;
 * a distinct-until-changed StateFlow alone would never re-deliver that status after the
 * consent grant.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionAnalyticsSyncTest {

    private val enabled = MutableStateFlow(false)
    private val status = MutableStateFlow<SubscriptionStatus>(SubscriptionStatus.FREE)

    private lateinit var billingManager: BillingManager
    private lateinit var analyticsService: AnalyticsService
    private lateinit var sync: SubscriptionAnalyticsSync

    @Before
    fun setup() {
        billingManager = mockk(relaxed = true)
        analyticsService = mockk(relaxed = true)
        every { billingManager.subscriptionStatus } returns status
        every { analyticsService.enabled } returns enabled
        sync = SubscriptionAnalyticsSync(billingManager, analyticsService)
    }

    @Test
    fun `mirrors the initial status into Crashlytics key and user property`() =
        runTest(UnconfinedTestDispatcher()) {
            sync.start(backgroundScope)

            verify { analyticsService.updateCrashlyticsSubscriptionStatus("free") }
            verify { analyticsService.setSubscriptionStatus("free") }
        }

    @Test
    fun `consent grant re-fires the LATEST status emitted while disabled`() =
        runTest(UnconfinedTestDispatcher()) {
            sync.start(backgroundScope)

            // Billing confirms premium while consent is still ungranted: the service
            // no-ops internally, and the status flow will never re-emit this value.
            status.value = SubscriptionStatus.ACTIVE(expiryDateMs = 1L)
            verify(exactly = 1) { analyticsService.updateCrashlyticsSubscriptionStatus("premium") }

            // The consent grant must re-deliver the latest status — this second call
            // is exactly what a plain status collector (no combine) would never make.
            enabled.value = true
            verify(exactly = 2) { analyticsService.updateCrashlyticsSubscriptionStatus("premium") }
            verify(exactly = 2) { analyticsService.setSubscriptionStatus("premium") }
        }

    @Test
    fun `status changes propagate in order while running`() =
        runTest(UnconfinedTestDispatcher()) {
            enabled.value = true
            sync.start(backgroundScope)

            status.value = SubscriptionStatus.ACTIVE(expiryDateMs = 1L)
            status.value = SubscriptionStatus.FREE

            verifyOrder {
                analyticsService.updateCrashlyticsSubscriptionStatus("free")
                analyticsService.updateCrashlyticsSubscriptionStatus("premium")
                analyticsService.updateCrashlyticsSubscriptionStatus("free")
            }
        }

    @Test
    fun `settings re-enable re-delivers a status that changed while disabled`() =
        runTest(UnconfinedTestDispatcher()) {
            enabled.value = true
            sync.start(backgroundScope)

            enabled.value = false
            status.value = SubscriptionStatus.ACTIVE(expiryDateMs = 1L)
            verify(exactly = 1) { analyticsService.updateCrashlyticsSubscriptionStatus("premium") }

            enabled.value = true
            verify(exactly = 2) { analyticsService.updateCrashlyticsSubscriptionStatus("premium") }
        }

    @Test
    fun `stops mirroring when the scope is cancelled`() =
        runTest(UnconfinedTestDispatcher()) {
            val scope = CoroutineScope(coroutineContext + Job())
            enabled.value = true
            sync.start(scope)

            scope.cancel()
            status.value = SubscriptionStatus.ACTIVE(expiryDateMs = 1L)

            verify(exactly = 0) { analyticsService.updateCrashlyticsSubscriptionStatus("premium") }
        }
}
