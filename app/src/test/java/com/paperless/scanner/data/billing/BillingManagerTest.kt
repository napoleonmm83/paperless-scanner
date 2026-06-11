package com.paperless.scanner.data.billing

import android.app.Activity
import android.content.Context
import app.cash.turbine.test
import com.android.billingclient.api.*
import com.paperless.scanner.R
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for BillingManager.
 *
 * Tests Google Play Billing Library 8.x integration:
 * - Connection management
 * - Product details querying
 * - Purchase flow
 * - Purchase acknowledgement
 * - Restore purchases
 * - Subscription status updates
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BillingManagerTest {

    private lateinit var billingManager: BillingManager
    private lateinit var context: Context
    private lateinit var mockBillingClient: BillingClient
    private lateinit var mockActivity: Activity

    // Mock BillingClient.Builder
    private lateinit var mockBuilder: BillingClient.Builder

    // Capture listeners for manual triggering
    private lateinit var capturedPurchasesUpdatedListener: PurchasesUpdatedListener
    private lateinit var capturedBillingClientStateListener: BillingClientStateListener

    // Slots for capturing
    private val purchasesListenerSlot = slot<PurchasesUpdatedListener>()
    private val stateListenerSlot = slot<BillingClientStateListener>()

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Before
    fun setup() {
        // launchPurchaseFlow now runs the purchase handshake on Dispatchers.Main
        // (Play Billing requirement, #274); provide an eager test Main dispatcher so the
        // existing synchronous purchase-flow assertions still hold.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        context = mockk(relaxed = true)
        // Mock string resources for billing error messages
        every { context.getString(R.string.billing_error_product_not_found) } returns "Product not found. Please try again later."
        every { context.getString(R.string.billing_error_no_offers) } returns "No subscription offers available"
        every { context.getString(R.string.billing_error_not_ready) } returns "Billing service is not ready"
        every { context.getString(R.string.billing_error_disconnected) } returns "Billing service disconnected"
        mockActivity = mockk(relaxed = true)
        mockBillingClient = mockk(relaxed = true)
        mockBuilder = mockk(relaxed = true)

        // Mock BillingClient.newBuilder chain
        mockkStatic(BillingClient::class)
        every { BillingClient.newBuilder(any()) } returns mockBuilder
        every { mockBuilder.setListener(capture(purchasesListenerSlot)) } answers {
            capturedPurchasesUpdatedListener = firstArg()
            mockBuilder
        }
        every { mockBuilder.enablePendingPurchases(any<PendingPurchasesParams>()) } returns mockBuilder
        every { mockBuilder.build() } returns mockBillingClient

        // Capture BillingClientStateListener
        every { mockBillingClient.startConnection(capture(stateListenerSlot)) } answers {
            capturedBillingClientStateListener = firstArg()
        }

        // Mock isReady to return true by default (tests can override)
        every { mockBillingClient.isReady } returns true

        billingManager = BillingManager(context)
    }

    // Helper to setup billing client mocks for purchase flow tests
    private fun setupBillingClientMocks() {
        // Mock queryProductDetailsAsync to prevent hanging on retry
        // Billing Library 8.x uses QueryProductDetailsResult instead of List<ProductDetails>
        every {
            mockBillingClient.queryProductDetailsAsync(
                ofType<QueryProductDetailsParams>(),
                ofType<com.android.billingclient.api.ProductDetailsResponseListener>()
            )
        } answers {
            val callback = secondArg<com.android.billingclient.api.ProductDetailsResponseListener>()
            val mockResult = mockk<BillingResult> {
                every { responseCode } returns BillingClient.BillingResponseCode.OK
            }
            val mockQueryResult = mockk<QueryProductDetailsResult> {
                every { productDetailsList } returns emptyList()
                every { unfetchedProductList } returns emptyList()
            }
            callback.onProductDetailsResponse(mockResult, mockQueryResult)
        }

        // Mock queryPurchasesAsync to prevent hanging
        every {
            mockBillingClient.queryPurchasesAsync(
                ofType<QueryPurchasesParams>(),
                ofType<com.android.billingclient.api.PurchasesResponseListener>()
            )
        } answers {
            val callback = secondArg<com.android.billingclient.api.PurchasesResponseListener>()
            val mockResult = mockk<BillingResult> {
                every { responseCode } returns BillingClient.BillingResponseCode.OK
            }
            callback.onQueryPurchasesResponse(mockResult, emptyList())
        }
    }

    /**
     * Fires onBillingSetupFinished(OK) so the manager transitions Initializing → Ready.
     * Also pre-installs the async-query mocks so the queryPurchases/queryProductDetails
     * triggered on the Ready transition don't leave hanging coroutines in the
     * manager's internal scope.
     */
    private fun transitionToReady() {
        setupBillingClientMocks()
        val okResult = mockk<BillingResult> {
            every { responseCode } returns BillingClient.BillingResponseCode.OK
            every { debugMessage } returns ""
        }
        capturedBillingClientStateListener.onBillingSetupFinished(okResult)
    }

    // ==================== Initialization Tests ====================

    @Test
    fun `initialize creates BillingClient and starts connection`() {
        billingManager.initialize()

        verify { BillingClient.newBuilder(context) }
        verify { mockBuilder.setListener(any()) }
        verify { mockBuilder.enablePendingPurchases(any<PendingPurchasesParams>()) }
        verify { mockBuilder.build() }
        verify { mockBillingClient.startConnection(any()) }
    }

    @Test
    fun `initialize does not reinitialize if already initialized`() {
        billingManager.initialize()
        clearMocks(mockBillingClient, answers = false)

        billingManager.initialize()

        verify(exactly = 0) { mockBillingClient.startConnection(any()) }
    }

    // Note: Skipping complex async connection test due to callback complexity
    // The connection logic is tested implicitly through other tests

    @Test
    fun `connection failure transitions state to Failed`() {
        billingManager.initialize()

        val billingResult = mockk<BillingResult> {
            every { responseCode } returns BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE
            every { debugMessage } returns "Service unavailable"
        }

        capturedBillingClientStateListener.onBillingSetupFinished(billingResult)

        val state = billingManager.billingState.value
        assertTrue("Expected Failed state, was $state", state is BillingState.Failed)
        assertEquals("Service unavailable", (state as BillingState.Failed).reason)
    }

    @Test
    fun `setup success transitions state to Ready`() {
        billingManager.initialize()
        assertEquals(BillingState.Initializing, billingManager.billingState.value)

        transitionToReady()

        assertEquals(BillingState.Ready, billingManager.billingState.value)
    }

    @Test
    fun `setup success queries purchases before product details`() {
        // Cross-class invariant for LaunchPromoManager: the not-premium gate
        // (queryPurchases → isSubscriptionActive) must be resolved before the
        // launch-offer gate can open (queryProductDetails → launchOffer).
        // Swapping the call order in onBillingSetupFinished would let a premium
        // user briefly see the promo on cold start — this test fails on that swap.
        billingManager.initialize()
        transitionToReady()

        verifyOrder {
            mockBillingClient.queryPurchasesAsync(
                any<QueryPurchasesParams>(),
                any<PurchasesResponseListener>()
            )
            mockBillingClient.queryProductDetailsAsync(
                any<QueryProductDetailsParams>(),
                any<ProductDetailsResponseListener>()
            )
        }
    }

    @Test
    fun `disconnection transitions state to Disconnected with retry attempt`() {
        billingManager.initialize()
        transitionToReady()
        assertEquals(BillingState.Ready, billingManager.billingState.value)

        capturedBillingClientStateListener.onBillingServiceDisconnected()

        // Backoff reconnect is scheduled inside scope.launch { delay(...); ... }.
        // We assert the synchronous state transition only — that's the contract
        // observable to consumers. Timing of the retry is not unit-tested.
        val state = billingManager.billingState.value
        assertTrue("Expected Disconnected state, was $state", state is BillingState.Disconnected)
        assertEquals(1, (state as BillingState.Disconnected).retryAttempt)
    }

    @Test
    fun `initialize from Failed state allows retry`() {
        // First attempt: setup fails
        billingManager.initialize()
        val failResult = mockk<BillingResult> {
            every { responseCode } returns BillingClient.BillingResponseCode.BILLING_UNAVAILABLE
            every { debugMessage } returns "Billing unavailable"
        }
        capturedBillingClientStateListener.onBillingSetupFinished(failResult)
        assertTrue(billingManager.billingState.value is BillingState.Failed)

        clearMocks(mockBillingClient, answers = false)
        every { mockBillingClient.startConnection(capture(stateListenerSlot)) } answers {
            capturedBillingClientStateListener = firstArg()
        }
        every { mockBillingClient.isReady } returns true

        // Second attempt: from Failed state should re-initialize
        billingManager.initialize()

        assertEquals(BillingState.Initializing, billingManager.billingState.value)
        verify { mockBillingClient.startConnection(any()) }
    }

    @Test
    fun `initialize from Ready state is a no-op`() {
        billingManager.initialize()
        transitionToReady()
        clearMocks(mockBillingClient, answers = false)

        billingManager.initialize()

        // Already Ready — no new connection
        verify(exactly = 0) { mockBillingClient.startConnection(any()) }
        assertEquals(BillingState.Ready, billingManager.billingState.value)
    }

    // ==================== Purchase Flow Tests ====================

    @Test
    fun `launchPurchaseFlow returns Error when product not found`() = runTest {
        billingManager.initialize()
        setupBillingClientMocks()

        val result = billingManager.launchPurchaseFlow(mockActivity, "unknown_product")

        assertTrue(result is PurchaseResult.Error)
        assertEquals("Product not found. Please try again later.", (result as PurchaseResult.Error).message)
    }

    @Test
    fun `launchPurchaseFlow returns Error when no offer token`() = runTest {
        billingManager.initialize()
        setupBillingClientMocks()

        // Setup product without offer token
        val mockProductDetails = mockk<ProductDetails>(relaxed = true) {
            every { productId } returns BillingManager.PRODUCT_ID_MONTHLY
            every { subscriptionOfferDetails } returns null
        }

        // Inject product into cache via reflection or by triggering queryProductDetailsAsync
        val productDetailsCache = mapOf(BillingManager.PRODUCT_ID_MONTHLY to mockProductDetails)
        setPrivateField(billingManager, "productDetailsCache", productDetailsCache)

        val result = billingManager.launchPurchaseFlow(mockActivity, BillingManager.PRODUCT_ID_MONTHLY)

        assertTrue(result is PurchaseResult.Error)
        assertEquals("No subscription offers available", (result as PurchaseResult.Error).message)
    }

    @Test
    fun `launchPurchaseFlow returns Success when purchase completes`() = runTest {
        billingManager.initialize()
        setupBillingClientMocks()

        // Setup product with offer token
        val mockOfferDetails = mockk<ProductDetails.SubscriptionOfferDetails> {
            every { offerToken } returns "test-offer-token"
            // Default path now filters out promo-tagged offers, so it reads offerTags.
            every { offerTags } returns emptyList()
        }
        val mockProductDetails = mockk<ProductDetails>(relaxed = true) {
            every { productId } returns BillingManager.PRODUCT_ID_MONTHLY
            every { subscriptionOfferDetails } returns listOf(mockOfferDetails)
        }

        val productDetailsCache = mapOf(BillingManager.PRODUCT_ID_MONTHLY to mockProductDetails)
        setPrivateField(billingManager, "productDetailsCache", productDetailsCache)

        // Mock launchBillingFlow to return OK (dialog launched)
        val mockBillingResult = mockk<BillingResult> {
            every { responseCode } returns BillingClient.BillingResponseCode.OK
            every { debugMessage } returns ""
        }
        every { mockBillingClient.launchBillingFlow(any(), any()) } answers {
            // Immediately trigger purchasesUpdatedListener with success
            val mockPurchase = mockk<Purchase>(relaxed = true) {
                every { purchaseState } returns Purchase.PurchaseState.PURCHASED
                every { products } returns listOf(BillingManager.PRODUCT_ID_MONTHLY)
            }
            val successResult = mockk<BillingResult> {
                every { responseCode } returns BillingClient.BillingResponseCode.OK
                every { debugMessage } returns ""
            }
            capturedPurchasesUpdatedListener.onPurchasesUpdated(successResult, listOf(mockPurchase))
            mockBillingResult
        }

        val result = billingManager.launchPurchaseFlow(mockActivity, BillingManager.PRODUCT_ID_MONTHLY)

        assertTrue(result is PurchaseResult.Success)
    }

    @Test
    fun `launchPurchaseFlow returns Cancelled when user cancels`() = runTest {
        billingManager.initialize()
        setupBillingClientMocks()

        val mockOfferDetails = mockk<ProductDetails.SubscriptionOfferDetails> {
            every { offerToken } returns "test-offer-token"
            // Default path now filters out promo-tagged offers, so it reads offerTags.
            every { offerTags } returns emptyList()
        }
        val mockProductDetails = mockk<ProductDetails>(relaxed = true) {
            every { productId } returns BillingManager.PRODUCT_ID_YEARLY
            every { subscriptionOfferDetails } returns listOf(mockOfferDetails)
        }

        val productDetailsCache = mapOf(BillingManager.PRODUCT_ID_YEARLY to mockProductDetails)
        setPrivateField(billingManager, "productDetailsCache", productDetailsCache)

        // Mock launchBillingFlow to return OK, then trigger cancel
        val mockBillingResult = mockk<BillingResult> {
            every { responseCode } returns BillingClient.BillingResponseCode.OK
            every { debugMessage } returns ""
        }
        every { mockBillingClient.launchBillingFlow(any(), any()) } answers {
            // Immediately trigger purchasesUpdatedListener with cancellation
            val cancelResult = mockk<BillingResult> {
                every { responseCode } returns BillingClient.BillingResponseCode.USER_CANCELED
                every { debugMessage } returns "User cancelled"
            }
            capturedPurchasesUpdatedListener.onPurchasesUpdated(cancelResult, null)
            mockBillingResult
        }

        val result = billingManager.launchPurchaseFlow(mockActivity, BillingManager.PRODUCT_ID_YEARLY)

        assertTrue(result is PurchaseResult.Cancelled)
    }

    // ==================== Restore Purchases Tests ====================

    @Test
    fun `restorePurchases returns Success when purchases found`() = runTest {
        billingManager.initialize()
        transitionToReady()

        val mockPurchase = mockk<Purchase> {
            every { purchaseState } returns Purchase.PurchaseState.PURCHASED
            every { isAcknowledged } returns true
            every { products } returns listOf(BillingManager.PRODUCT_ID_MONTHLY)
        }

        every {
            mockBillingClient.queryPurchasesAsync(
                any<QueryPurchasesParams>(),
                any<PurchasesResponseListener>()
            )
        } answers {
            val callback = secondArg<PurchasesResponseListener>()
            val billingResult = mockk<BillingResult> {
                every { responseCode } returns BillingClient.BillingResponseCode.OK
            }
            callback.onQueryPurchasesResponse(billingResult, listOf(mockPurchase))
        }

        val result = billingManager.restorePurchases()

        assertTrue(result is RestoreResult.Success)
        assertEquals(1, (result as RestoreResult.Success).restoredCount)
    }

    @Test
    fun `restorePurchases returns NoPurchasesFound when no purchases`() = runTest {
        billingManager.initialize()
        transitionToReady()

        every {
            mockBillingClient.queryPurchasesAsync(
                any<QueryPurchasesParams>(),
                any<PurchasesResponseListener>()
            )
        } answers {
            val callback = secondArg<PurchasesResponseListener>()
            val billingResult = mockk<BillingResult> {
                every { responseCode } returns BillingClient.BillingResponseCode.OK
            }
            callback.onQueryPurchasesResponse(billingResult, emptyList())
        }

        val result = billingManager.restorePurchases()

        assertTrue(result is RestoreResult.NoPurchasesFound)
    }

    @Test
    fun `restorePurchases returns Error when query fails`() = runTest {
        billingManager.initialize()
        transitionToReady()

        every {
            mockBillingClient.queryPurchasesAsync(
                any<QueryPurchasesParams>(),
                any<PurchasesResponseListener>()
            )
        } answers {
            val callback = secondArg<PurchasesResponseListener>()
            val billingResult = mockk<BillingResult> {
                every { responseCode } returns BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE
                every { debugMessage } returns "Service unavailable"
            }
            callback.onQueryPurchasesResponse(billingResult, emptyList())
        }

        val result = billingManager.restorePurchases()

        assertTrue(result is RestoreResult.Error)
    }

    @Test
    fun `restorePurchases returns Error when state is not Ready (regression - prior code hung)`() = runTest {
        // Manager initialized but never transitions to Ready (setup callback never fires).
        // Prior code: billingClient?.queryPurchasesAsync was a no-op when client wasn't
        // ready and the continuation was never resumed, hanging the coroutine forever.
        // Now: explicit Ready-state gate returns RestoreResult.Error immediately.
        billingManager.initialize()
        assertEquals(BillingState.Initializing, billingManager.billingState.value)

        val result = billingManager.restorePurchases()

        assertTrue(result is RestoreResult.Error)
    }

    // ==================== Subscription Status Tests ====================

    // Note: Flow-based subscription status tests are complex due to async callbacks
    // Testing the sync method instead

    @Test
    fun `isSubscriptionActiveSync returns current subscription status`() {
        billingManager.initialize()

        // Initially false
        assertFalse(billingManager.isSubscriptionActiveSync())
    }

    // ==================== Destroy Tests ====================

    @Test
    fun `destroy ends billing connection and resets state to Uninitialized`() {
        billingManager.initialize()
        transitionToReady()
        assertEquals(BillingState.Ready, billingManager.billingState.value)

        billingManager.destroy()

        verify { mockBillingClient.endConnection() }
        assertEquals(BillingState.Uninitialized, billingManager.billingState.value)
    }

    // ==================== Explicit Offer Token Tests ====================

    @Test
    fun `launchPurchaseFlow with explicit offer token purchases that offer`() = runTest {
        billingManager.initialize()
        setupBillingClientMocks()

        // Yearly product serving TWO offers: the default base offer first,
        // the launch50 promo second. The explicit-token path must pick the
        // promo offer, NOT firstOrNull()'s base offer.
        val baseOffer = mockk<ProductDetails.SubscriptionOfferDetails> {
            every { offerToken } returns "base-token"
            every { offerTags } returns emptyList()
        }
        val promoOffer = mockk<ProductDetails.SubscriptionOfferDetails> {
            every { offerToken } returns "promo-token"
            every { offerTags } returns listOf("launch50")
        }
        val mockProductDetails = mockk<ProductDetails>(relaxed = true) {
            every { productId } returns BillingManager.PRODUCT_ID_YEARLY
            every { subscriptionOfferDetails } returns listOf(baseOffer, promoOffer)
        }
        setPrivateField(
            billingManager,
            "productDetailsCache",
            mapOf(BillingManager.PRODUCT_ID_YEARLY to mockProductDetails)
        )

        // Capture the token handed to setOfferToken by mocking both static
        // builder entry points — the real builders validate mocked
        // ProductDetails internals, so they must not run.
        val offerTokenSlot = slot<String>()
        mockkStatic(BillingFlowParams.ProductDetailsParams::class)
        mockkStatic(BillingFlowParams::class)
        try {
            val mockParamsBuilder = mockk<BillingFlowParams.ProductDetailsParams.Builder>(relaxed = true)
            every { BillingFlowParams.ProductDetailsParams.newBuilder() } returns mockParamsBuilder
            every { mockParamsBuilder.setProductDetails(any()) } returns mockParamsBuilder
            every { mockParamsBuilder.setOfferToken(capture(offerTokenSlot)) } returns mockParamsBuilder
            every { mockParamsBuilder.build() } returns mockk(relaxed = true)

            val mockFlowBuilder = mockk<BillingFlowParams.Builder>(relaxed = true)
            every { BillingFlowParams.newBuilder() } returns mockFlowBuilder
            every { mockFlowBuilder.setProductDetailsParamsList(any()) } returns mockFlowBuilder
            every { mockFlowBuilder.build() } returns mockk(relaxed = true)

            val launchOkResult = mockk<BillingResult> {
                every { responseCode } returns BillingClient.BillingResponseCode.OK
                every { debugMessage } returns ""
            }
            every { mockBillingClient.launchBillingFlow(any(), any()) } answers {
                val mockPurchase = mockk<Purchase>(relaxed = true) {
                    every { purchaseState } returns Purchase.PurchaseState.PURCHASED
                    every { isAcknowledged } returns true
                    every { products } returns listOf(BillingManager.PRODUCT_ID_YEARLY)
                }
                val successResult = mockk<BillingResult> {
                    every { responseCode } returns BillingClient.BillingResponseCode.OK
                    every { debugMessage } returns ""
                }
                capturedPurchasesUpdatedListener.onPurchasesUpdated(successResult, listOf(mockPurchase))
                launchOkResult
            }

            val result = billingManager.launchPurchaseFlow(
                mockActivity,
                BillingManager.PRODUCT_ID_YEARLY,
                "promo-token"
            )

            assertTrue(result is PurchaseResult.Success)
            assertEquals("promo-token", offerTokenSlot.captured)
        } finally {
            unmockkStatic(BillingFlowParams.ProductDetailsParams::class)
            unmockkStatic(BillingFlowParams::class)
        }
    }

    @Test
    fun `launchPurchaseFlow with stale promo token fails closed`() = runTest {
        billingManager.initialize()
        setupBillingClientMocks()

        // Play no longer serves the requested promo offer (Console offer
        // deactivated) — only the base offer remains in the snapshot.
        val baseOffer = mockk<ProductDetails.SubscriptionOfferDetails> {
            every { offerToken } returns "base-token"
            every { offerTags } returns emptyList()
        }
        val mockProductDetails = mockk<ProductDetails>(relaxed = true) {
            every { productId } returns BillingManager.PRODUCT_ID_YEARLY
            every { subscriptionOfferDetails } returns listOf(baseOffer)
        }
        setPrivateField(
            billingManager,
            "productDetailsCache",
            mapOf(BillingManager.PRODUCT_ID_YEARLY to mockProductDetails)
        )

        val result = billingManager.launchPurchaseFlow(
            mockActivity,
            BillingManager.PRODUCT_ID_YEARLY,
            "promo-token"
        )

        // Fail closed: no silent fallback to the base offer, no flow launch.
        assertTrue(result is PurchaseResult.Error)
        assertEquals("No subscription offers available", (result as PurchaseResult.Error).message)
        verify(exactly = 0) { mockBillingClient.launchBillingFlow(any(), any()) }
    }

    @Test
    fun `default purchase path skips promo-tagged offers`() = runTest {
        billingManager.initialize()
        setupBillingClientMocks()

        // Play serves the launch50 promo FIRST (offer ordering is not guaranteed).
        // A regular purchase (no explicit token) must NOT land on the discounted
        // promo token when the promo gates are closed — it must pick the first
        // untagged offer instead (revenue bug otherwise: silent undercharge).
        val promoOffer = mockk<ProductDetails.SubscriptionOfferDetails> {
            every { offerToken } returns "promo-token"
            every { offerTags } returns listOf("launch50")
        }
        val baseOffer = mockk<ProductDetails.SubscriptionOfferDetails> {
            every { offerToken } returns "base-token"
            every { offerTags } returns emptyList()
        }
        val mockProductDetails = mockk<ProductDetails>(relaxed = true) {
            every { productId } returns BillingManager.PRODUCT_ID_YEARLY
            every { subscriptionOfferDetails } returns listOf(promoOffer, baseOffer)
        }
        setPrivateField(
            billingManager,
            "productDetailsCache",
            mapOf(BillingManager.PRODUCT_ID_YEARLY to mockProductDetails)
        )

        // Capture the token handed to setOfferToken (same static-builder seam as
        // the explicit-token test; real builders must not run on mocked details).
        val offerTokenSlot = slot<String>()
        mockkStatic(BillingFlowParams.ProductDetailsParams::class)
        mockkStatic(BillingFlowParams::class)
        try {
            val mockParamsBuilder = mockk<BillingFlowParams.ProductDetailsParams.Builder>(relaxed = true)
            every { BillingFlowParams.ProductDetailsParams.newBuilder() } returns mockParamsBuilder
            every { mockParamsBuilder.setProductDetails(any()) } returns mockParamsBuilder
            every { mockParamsBuilder.setOfferToken(capture(offerTokenSlot)) } returns mockParamsBuilder
            every { mockParamsBuilder.build() } returns mockk(relaxed = true)

            val mockFlowBuilder = mockk<BillingFlowParams.Builder>(relaxed = true)
            every { BillingFlowParams.newBuilder() } returns mockFlowBuilder
            every { mockFlowBuilder.setProductDetailsParamsList(any()) } returns mockFlowBuilder
            every { mockFlowBuilder.build() } returns mockk(relaxed = true)

            val launchOkResult = mockk<BillingResult> {
                every { responseCode } returns BillingClient.BillingResponseCode.OK
                every { debugMessage } returns ""
            }
            every { mockBillingClient.launchBillingFlow(any(), any()) } answers {
                val mockPurchase = mockk<Purchase>(relaxed = true) {
                    every { purchaseState } returns Purchase.PurchaseState.PURCHASED
                    every { isAcknowledged } returns true
                    every { products } returns listOf(BillingManager.PRODUCT_ID_YEARLY)
                }
                val successResult = mockk<BillingResult> {
                    every { responseCode } returns BillingClient.BillingResponseCode.OK
                    every { debugMessage } returns ""
                }
                capturedPurchasesUpdatedListener.onPurchasesUpdated(successResult, listOf(mockPurchase))
                launchOkResult
            }

            val result = billingManager.launchPurchaseFlow(mockActivity, BillingManager.PRODUCT_ID_YEARLY)

            assertTrue(result is PurchaseResult.Success)
            assertEquals("base-token", offerTokenSlot.captured)
        } finally {
            unmockkStatic(BillingFlowParams.ProductDetailsParams::class)
            unmockkStatic(BillingFlowParams::class)
        }
    }

    // ==================== Launch Offer Lifecycle Tests ====================

    @Test
    fun `failed product details refresh clears previously extracted launch offer`() {
        // Fail-closed contract: a promo extracted by an earlier successful query
        // must NOT stay live after a later query fails — LaunchPromoManager would
        // otherwise keep the offer gate open with data Play may no longer serve.
        billingManager.initialize()

        // Yearly product serving a live launch50 promo (trial + discounted intro + regular).
        val promoOffer = mockk<ProductDetails.SubscriptionOfferDetails> {
            every { offerToken } returns "promo-token"
            every { offerTags } returns listOf("launch50")
            every { pricingPhases } returns mockk {
                every { pricingPhaseList } returns listOf(
                    mockk {
                        every { priceAmountMicros } returns 19_990_000L
                        every { formattedPrice } returns "CHF 19.99"
                    },
                    mockk {
                        every { priceAmountMicros } returns 39_990_000L
                        every { formattedPrice } returns "CHF 39.99"
                    }
                )
            }
        }
        val yearlyDetails = mockk<ProductDetails>(relaxed = true) {
            every { productId } returns BillingManager.PRODUCT_ID_YEARLY
            every { subscriptionOfferDetails } returns listOf(promoOffer)
        }

        // First query succeeds with the promo; flipping the flag makes the
        // next query fail (simulates Play refusing the refresh later on).
        var failQuery = false
        every {
            mockBillingClient.queryProductDetailsAsync(
                ofType<QueryProductDetailsParams>(),
                ofType<ProductDetailsResponseListener>()
            )
        } answers {
            val callback = secondArg<ProductDetailsResponseListener>()
            if (failQuery) {
                val failResult = mockk<BillingResult> {
                    every { responseCode } returns BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE
                    every { debugMessage } returns "Service unavailable"
                }
                val emptyQueryResult = mockk<QueryProductDetailsResult> {
                    every { productDetailsList } returns emptyList()
                    every { unfetchedProductList } returns emptyList()
                }
                callback.onProductDetailsResponse(failResult, emptyQueryResult)
            } else {
                val okResult = mockk<BillingResult> {
                    every { responseCode } returns BillingClient.BillingResponseCode.OK
                }
                val queryResult = mockk<QueryProductDetailsResult> {
                    every { productDetailsList } returns listOf(yearlyDetails)
                    every { unfetchedProductList } returns emptyList()
                }
                callback.onProductDetailsResponse(okResult, queryResult)
            }
        }
        every {
            mockBillingClient.queryPurchasesAsync(
                ofType<QueryPurchasesParams>(),
                ofType<PurchasesResponseListener>()
            )
        } answers {
            val callback = secondArg<PurchasesResponseListener>()
            val okResult = mockk<BillingResult> {
                every { responseCode } returns BillingClient.BillingResponseCode.OK
            }
            callback.onQueryPurchasesResponse(okResult, emptyList())
        }

        val setupOk = mockk<BillingResult> {
            every { responseCode } returns BillingClient.BillingResponseCode.OK
            every { debugMessage } returns ""
        }

        // First Ready transition → successful query → promo extracted.
        capturedBillingClientStateListener.onBillingSetupFinished(setupOk)
        assertEquals("promo-token", billingManager.launchOffer.value?.offerToken)

        // Re-drive the queries via the same state listener; this time the
        // product query fails → offer must be cleared, not left stale.
        failQuery = true
        capturedBillingClientStateListener.onBillingSetupFinished(setupOk)

        assertNull(billingManager.launchOffer.value)
    }

    // ==================== Helper Methods ====================

    private fun setPrivateField(target: Any, fieldName: String, value: Any) {
        val field = target::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(target, value)
    }
}
