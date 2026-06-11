package com.paperless.scanner.data.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryProductDetailsResult
import com.android.billingclient.api.QueryPurchasesParams
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.paperless.scanner.R
import com.paperless.scanner.util.AppLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/**
 * BillingManager - Manages Premium subscription and billing with Google Play Billing Library 8.x
 *
 * **BILLING ARCHITECTURE:**
 * - Uses Google Play Billing Library 8.3.0
 * - Singleton lifecycle managed by Hilt
 * - Reactive state via Kotlin Flows
 * - Automatic purchase acknowledgement
 *
 * **FEATURES:**
 * - Subscription status tracking (active/inactive/expired/grace period)
 * - Purchase flow initiation with trial offer support
 * - Purchase restoration for reinstalls/device switches
 * - Reactive state updates via [subscriptionStatus] and [isSubscriptionActive] Flows
 * - Crashlytics integration for billing error tracking
 *
 * **SUBSCRIPTION PRODUCTS:**
 * - `paperless_ai_monthly` - Monthly subscription with 7-day trial
 * - `paperless_ai_yearly` - Yearly subscription with 14-day trial
 *
 * **USAGE:**
 * ```kotlin
 * // Initialize early in app lifecycle
 * billingManager.initialize()
 *
 * // Observe subscription status
 * billingManager.isSubscriptionActive.collect { isActive ->
 *     updatePremiumUI(isActive)
 * }
 *
 * // Launch purchase
 * val result = billingManager.launchPurchaseFlow(activity, PRODUCT_ID_MONTHLY)
 * ```
 *
 * @see PremiumFeatureManager For feature-level access control
 * @see SubscriptionStatus For subscription state model
 * @see PurchaseResult For purchase flow result handling
 */
@Singleton
class BillingManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        // <=11 chars so AppLogger's "Paperless.{tag}" stays within Android's 23-char tag cap (no truncation).
        private const val TAG = "Billing"
        const val PRODUCT_ID_MONTHLY = "paperless_ai_monthly"
        const val PRODUCT_ID_YEARLY = "paperless_ai_yearly"

        // Reconnect backoff schedule for transient Disconnected state.
        // Cap at 16s, infinite retries — each retry is a cheap binder call;
        // failure stays in Disconnected and consumers can observe billingState.
        private const val RECONNECT_BACKOFF_INITIAL_MS = 1_000L
        private const val RECONNECT_BACKOFF_CAP_MS = 16_000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _subscriptionStatus: MutableStateFlow<SubscriptionStatus> = MutableStateFlow(SubscriptionStatus.FREE)
    val subscriptionStatus: Flow<SubscriptionStatus> = _subscriptionStatus.asStateFlow()

    private val _isSubscriptionActive = MutableStateFlow(false)
    val isSubscriptionActive: Flow<Boolean> = _isSubscriptionActive.asStateFlow()

    // Explicit billing state machine — replaces the implicit
    // "billingClient != null && isReady" check that existed before.
    // Disconnected auto-retries with exponential backoff; Failed waits
    // for an explicit initialize() call (next user action).
    private val _billingState: MutableStateFlow<BillingState> = MutableStateFlow(BillingState.Uninitialized)
    val billingState: StateFlow<BillingState> = _billingState.asStateFlow()

    private var billingClient: BillingClient? = null
    private var productDetailsCache: Map<String, ProductDetails> = emptyMap()

    // Tracks the in-flight Disconnected-state reconnect coroutine so destroy()
    // can cancel it. A new disconnect cancels the previous schedule.
    private var reconnectJob: Job? = null

    // Store pending purchase continuation to resume after purchasesUpdatedListener callback
    private var pendingPurchaseContinuation: kotlin.coroutines.Continuation<PurchaseResult>? = null

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        AppLogger.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        AppLogger.d(TAG, "purchasesUpdatedListener triggered")
        AppLogger.d(TAG, "Response Code: ${billingResult.responseCode}")
        AppLogger.d(TAG, "Debug Message: ${billingResult.debugMessage}")
        AppLogger.d(TAG, "Purchases count: ${purchases?.size ?: 0}")
        AppLogger.d(TAG, "Has pending continuation: ${pendingPurchaseContinuation != null}")

        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                AppLogger.d(TAG, "✓ Purchase SUCCESS")
                purchases?.forEach { purchase ->
                    AppLogger.d(TAG, "  - Product: ${purchase.products}")
                    AppLogger.d(TAG, "  - State: ${purchase.purchaseState}")
                    AppLogger.d(TAG, "  - Acknowledged: ${purchase.isAcknowledged}")
                    handlePurchase(purchase)
                }
                // Resume pending purchase flow with success
                pendingPurchaseContinuation?.let {
                    AppLogger.d(TAG, "Resuming continuation with SUCCESS")
                    it.resume(PurchaseResult.Success)
                    pendingPurchaseContinuation = null
                } ?: AppLogger.w(TAG, "⚠ No pending continuation to resume!")
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                AppLogger.d(TAG, "✗ Purchase CANCELLED by user")
                // Resume pending purchase flow with cancelled
                pendingPurchaseContinuation?.let {
                    AppLogger.d(TAG, "Resuming continuation with CANCELLED")
                    it.resume(PurchaseResult.Cancelled)
                    pendingPurchaseContinuation = null
                } ?: AppLogger.w(TAG, "⚠ No pending continuation to resume!")
            }
            else -> {
                AppLogger.e(TAG, "✗ Purchase ERROR: ${billingResult.debugMessage}")
                // Resume pending purchase flow with error
                pendingPurchaseContinuation?.let {
                    AppLogger.d(TAG, "Resuming continuation with ERROR")
                    it.resume(PurchaseResult.Error(billingResult.debugMessage))
                    pendingPurchaseContinuation = null
                } ?: AppLogger.w(TAG, "⚠ No pending continuation to resume!")
            }
        }
        AppLogger.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    }

    /**
     * Initialize billing connection.
     * Should be called early in app lifecycle (Application.onCreate).
     *
     * Idempotent: safe to call multiple times. Allowed transitions:
     *  - Uninitialized → Initializing (first call)
     *  - Failed → Initializing (retry after a previous setup failure)
     *
     * Calls from Ready/Initializing/Disconnected are no-ops (Disconnected
     * self-heals via the reconnect backoff scheduler).
     */
    @Synchronized
    fun initialize() {
        when (val current = _billingState.value) {
            is BillingState.Ready,
            is BillingState.Initializing,
            is BillingState.Disconnected -> {
                AppLogger.w(TAG, "initialize() ignored — state is $current")
                return
            }
            is BillingState.Uninitialized,
            is BillingState.Failed -> {
                AppLogger.d(TAG, "initialize() — transitioning $current → Initializing")
            }
        }

        // Tear down any stale client (only possible when re-initializing from Failed).
        billingClient?.let {
            try {
                it.endConnection()
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to end stale connection before re-init", e)
            }
        }

        _billingState.value = BillingState.Initializing
        billingClient = BillingClient.newBuilder(context)
            .setListener(purchasesUpdatedListener)
            .enablePendingPurchases(
                PendingPurchasesParams.newBuilder()
                    .enableOneTimeProducts()
                    .build()
            )
            .build()

        connectToPlayBilling()
    }

    /**
     * Connect to Google Play Billing.
     *
     * Used for both initial connection (from initialize) and reconnect attempts
     * (scheduled by the Disconnected backoff handler). The same listener
     * handles state transitions in both cases.
     */
    private fun connectToPlayBilling() {
        val client = billingClient ?: run {
            AppLogger.e(TAG, "connectToPlayBilling() called with null client — transitioning to Failed")
            _billingState.value = BillingState.Failed("client was null at connect time")
            return
        }
        client.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    AppLogger.d(TAG, "Billing setup successful — state = Ready")
                    _billingState.value = BillingState.Ready
                    // Reaching Ready cancels any pending reconnect schedule from a
                    // previous Disconnected episode (success after retry).
                    reconnectJob?.cancel()
                    reconnectJob = null
                    // Query existing purchases
                    scope.launch {
                        queryPurchases()
                        queryProductDetails()
                    }
                } else {
                    val reason = billingResult.debugMessage.ifBlank { "code=${billingResult.responseCode}" }
                    // Always-on error stays generic; the raw reason/response code is
                    // release-sensitive (#39), so detail goes to the debug-gated log only.
                    AppLogger.e(TAG, "Billing setup failed — state = Failed (no auto-retry)")
                    AppLogger.d(TAG, "Billing setup failure reason: $reason")
                    _billingState.value = BillingState.Failed(reason)
                    // No auto-retry on setup failure — wait for next explicit
                    // initialize() call (e.g., user opens Premium screen).
                }
            }

            override fun onBillingServiceDisconnected() {
                AppLogger.w(TAG, "Billing service disconnected — scheduling backoff reconnect")
                scheduleReconnectWithBackoff()
            }
        })
    }

    /**
     * Schedules a reconnect attempt with exponential backoff capped at
     * [RECONNECT_BACKOFF_CAP_MS]. Each Disconnected state carries the
     * current retry attempt for consumers/debug. Cancels any previous
     * pending schedule.
     */
    private fun scheduleReconnectWithBackoff() {
        reconnectJob?.cancel()
        val previousAttempt = (_billingState.value as? BillingState.Disconnected)?.retryAttempt ?: 0
        val nextAttempt = previousAttempt + 1
        _billingState.value = BillingState.Disconnected(nextAttempt)

        // Exponential: 1s, 2s, 4s, 8s, 16s, 16s, 16s, ...
        val delayMs = (RECONNECT_BACKOFF_INITIAL_MS shl (nextAttempt - 1).coerceAtMost(4))
            .coerceAtMost(RECONNECT_BACKOFF_CAP_MS)

        AppLogger.d(TAG, "Reconnect attempt #$nextAttempt scheduled in ${delayMs}ms")
        reconnectJob = scope.launch {
            delay(delayMs)
            if (billingClient != null) {
                connectToPlayBilling()
            } else {
                AppLogger.w(TAG, "Reconnect aborted — billingClient was destroyed")
            }
        }
    }

    /**
     * Query product details for subscription products.
     */
    /**
     * Query product details for subscription products.
     *
     * Edge Case: BillingClient might be null or not ready when this is called.
     * We handle gracefully to prevent crashes.
     */
    private suspend fun queryProductDetails() {
        // Edge Case: BillingClient might be null
        val client = billingClient
        if (client == null) {
            AppLogger.e(TAG, "Cannot query product details: BillingClient is null")
            return
        }

        if (!client.isReady) {
            AppLogger.e(TAG, "Cannot query product details: BillingClient not ready")
            return
        }

        val productList = listOf(
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_ID_MONTHLY)
                .setProductType(BillingClient.ProductType.SUBS)
                .build(),
            QueryProductDetailsParams.Product.newBuilder()
                .setProductId(PRODUCT_ID_YEARLY)
                .setProductType(BillingClient.ProductType.SUBS)
                .build()
        )

        val params = QueryProductDetailsParams.newBuilder()
            .setProductList(productList)
            .build()

        suspendCancellableCoroutine { continuation ->
            client.queryProductDetailsAsync(params) { billingResult, queryProductDetailsResult ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    val productDetailsList = queryProductDetailsResult.productDetailsList
                    productDetailsCache = productDetailsList.associateBy { it.productId }
                    AppLogger.d(TAG, "Product details loaded: ${productDetailsList.size} products")

                    // Log unfetched products for debugging (Billing Library 8.x feature)
                    val unfetchedProducts = queryProductDetailsResult.unfetchedProductList
                    if (unfetchedProducts.isNotEmpty()) {
                        AppLogger.w(TAG, "Unfetched products: ${unfetchedProducts.size}")
                    }
                } else {
                    AppLogger.e(TAG, "Failed to load product details: ${billingResult.debugMessage}")

                    // Log to Crashlytics
                    try {
                        val crashlytics = FirebaseCrashlytics.getInstance()

                        crashlytics.log("Failed to query product details: ${billingResult.debugMessage}")
                        crashlytics.setCustomKey("billing_error_code", billingResult.responseCode)
                    } catch (e: Exception) {
                        AppLogger.w(TAG, "Failed to log product query error to Crashlytics", e)
                    }
                }
                continuation.resume(Unit)
            }
        }
    }

    /**
     * Query existing purchases and update subscription status.
     *
     * Edge Case: BillingClient might be null or not ready.
     */
    private suspend fun queryPurchases() {
        // Edge Case: BillingClient might be null
        val client = billingClient
        if (client == null) {
            AppLogger.e(TAG, "Cannot query purchases: BillingClient is null")
            return
        }

        if (!client.isReady) {
            AppLogger.e(TAG, "Cannot query purchases: BillingClient not ready")
            return
        }

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        suspendCancellableCoroutine { continuation ->
            client.queryPurchasesAsync(params) { billingResult, purchases ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    val activePurchase = purchases.firstOrNull { it.isAcknowledged && it.purchaseState == Purchase.PurchaseState.PURCHASED }

                    if (activePurchase != null) {
                        updateSubscriptionStatus(SubscriptionStatus.ACTIVE(System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000)) // Mock: +30 days
                        _isSubscriptionActive.value = true
                        AppLogger.d(TAG, "Active subscription found: ${activePurchase.products}")
                    } else {
                        updateSubscriptionStatus(SubscriptionStatus.FREE)
                        _isSubscriptionActive.value = false
                        AppLogger.d(TAG, "No active subscription")
                    }
                } else {
                    AppLogger.e(TAG, "Failed to query purchases: ${billingResult.debugMessage}")
                }
                continuation.resume(Unit)
            }
        }
    }

    /**
     * Check subscription status synchronously.
     * Used for immediate decision-making.
     */
    fun isSubscriptionActiveSync(): Boolean {
        return _isSubscriptionActive.value
    }

    /**
     * Current subscription status synchronously (mirrors [isSubscriptionActiveSync]).
     * Note: billing connects asynchronously after [initialize], so early-startup reads
     * see FREE until the first purchase query lands — observe [subscriptionStatus]
     * for updates (#296).
     */
    fun subscriptionStatusSync(): SubscriptionStatus = _subscriptionStatus.value

    /**
     * Launch purchase flow for Premium subscription.
     *
     * **Trial Support:**
     * If a trial offer is configured in Play Console and marked as "Default",
     * it will be automatically selected by `firstOrNull()`. This is Google's
     * recommended pattern for handling trial offers.
     *
     * **Play Console Setup Required:**
     * - Monthly: 7-day trial (Product ID: `paperless_ai_monthly`)
     * - Yearly: 14-day trial (Product ID: `paperless_ai_yearly`)
     *
     * **Important:**
     * This function suspends until the purchase flow completes (success/cancel/error).
     * The actual result comes from purchasesUpdatedListener callback.
     *
     * @param activity Activity required for billing flow
     * @param productId Product ID from Play Console (e.g., "paperless_ai_monthly")
     * @return PurchaseResult indicating success, cancellation, or error
     */
    suspend fun launchPurchaseFlow(activity: Activity, productId: String): PurchaseResult {
        AppLogger.d(TAG, "════════════════════════════════════════════════")
        AppLogger.d(TAG, "launchPurchaseFlow called")
        AppLogger.d(TAG, "Product ID: $productId")

        // CRITICAL: Check if BillingClient is ready before launching flow
        // Prevents NullPointerException in ProxyBillingActivity.onCreate
        // when PendingIntent.getIntentSender() is called on null object
        if (billingClient?.isReady != true) {
            AppLogger.e(TAG, "✗ BillingClient not ready!")
            AppLogger.e(TAG, "  BillingClient: ${if (billingClient == null) "null" else "initialized"}")
            AppLogger.e(TAG, "  isReady: ${billingClient?.isReady}")

            // Log to Crashlytics for monitoring
            try {
                val crashlytics = FirebaseCrashlytics.getInstance()
                    
                crashlytics.log("BillingClient not ready when launchPurchaseFlow called")
                crashlytics.setCustomKey("billing_client_null", billingClient == null)
                crashlytics.setCustomKey("billing_client_ready", billingClient?.isReady ?: false)
                crashlytics.setCustomKey("product_id", productId)
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to log to Crashlytics", e)
            }

            return PurchaseResult.Error(context.getString(R.string.billing_error_not_ready))
        }

        // CRITICAL: specific fix for "Unable to start activity ... ProxyBillingActivity: java.lang.NullPointerException"
        // This crash happens if the activity is finishing or destroyed when the billing flow tries to launch.
        if (activity.isFinishing || activity.isDestroyed) {
             AppLogger.e(TAG, "✗ Activity is finishing or destroyed, cannot launch billing flow!")
             return PurchaseResult.Error(context.getString(R.string.billing_error_activity_finishing))
        }

        AppLogger.d(TAG, "✓ BillingClient is ready")

        // Check if there's already a pending purchase
        if (pendingPurchaseContinuation != null) {
            AppLogger.e(TAG, "✗ Purchase already in progress!")
            return PurchaseResult.Error(context.getString(R.string.billing_error_purchase_in_progress))
        }

        // Edge Case: ProductDetails cache might be empty if initial query failed
        // Retry loading product details before failing
        var productDetails = productDetailsCache[productId]
        if (productDetails == null) {
            AppLogger.w(TAG, "⚠ Product not found in cache, attempting to reload...")
            AppLogger.d(TAG, "Available products before reload: ${productDetailsCache.keys}")

            // Try to reload product details
            try {
                queryProductDetails()
                productDetails = productDetailsCache[productId]
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to reload product details", e)
            }

            // Still not found after retry?
            if (productDetails == null) {
                AppLogger.e(TAG, "✗ Product not found even after retry!")
                AppLogger.d(TAG, "Available products after retry: ${productDetailsCache.keys}")

                // Log to Crashlytics
                try {
                    val crashlytics = FirebaseCrashlytics.getInstance()
                        
                    crashlytics.log("Product not found: $productId")
                    crashlytics.setCustomKey("product_id_requested", productId)
                    crashlytics.setCustomKey("available_products", productDetailsCache.keys.joinToString(","))
                    crashlytics.recordException(Exception("ProductDetails not found for $productId"))
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Failed to log missing product to Crashlytics", e)
                }

                return PurchaseResult.Error(context.getString(R.string.billing_error_product_not_found))
            } else {
                AppLogger.d(TAG, "✓ Product loaded successfully after retry")
            }
        }

        AppLogger.d(TAG, "✓ Product found: ${productDetails.name}")

        // Get first offer token (trial offer if configured as default in Play Console)
        val offerToken = productDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken
        if (offerToken == null) {
            AppLogger.e(TAG, "✗ No subscription offers available!")
            return PurchaseResult.Error(context.getString(R.string.billing_error_no_offers))
        }

        AppLogger.d(TAG, "✓ Offer token found")
        AppLogger.d(TAG, "Offers available: ${productDetails.subscriptionOfferDetails?.size}")

        val productDetailsParamsList = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(productDetails)
                .setOfferToken(offerToken)
                .build()
        )

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)
            .build()

        // Run the whole purchase handshake on Main: Google Play Billing requires
        // launchBillingFlow on the main thread, and withContext(Main) is a no-op when the
        // caller is already on Main (the normal viewModelScope/Dispatchers.Main path). (#274)
        return withContext(Dispatchers.Main) { suspendCancellableCoroutine { continuation ->
            // Store continuation to be resumed by purchasesUpdatedListener
            pendingPurchaseContinuation = continuation
            AppLogger.d(TAG, "✓ Pending continuation stored")

            // Launch billing flow
            AppLogger.d(TAG, "Launching billing flow...")
            // Guaranteed on the main thread by the enclosing withContext(Dispatchers.Main),
            // so this is a real synchronous BillingResult (Play Billing requires Main). (#274)
            val billingResult = billingClient?.launchBillingFlow(activity, billingFlowParams)

            AppLogger.d(TAG, "launchBillingFlow returned:")
            AppLogger.d(TAG, "  Response Code: ${billingResult?.responseCode}")
            AppLogger.d(TAG, "  Debug Message: ${billingResult?.debugMessage}")

            // Log to Crashlytics for monitoring
            try {
                val crashlytics = FirebaseCrashlytics.getInstance()
                    
                crashlytics.log("BillingFlow launch attempt: ${billingResult?.responseCode}")
                crashlytics.setCustomKey("billing_flow_response_code", billingResult?.responseCode ?: -1)
                crashlytics.setCustomKey("billing_flow_product_id", productId)
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to log billing flow to Crashlytics", e)
            }

            // Only handle flow launch errors here
            // Success/Cancel/Error from actual purchase handled in purchasesUpdatedListener
            if (billingResult?.responseCode != BillingClient.BillingResponseCode.OK) {
                AppLogger.e(TAG, "✗ Failed to launch billing flow!")

                // Log error to Crashlytics
                try {
                    val crashlytics = FirebaseCrashlytics.getInstance()
                        
                    crashlytics.recordException(
                        Exception("BillingFlow launch failed: ${billingResult?.debugMessage}")
                    )
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Failed to record exception to Crashlytics", e)
                }

                pendingPurchaseContinuation = null
                continuation.resume(PurchaseResult.Error(billingResult?.debugMessage ?: context.getString(R.string.billing_error_launch_failed)))
            } else {
                AppLogger.d(TAG, "✓ Billing flow launched successfully")
                AppLogger.d(TAG, "Waiting for purchasesUpdatedListener callback...")
            }
            // If OK, wait for purchasesUpdatedListener to resume the continuation

            // Handle cancellation
            continuation.invokeOnCancellation {
                AppLogger.d(TAG, "⚠ Purchase flow cancelled (continuation)")
                pendingPurchaseContinuation = null
            }
        } }
    }

    /**
     * Restore previous purchases.
     * Useful when user reinstalls app or switches devices.
     *
     * Bug-fix history: previously `billingClient?.queryPurchasesAsync(...)`
     * was a silent no-op when the client was null/not-ready, leaving the
     * continuation forever-suspended. The Ready-state gate now resolves
     * with an explicit Error result so callers never hang.
     */
    suspend fun restorePurchases(): RestoreResult {
        val client = billingClient
        if (_billingState.value !is BillingState.Ready || client == null || !client.isReady) {
            // Always-on error stays generic; state may be Failed(reason), which is
            // release-sensitive (#39), so the detail goes to the debug-gated log.
            AppLogger.e(TAG, "restorePurchases: client not Ready")
            AppLogger.d(TAG, "restorePurchases blocked, state=${_billingState.value}")
            return RestoreResult.Error(context.getString(R.string.billing_error_not_ready))
        }

        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        return suspendCancellableCoroutine { continuation ->
            client.queryPurchasesAsync(params) { billingResult, purchases ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    val activePurchases = purchases.filter {
                        it.purchaseState == Purchase.PurchaseState.PURCHASED
                    }

                    // Acknowledge any unacknowledged purchases
                    scope.launch {
                        activePurchases.forEach { purchase ->
                            if (!purchase.isAcknowledged) {
                                acknowledgePurchase(purchase)
                            }
                        }
                    }

                    if (activePurchases.isNotEmpty()) {
                        updateSubscriptionStatus(SubscriptionStatus.ACTIVE(System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000))
                        _isSubscriptionActive.value = true
                        continuation.resume(RestoreResult.Success(activePurchases.size))
                    } else {
                        continuation.resume(RestoreResult.NoPurchasesFound)
                    }
                } else {
                    continuation.resume(RestoreResult.Error(billingResult.debugMessage))
                }
            }
        }
    }

    /**
     * Handle new purchase.
     *
     * Edge Case: PENDING purchases occur when payment processing is delayed
     * (e.g., bank transfer, certain payment methods in some countries).
     * Google requires acknowledging within 3 days or refund is triggered.
     */
    private fun handlePurchase(purchase: Purchase) {
        when (purchase.purchaseState) {
            Purchase.PurchaseState.PURCHASED -> {
                AppLogger.d(TAG, "Purchase state: PURCHASED")
                if (!purchase.isAcknowledged) {
                    scope.launch {
                        acknowledgePurchase(purchase)
                    }
                }
                updateSubscriptionStatus(SubscriptionStatus.ACTIVE(System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000))
                _isSubscriptionActive.value = true
                AppLogger.d(TAG, "Purchase successful: ${purchase.products}")

                // Log to Crashlytics
                try {
                    val crashlytics = FirebaseCrashlytics.getInstance()
                        
                    crashlytics.log("Purchase completed: ${purchase.products}")
                    crashlytics.setCustomKey("purchase_state", "PURCHASED")
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Failed to log purchase to Crashlytics", e)
                }
            }
            Purchase.PurchaseState.PENDING -> {
                AppLogger.d(TAG, "Purchase state: PENDING (payment being processed)")
                // Don't grant access yet, but inform user
                updateSubscriptionStatus(SubscriptionStatus.FREE)
                _isSubscriptionActive.value = false

                // Log to Crashlytics for monitoring
                try {
                    val crashlytics = FirebaseCrashlytics.getInstance()
                        
                    crashlytics.log("Purchase PENDING: ${purchase.products}")
                    crashlytics.setCustomKey("purchase_state", "PENDING")
                    // Purchase token is sensitive (can be replayed to mutate the purchase).
                    // Track presence/length only — never the value. CLAUDE.md: no secrets in logs.
                    crashlytics.setCustomKey("purchase_token_present", purchase.purchaseToken.isNotEmpty())
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Failed to log pending purchase to Crashlytics", e)
                }
            }
            Purchase.PurchaseState.UNSPECIFIED_STATE -> {
                AppLogger.w(TAG, "Purchase state: UNSPECIFIED_STATE")
                // Log to Crashlytics
                try {
                    val crashlytics = FirebaseCrashlytics.getInstance()
                        
                    crashlytics.log("Purchase UNSPECIFIED_STATE: ${purchase.products}")
                    crashlytics.setCustomKey("purchase_state", "UNSPECIFIED_STATE")
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Failed to log unspecified purchase to Crashlytics", e)
                }
            }
            else -> {
                AppLogger.w(TAG, "Unknown purchase state: ${purchase.purchaseState}")
            }
        }
    }

    /**
     * Acknowledge purchase.
     * Required within 3 days or Google will refund the purchase.
     */
    private suspend fun acknowledgePurchase(purchase: Purchase) {
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()

        suspendCancellableCoroutine { continuation ->
            billingClient?.acknowledgePurchase(params) { billingResult ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    AppLogger.d(TAG, "Purchase acknowledged: ${purchase.products}")
                } else {
                    AppLogger.e(TAG, "Failed to acknowledge purchase: ${billingResult.debugMessage}")
                }
                continuation.resume(Unit)
            }
        }
    }

    /**
     * Update subscription status and notify observers.
     */
    private fun updateSubscriptionStatus(status: SubscriptionStatus) {
        _subscriptionStatus.value = status
        _isSubscriptionActive.value = status is SubscriptionStatus.ACTIVE
    }

    /**
     * Get detailed subscription information for UI display.
     * Returns null if no active subscription or if data cannot be retrieved.
     *
     * @return SubscriptionInfo with product details, price, renewal date, and status
     */
    suspend fun getSubscriptionInfo(): SubscriptionInfo? {
        // Check if billing is ready
        val client = billingClient
        if (client == null || !client.isReady) {
            AppLogger.w(TAG, "Cannot get subscription info: BillingClient not ready")
            return null
        }

        // Query purchases
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        return suspendCancellableCoroutine { continuation ->
            client.queryPurchasesAsync(params) { billingResult, purchases ->
                if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                    AppLogger.e(TAG, "Failed to query purchases for subscription info")
                    continuation.resume(null)
                    return@queryPurchasesAsync
                }

                // Find active purchase
                val activePurchase = purchases.firstOrNull {
                    it.isAcknowledged && it.purchaseState == Purchase.PurchaseState.PURCHASED
                }

                if (activePurchase == null) {
                    continuation.resume(null)
                    return@queryPurchasesAsync
                }

                // Get product ID
                val productId = activePurchase.products.firstOrNull() ?: run {
                    continuation.resume(null)
                    return@queryPurchasesAsync
                }

                // Get product details from cache
                val productDetails = productDetailsCache[productId]
                if (productDetails == null) {
                    // Always-on warning stays identifier-free; productId is release-sensitive (#39).
                    AppLogger.w(TAG, "Product details not found in cache")
                    AppLogger.d(TAG, "Missing product details for productId=$productId")
                    continuation.resume(null)
                    return@queryPurchasesAsync
                }

                // Extract price
                val offerDetails = productDetails.subscriptionOfferDetails?.firstOrNull()
                val pricePhase = offerDetails?.pricingPhases?.pricingPhaseList?.firstOrNull()
                val formattedPrice = pricePhase?.formattedPrice ?: "N/A"

                // Determine product name
                val isMonthly = productId == PRODUCT_ID_MONTHLY
                val productName = if (isMonthly) context.getString(R.string.billing_monthly) else context.getString(R.string.billing_yearly)

                // Calculate renewal date (purchaseTime + subscription period)
                // Note: Google Play doesn't provide exact renewal date in Purchase object
                // We estimate based on purchase time + billing period
                val purchaseTimeMs = activePurchase.purchaseTime
                val billingPeriodMs = if (isMonthly) {
                    30L * 24 * 60 * 60 * 1000 // 30 days
                } else {
                    365L * 24 * 60 * 60 * 1000 // 365 days
                }
                val renewalDateMs = purchaseTimeMs + billingPeriodMs

                // Determine status
                val status = if (activePurchase.isAutoRenewing) {
                    SubscriptionInfoStatus.ACTIVE
                } else {
                    SubscriptionInfoStatus.CANCELLED // Cancelled but still valid
                }

                val subscriptionInfo = SubscriptionInfo(
                    productId = productId,
                    productName = productName,
                    price = formattedPrice,
                    renewalDateMs = renewalDateMs,
                    status = status,
                    isMonthly = isMonthly
                )

                continuation.resume(subscriptionInfo)
            }
        }
    }

    /**
     * Clean up billing client on destroy.
     *
     * Edge Case: If purchase flow is active when destroy() is called,
     * the pendingPurchaseContinuation would hang indefinitely.
     * We cancel it gracefully to prevent memory leaks.
     */
    fun destroy() {
        AppLogger.d(TAG, "destroy() called")

        // Cancel pending reconnect schedule (if any) — prevents the backoff
        // coroutine from trying to reconnect after the client is gone.
        reconnectJob?.cancel()
        reconnectJob = null

        // Cancel any pending purchase flow to prevent continuation leak
        pendingPurchaseContinuation?.let {
            AppLogger.w(TAG, "Cancelling pending purchase continuation on destroy")
            try {
                it.resume(PurchaseResult.Error(context.getString(R.string.billing_error_disconnected)))
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to resume continuation on destroy", e)
            }
            pendingPurchaseContinuation = null
        }

        // Safely disconnect billing client
        try {
            billingClient?.endConnection()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Error disconnecting billing client", e)
        }

        billingClient = null
        _billingState.value = BillingState.Uninitialized
        // #142: cancel the manager's in-flight coroutines (reconnect/backoff, queryPurchases,
        // queryProductDetails) so they are torn down rather than leaked. cancelChildren() — NOT
        // scope.cancel() — keeps the singleton scope reusable: destroy() resets state to
        // Uninitialized, so a later initialize() can relaunch work on this same scope.
        scope.coroutineContext.cancelChildren()
        AppLogger.d(TAG, "BillingClient destroyed")
    }
}

/**
 * Explicit billing state machine for [BillingManager].
 *
 * Transitions:
 * ```
 * Uninitialized ──initialize()──> Initializing ──onBillingSetupFinished(OK)──> Ready
 *                                              ──onBillingSetupFinished(err)─> Failed (terminal until next initialize())
 *
 * Ready ──onBillingServiceDisconnected──> Disconnected ──backoff retry──> Ready (success)
 *                                                                       ──backoff retry──> Disconnected (next attempt)
 *
 * Failed ──initialize()──> Initializing (explicit retry path)
 * ```
 *
 * - **Disconnected** auto-retries with exponential backoff (1s → 16s cap, infinite attempts).
 * - **Failed** is terminal until the next explicit [BillingManager.initialize] call.
 */
sealed class BillingState {
    /** Initial state before [BillingManager.initialize] is called. */
    data object Uninitialized : BillingState()

    /** [BillingClient.startConnection] in flight — awaiting [BillingClientStateListener.onBillingSetupFinished]. */
    data object Initializing : BillingState()

    /** Billing client connected and ready to accept purchase / query calls. */
    data object Ready : BillingState()

    /**
     * Setup failed permanently. No auto-retry — next [BillingManager.initialize] call
     * (typically triggered by user reopening the Premium screen) will retry.
     * @property reason Debug message from the failed [BillingResult] (for Crashlytics).
     */
    data class Failed(val reason: String) : BillingState()

    /**
     * Service disconnected mid-session. Backoff reconnect is scheduled.
     * @property retryAttempt 1-based counter; resets on transition to [Ready].
     */
    data class Disconnected(val retryAttempt: Int) : BillingState()
}

/**
 * Subscription status states.
 */
sealed class SubscriptionStatus {
    data object FREE : SubscriptionStatus()
    data class ACTIVE(val expiryDateMs: Long) : SubscriptionStatus()
    data object EXPIRED : SubscriptionStatus()
    data object GRACE_PERIOD : SubscriptionStatus() // Payment failed but still has access
    data object ON_HOLD : SubscriptionStatus() // Payment failed, access revoked
}

/**
 * Analytics dimension name for this status (Crashlytics key `subscription_status`,
 * Firebase user property, and the `subscriptionType` AI-usage dimension, #296).
 *
 * Only "free" and "premium" are currently producible — ACTIVE does not retain which
 * product (monthly/yearly) was purchased, and EXPIRED/GRACE_PERIOD/ON_HOLD have no
 * producer yet. The exhaustive `when` is compiler-enforced future-proofing.
 */
fun SubscriptionStatus.analyticsName(): String = when (this) {
    SubscriptionStatus.FREE -> "free"
    is SubscriptionStatus.ACTIVE -> "premium"
    SubscriptionStatus.EXPIRED -> "expired"
    SubscriptionStatus.GRACE_PERIOD -> "grace_period"
    SubscriptionStatus.ON_HOLD -> "on_hold"
}

/**
 * Result of purchase flow.
 */
sealed class PurchaseResult {
    data object Success : PurchaseResult()
    data object Cancelled : PurchaseResult()
    data class Error(val message: String) : PurchaseResult()
}

/**
 * Result of restore purchases.
 */
sealed class RestoreResult {
    data class Success(val restoredCount: Int) : RestoreResult()
    data object NoPurchasesFound : RestoreResult()
    data class Error(val message: String) : RestoreResult()
}

/**
 * Detailed subscription information for UI display.
 * Used in subscription management screens.
 */
data class SubscriptionInfo(
    val productId: String,                    // "paperless_ai_monthly" or "paperless_ai_yearly"
    val productName: String,                  // "Monthly" or "Yearly"
    val price: String,                        // "2,99 €/Monat" or "29,99 €/Jahr"
    val renewalDateMs: Long?,                 // Next renewal date in milliseconds (null if unknown)
    val status: SubscriptionInfoStatus,       // ACTIVE, PAUSED, CANCELLED, EXPIRED
    val isMonthly: Boolean                    // true if monthly, false if yearly
)

/**
 * Subscription status for display purposes.
 */
enum class SubscriptionInfoStatus {
    ACTIVE,      // Subscription is active and will auto-renew
    PAUSED,      // Subscription paused (Google Play feature)
    CANCELLED,   // Cancelled but still valid until expiry
    EXPIRED      // Expired, no longer valid
}
