package com.paperless.scanner.ui.screens.settings

import com.paperless.scanner.data.analytics.AnalyticsEvent
import com.paperless.scanner.data.analytics.AnalyticsService
import com.paperless.scanner.data.analytics.AuthDebugService
import com.paperless.scanner.data.repository.ServerStatusRepository
import com.paperless.scanner.domain.model.ServerStatus
import okhttp3.ResponseBody.Companion.toResponseBody
import com.paperless.scanner.data.billing.BillingManager
import com.paperless.scanner.data.billing.LaunchPromoManager
import com.paperless.scanner.data.billing.LaunchPromoState
import com.paperless.scanner.data.billing.PremiumFeatureManager
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.data.billing.PurchaseResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private lateinit var tokenManager: TokenManager
    private lateinit var serverStatusRepository: ServerStatusRepository
    private lateinit var analyticsService: AnalyticsService
    private lateinit var billingManager: BillingManager
    private lateinit var premiumFeatureManager: PremiumFeatureManager
    private lateinit var launchPromoManager: LaunchPromoManager
    private lateinit var authDebugService: AuthDebugService

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        tokenManager = mockk(relaxed = true)
        serverStatusRepository = mockk(relaxed = true)
        // Default: server status fetch fails (matches the old "silent fail" behavior).
        coEvery { serverStatusRepository.getServerStatus() } returns Result.failure(Exception("not stubbed"))
        analyticsService = mockk(relaxed = true)
        billingManager = mockk(relaxed = true)
        premiumFeatureManager = mockk(relaxed = true)
        launchPromoManager = mockk { every { state } returns MutableStateFlow(LaunchPromoState.Hidden) }
        authDebugService = mockk(relaxed = true)

        // Default mock responses
        coEvery { tokenManager.serverUrl } returns flowOf("https://paperless.example.com")
        coEvery { tokenManager.token } returns flowOf("test-token")
        coEvery { tokenManager.uploadNotificationsEnabled } returns flowOf(true)
        coEvery { tokenManager.uploadQuality } returns flowOf("auto")
        coEvery { tokenManager.analyticsConsent } returns flowOf(false)
        coEvery { tokenManager.themeMode } returns flowOf("system")

        // Premium-related mocks
        coEvery { tokenManager.aiSuggestionsEnabled } returns flowOf(true)
        coEvery { tokenManager.aiNewTagsEnabled } returns flowOf(true)
        coEvery { tokenManager.aiWifiOnly } returns flowOf(false)
        coEvery { tokenManager.aiDebugModeEnabled } returns flowOf(false)
        every { billingManager.isSubscriptionActive } returns flowOf(false)
        every { billingManager.isSubscriptionActiveSync() } returns false

        // AppLock-related mocks (all as suspend for consistency)
        every { tokenManager.isAppLockEnabledSync() } returns false
        every { tokenManager.isAppLockBiometricEnabled() } returns false
        coEvery { tokenManager.isAppLockEnabled() } returns flowOf(false)
        coEvery { tokenManager.isAppLockBiometricEnabledFlow() } returns flowOf(false)
        coEvery { tokenManager.getAppLockTimeout() } returns com.paperless.scanner.util.AppLockTimeout.IMMEDIATE
        coEvery { tokenManager.getAppLockTimeoutFlow() } returns flowOf(com.paperless.scanner.util.AppLockTimeout.IMMEDIATE)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): SettingsViewModel {
        return SettingsViewModel(
            tokenManager = tokenManager,
            serverStatusRepository = serverStatusRepository,
            analyticsService = analyticsService,
            billingManager = billingManager,
            premiumFeatureManager = premiumFeatureManager,
            launchPromoManager = launchPromoManager,
            authDebugService = authDebugService
        )
    }

    // ==================== Initial State Tests ====================

    @Test
    fun `loadServerVersion populates serverVersion on success`() = runTest {
        coEvery { serverStatusRepository.getServerStatus() } returns
            Result.success(ServerStatus(paperlessVersion = "2.6.0"))

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals("2.6.0", viewModel.uiState.value.serverVersion)
    }

    @Test
    fun `loadServerVersion leaves serverVersion null on failure`() = runTest {
        // Default mock already returns Result.failure — explicit re-stub for clarity.
        coEvery { serverStatusRepository.getServerStatus() } returns
            Result.failure(retrofit2.HttpException(retrofit2.Response.error<Any>(403, "".toResponseBody())))

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(null, viewModel.uiState.value.serverVersion)
    }

    @Test
    fun `initial uiState has correct defaults`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("https://paperless.example.com", state.serverUrl)
        assertTrue(state.isConnected)
        assertTrue(state.showUploadNotifications)
        assertEquals(UploadQuality.AUTO, state.uploadQuality)
    }

    @Test
    fun `isConnected is false when no token`() = runTest {
        coEvery { tokenManager.token } returns flowOf(null)

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isConnected)
    }

    @Test
    fun `isConnected is false when token is blank`() = runTest {
        coEvery { tokenManager.token } returns flowOf("")

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isConnected)
    }

    @Test
    fun `serverUrl is empty when not configured`() = runTest {
        coEvery { tokenManager.serverUrl } returns flowOf(null)

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals("", viewModel.uiState.value.serverUrl)
    }

    // ==================== Launch Promo Tests ====================

    @Test
    fun `launchPromoActive follows LaunchPromoManager state`() = runTest {
        val promoFlow = MutableStateFlow<LaunchPromoState>(LaunchPromoState.Hidden)
        every { launchPromoManager.state } returns promoFlow

        val viewModel = createViewModel()
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.launchPromoActive)

        promoFlow.value = LaunchPromoState.Active(
            promoPrice = "CHF 19.99",
            regularPrice = "CHF 39.99",
            endEpochMs = Long.MAX_VALUE,
            offerToken = "promo-token"
        )
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.launchPromoActive)
    }

    @Test
    fun `purchase routes yearly through promo offer token when promo active`() = runTest {
        every { launchPromoManager.promoOfferTokenFor(BillingManager.PRODUCT_ID_YEARLY) } returns "promo-token"
        coEvery {
            billingManager.launchPurchaseFlow(any(), BillingManager.PRODUCT_ID_YEARLY, "promo-token")
        } returns PurchaseResult.Success

        val viewModel = createViewModel()
        viewModel.launchPurchaseFlow(mockk(relaxed = true), BillingManager.PRODUCT_ID_YEARLY)

        coVerify { billingManager.launchPurchaseFlow(any(), BillingManager.PRODUCT_ID_YEARLY, "promo-token") }
        verify {
            analyticsService.trackEvent(
                AnalyticsEvent.PremiumSubscribed(plan = "yearly", offerTag = BillingManager.LAUNCH_PROMO_OFFER_TAG)
            )
        }
    }

    @Test
    fun `purchase without promo uses default offer and logs offerTag none`() = runTest {
        every { launchPromoManager.promoOfferTokenFor(BillingManager.PRODUCT_ID_MONTHLY) } returns null
        coEvery {
            billingManager.launchPurchaseFlow(any(), BillingManager.PRODUCT_ID_MONTHLY, null)
        } returns PurchaseResult.Success

        val viewModel = createViewModel()
        viewModel.launchPurchaseFlow(mockk(relaxed = true), BillingManager.PRODUCT_ID_MONTHLY)

        coVerify { billingManager.launchPurchaseFlow(any(), BillingManager.PRODUCT_ID_MONTHLY, null) }
        verify {
            analyticsService.trackEvent(
                AnalyticsEvent.PremiumSubscribed(plan = "monthly", offerTag = "none")
            )
        }
    }

    @Test
    fun `failed purchase logs no subscription event`() = runTest {
        every { launchPromoManager.promoOfferTokenFor(BillingManager.PRODUCT_ID_YEARLY) } returns null
        coEvery {
            billingManager.launchPurchaseFlow(any(), BillingManager.PRODUCT_ID_YEARLY, null)
        } returns PurchaseResult.Error("nope")

        val viewModel = createViewModel()
        viewModel.launchPurchaseFlow(mockk(relaxed = true), BillingManager.PRODUCT_ID_YEARLY)

        verify(exactly = 0) { analyticsService.trackEvent(any<AnalyticsEvent.PremiumSubscribed>()) }
    }

    // ==================== Upload Quality Tests ====================

    @Test
    fun `loadSettings parses upload quality correctly`() = runTest {
        coEvery { tokenManager.uploadQuality } returns flowOf("high")

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(UploadQuality.HIGH, viewModel.uiState.value.uploadQuality)
    }

    @Test
    fun `loadSettings defaults to AUTO for unknown quality`() = runTest {
        coEvery { tokenManager.uploadQuality } returns flowOf("unknown")

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(UploadQuality.AUTO, viewModel.uiState.value.uploadQuality)
    }

    @Test
    fun `setUploadQuality updates state and persists`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setUploadQuality(UploadQuality.HIGH)
        advanceUntilIdle()

        assertEquals(UploadQuality.HIGH, viewModel.uiState.value.uploadQuality)
        coVerify { tokenManager.setUploadQuality("high") }
    }

    @Test
    fun `setUploadQuality LOW persists correct key`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setUploadQuality(UploadQuality.LOW)
        advanceUntilIdle()

        coVerify { tokenManager.setUploadQuality("low") }
    }

    @Test
    fun `setUploadQuality MEDIUM persists correct key`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setUploadQuality(UploadQuality.MEDIUM)
        advanceUntilIdle()

        coVerify { tokenManager.setUploadQuality("medium") }
    }

    // ==================== Upload Notifications Tests ====================

    @Test
    fun `setShowUploadNotifications updates state and persists`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setShowUploadNotifications(false)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showUploadNotifications)
        coVerify { tokenManager.setUploadNotificationsEnabled(false) }
    }

    @Test
    fun `setShowUploadNotifications enables notifications`() = runTest {
        coEvery { tokenManager.uploadNotificationsEnabled } returns flowOf(false)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setShowUploadNotifications(true)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showUploadNotifications)
        coVerify { tokenManager.setUploadNotificationsEnabled(true) }
    }

    // ==================== Logout Tests ====================

    @Test
    fun `logout clears credentials`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.logout()
        advanceUntilIdle()

        coVerify { tokenManager.clearCredentials() }
    }

    // ==================== UploadQuality Enum Tests ====================

    @Test
    fun `UploadQuality entries have correct keys`() {
        assertEquals("auto", UploadQuality.AUTO.key)
        assertEquals("low", UploadQuality.LOW.key)
        assertEquals("medium", UploadQuality.MEDIUM.key)
        assertEquals("high", UploadQuality.HIGH.key)
    }

    @Test
    fun `all quality options load correctly from stored key`() = runTest {
        UploadQuality.entries.forEach { quality ->
            coEvery { tokenManager.uploadQuality } returns flowOf(quality.key)

            val viewModel = createViewModel()
            advanceUntilIdle()

            assertEquals(quality, viewModel.uiState.value.uploadQuality)
        }
    }
}
