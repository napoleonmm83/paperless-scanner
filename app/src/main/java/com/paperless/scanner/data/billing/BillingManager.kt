package com.paperless.scanner.data.billing

import android.app.Activity
import android.content.Context
import android.util.Log
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
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
        private const val TAG = "BillingManager"
        const val PRODUCT_ID_MONTHLY = "paperless_ai_monthly"
        const val PRODUCT_ID_YEARLY = "paperless_ai_yearly"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _subscriptionStatus: MutableStateFlow<SubscriptionStatus> = MutableStateFlow(SubscriptionStatus.FREE)
    val subscriptionStatus: Flow<SubscriptionStatus> = _subscriptionStatus.asStateFlow()

    private val _isSubscriptionActive = MutableStateFlow(false)
    val isSubscriptionActive: Flow<Boolean> = _isSubscriptionActive.asStateFlow()

    private var billingClient: BillingClient? = null
    private var productDetailsCache: Map<String, ProductDetails> = emptyMap()

    // Store pending purchase continuation to resume after purchasesUpdatedListener callback
    private var pendingPurchaseContinuation: kotlin.coroutines.Continuation<PurchaseResult>? = null

    private val purchasesUpdatedListener = PurchasesUpdatedListener { billingResult, purchases ->
        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
        Log.d(TAG, "purchasesUpdatedListener triggered")
        Log.d(TAG, "Response Code: ${billingResult.responseCode}")
        Log.d(TAG, "Debug Message: ${billingResult.debugMessage}")
        Log.d(TAG, "Purchases count: ${purchases?.size ?: 0}")
        Log.d(TAG, "Has pending continuation: ${pendingPurchaseContinuation != null}")

        when (billingResult.responseCode) {
            BillingClient.BillingResponseCode.OK -> {
                Log.d(TAG, "✓ Purchase SUCCESS")
                purchases?.forEach { purchase ->
                    Log.d(TAG, "  - Product: ${purchase.products}")
                    Log.d(TAG, "  - State: ${purchase.purchaseState}")
                    Log.d(TAG, "  - Acknowledged: ${purchase.isAcknowledged}")
                    handlePurchase(purchase)
                }
                // Resume pending purchase flow with success
                pendingPurchaseContinuation?.let {
                    Log.d(TAG, "Resuming continuation with SUCCESS")
                    it.resume(PurchaseResult.Success)
                    pendingPurchaseContinuation = null
                } ?: Log.w(TAG, "⚠ No pending continuation to resume!")
            }
            BillingClient.BillingResponseCode.USER_CANCELED -> {
                Log.d(TAG, "✗ Purchase CANCELLED by user")
                // Resume pending purchase flow with cancelled
                pendingPurchaseContinuation?.let {
                    Log.d(TAG, "Resuming continuation with CANCELLED")
                    it.resume(PurchaseResult.Cancelled)
                    pendingPurchaseContinuation = null
                } ?: Log.w(TAG, "⚠ No pending continuation to resume!")
            }
            else -> {
                Log.e(TAG, "✗ Purchase ERROR: ${billingResult.debugMessage}")
                // Resume pending purchase flow with error
                pendingPurchaseContinuation?.let {
                    Log.d(TAG, "Resuming continuation with ERROR")
                    it.resume(PurchaseResult.Error(billingResult.debugMessage))
                    pendingPurchaseContinuation = null
                } ?: Log.w(TAG, "⚠ No pending continuation to resume!")
            }
        }
        Log.d(TAG, "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━")
    }

    /**
     * Initialize billing connection.
     * Should be called early in app lifecycle (Application.onCreate).
     */
    fun initialize() {
        if (billingClient != null) {
            Log.w(TAG, "BillingClient already initialized")
            return
        }

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
     */
    private fun connectToPlayBilling() {
        billingClient?.startConnection(object : BillingClientStateListener {
            override fun onBillingSetupFinished(billingResult: BillingResult) {
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    Log.d(TAG, "Billing setup successful")
                    // Query existing purchases
                    scope.launch {
                        queryPurchases()
                        queryProductDetails()
                    }
                } else {
                    Log.e(TAG, "Billing setup failed: ${billingResult.debugMessage}")
                }
            }

            override fun onBillingServiceDisconnected() {
                Log.w(TAG, "Billing service disconnected")
                // Try reconnecting
                connectToPlayBilling()
            }
        })
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
            Log.e(TAG, "Cannot query product details: BillingClient is null")
            return
        }

        if (!client.isReady) {
            Log.e(TAG, "Cannot query product details: BillingClient not ready")
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
                    Log.d(TAG, "Product details loaded: ${productDetailsList.size} products")

                    // Log unfetched products for debugging (Billing Library 8.x feature)
                    val unfetchedProducts = queryProductDetailsResult.unfetchedProductList
                    if (unfetchedProducts.isNotEmpty()) {
                        Log.w(TAG, "Unfetched products: ${unfetchedProducts.size}")
                    }
                } else {
                    Log.e(TAG, "Failed to load product details: ${billingResult.debugMessage}")

                    // Log to Crashlytics
                    try {
                        val crashlytics = FirebaseCrashlytics.getInstance()

                        crashlytics.log("Failed to query product details: ${billingResult.debugMessage}")
                        crashlytics.setCustomKey("billing_error_code", billingResult.responseCode)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to log product query error to Crashlytics", e)
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
            Log.e(TAG, "Cannot query purchases: BillingClient is null")
            return
        }

        if (!client.isReady) {
            Log.e(TAG, "Cannot query purchases: BillingClient not ready")
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
                        Log.d(TAG, "Active subscription found: ${activePurchase.products}")
                    } else {
                        updateSubscriptionStatus(SubscriptionStatus.FREE)
                        _isSubscriptionActive.value = false
                        Log.d(TAG, "No active subscription")
                    }
                } else {
                    Log.e(TAG, "Failed to query purchases: ${billingResult.debugMessage}")
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
        Log.d(TAG, "════════════════════════════════════════════════")
        Log.d(TAG, "launchPurchaseFlow called")
        Log.d(TAG, "Product ID: $productId")

        // CRITICAL: Check if BillingClient is ready before launching flow
        // Prevents NullPointerException in ProxyBillingActivity.onCreate
        // when PendingIntent.getIntentSender() is called on null object
        if (billingClient?.isReady != true) {
            Log.e(TAG, "✗ BillingClient not ready!")
            Log.e(TAG, "  BillingClient: ${if (billingClient == null) "null" else "initialized"}")
            Log.e(TAG, "  isReady: ${billingClient?.isReady}")

            // Log to Crashlytics for monitoring
            try {
                val crashlytics = FirebaseCrashlytics.getInstance()
                    
                crashlytics.log("BillingClient not ready when launchPurchaseFlow called")
                crashlytics.setCustomKey("billing_client_null", billingClient == null)
                crashlytics.setCustomKey("billing_client_ready", billingClient?.isReady ?: false)
                crashlytics.setCustomKey("product_id", productId)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to log to Crashlytics", e)
            }

            return PurchaseResult.Error("Billing service not ready. Please wait a moment and try again.")
        }

        Log.d(TAG, "✓ BillingClient is ready")

        // Check if there's already a pending purchase
        if (pendingPurchaseContinuation != null) {
            Log.e(TAG, "✗ Purchase already in progress!")
            return PurchaseResult.Error("Purchase already in progress")
        }

        // Edge Case: ProductDetails cache might be empty if initial query failed
        // Retry loading product details before failing
        var productDetails = productDetailsCache[productId]
        if (productDetails == null) {
            Log.w(TAG, "⚠ Product not found in cache, attempting to reload...")
            Log.d(TAG, "Available products before reload: ${productDetailsCache.keys}")

            // Try to reload product details
            try {
                queryProductDetails()
                productDetails = productDetailsCache[productId]
            } catch (e: Exception) {
                Log.e(TAG, "Failed to reload product details", e)
            }

            // Still not found after retry?
            if (productDetails == null) {
                Log.e(TAG, "✗ Product not found even after retry!")
                Log.d(TAG, "Available products after retry: ${productDetailsCache.keys}")

                // Log to Crashlytics
                try {
                    val crashlytics = FirebaseCrashlytics.getInstance()
                        
                    crashlytics.log("Product not found: $productId")
                    crashlytics.setCustomKey("product_id_requested", productId)
                    crashlytics.setCustomKey("available_products", productDetailsCache.keys.joinToString(","))
                    crashlytics.recordException(Exception("ProductDetails not found for $productId"))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to log missing product to Crashlytics", e)
                }

                return PurchaseResult.Error("Product not found. Please try again later.")
            } else {
                Log.d(TAG, "✓ Product loaded successfully after retry")
            }
        }

        Log.d(TAG, "✓ Product found: ${productDetails.name}")

        // Get first offer token (trial offer if configured as default in Play Console)
        val offerToken = productDetails.subscriptionOfferDetails?.firstOrNull()?.offerToken
        if (offerToken == null) {
            Log.e(TAG, "✗ No subscription offers available!")
            return PurchaseResult.Error("No subscription offers available")
        }

        Log.d(TAG, "✓ Offer token found")
        Log.d(TAG, "Offers available: ${productDetails.subscriptionOfferDetails?.size}")

        val productDetailsParamsList = listOf(
            BillingFlowParams.ProductDetailsParams.newBuilder()
                .setProductDetails(productDetails)
                .setOfferToken(offerToken)
                .build()
        )

        val billingFlowParams = BillingFlowParams.newBuilder()
            .setProductDetailsParamsList(productDetailsParamsList)
            .build()

        return suspendCancellableCoroutine { continuation ->
            // Store continuation to be resumed by purchasesUpdatedListener
            pendingPurchaseContinuation = continuation
            Log.d(TAG, "✓ Pending continuation stored")

            // Launch billing flow
            Log.d(TAG, "Launching billing flow...")
            val billingResult = billingClient?.launchBillingFlow(activity, billingFlowParams)

            Log.d(TAG, "launchBillingFlow returned:")
            Log.d(TAG, "  Response Code: ${billingResult?.responseCode}")
            Log.d(TAG, "  Debug Message: ${billingResult?.debugMessage}")

            // Log to Crashlytics for monitoring
            try {
                val crashlytics = FirebaseCrashlytics.getInstance()
                    
                crashlytics.log("BillingFlow launch attempt: ${billingResult?.responseCode}")
                crashlytics.setCustomKey("billing_flow_response_code", billingResult?.responseCode ?: -1)
                crashlytics.setCustomKey("billing_flow_product_id", productId)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to log billing flow to Crashlytics", e)
            }

            // Only handle flow launch errors here
            // Success/Cancel/Error from actual purchase handled in purchasesUpdatedListener
            if (billingResult?.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.e(TAG, "✗ Failed to launch billing flow!")

                // Log error to Crashlytics
                try {
                    val crashlytics = FirebaseCrashlytics.getInstance()
                        
                    crashlytics.recordException(
                        Exception("BillingFlow launch failed: ${billingResult?.debugMessage}")
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to record exception to Crashlytics", e)
                }

                pendingPurchaseContinuation = null
                continuation.resume(PurchaseResult.Error(billingResult?.debugMessage ?: "Failed to launch billing flow"))
            } else {
                Log.d(TAG, "✓ Billing flow launched successfully")
                Log.d(TAG, "Waiting for purchasesUpdatedListener callback...")
            }
            // If OK, wait for purchasesUpdatedListener to resume the continuation

            // Handle cancellation
            continuation.invokeOnCancellation {
                Log.d(TAG, "⚠ Purchase flow cancelled (continuation)")
                pendingPurchaseContinuation = null
            }
        }
    }

    /**
     * Restore previous purchases.
     * Useful when user reinstalls app or switches devices.
     */
    suspend fun restorePurchases(): RestoreResult {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        return suspendCancellableCoroutine { continuation ->
            billingClient?.queryPurchasesAsync(params) { billingResult, purchases ->
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
                Log.d(TAG, "Purchase state: PURCHASED")
                if (!purchase.isAcknowledged) {
                    scope.launch {
                        acknowledgePurchase(purchase)
                    }
                }
                updateSubscriptionStatus(SubscriptionStatus.ACTIVE(System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000))
                _isSubscriptionActive.value = true
                Log.d(TAG, "Purchase successful: ${purchase.products}")

                // Log to Crashlytics
                try {
                    val crashlytics = FirebaseCrashlytics.getInstance()
                        
                    crashlytics.log("Purchase completed: ${purchase.products}")
                    crashlytics.setCustomKey("purchase_state", "PURCHASED")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to log purchase to Crashlytics", e)
                }
            }
            Purchase.PurchaseState.PENDING -> {
                Log.d(TAG, "Purchase state: PENDING (payment being processed)")
                // Don't grant access yet, but inform user
                updateSubscriptionStatus(SubscriptionStatus.FREE)
                _isSubscriptionActive.value = false

                // Log to Crashlytics for monitoring
                try {
                    val crashlytics = FirebaseCrashlytics.getInstance()
                        
                    crashlytics.log("Purchase PENDING: ${purchase.products}")
                    crashlytics.setCustomKey("purchase_state", "PENDING")
                    crashlytics.setCustomKey("purchase_token", purchase.purchaseToken)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to log pending purchase to Crashlytics", e)
                }
            }
            Purchase.PurchaseState.UNSPECIFIED_STATE -> {
                Log.w(TAG, "Purchase state: UNSPECIFIED_STATE")
                // Log to Crashlytics
                try {
                    val crashlytics = FirebaseCrashlytics.getInstance()
                        
                    crashlytics.log("Purchase UNSPECIFIED_STATE: ${purchase.products}")
                    crashlytics.setCustomKey("purchase_state", "UNSPECIFIED_STATE")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to log unspecified purchase to Crashlytics", e)
                }
            }
            else -> {
                Log.w(TAG, "Unknown purchase state: ${purchase.purchaseState}")
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
                    Log.d(TAG, "Purchase acknowledged: ${purchase.products}")
                } else {
                    Log.e(TAG, "Failed to acknowledge purchase: ${billingResult.debugMessage}")
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
            Log.w(TAG, "Cannot get subscription info: BillingClient not ready")
            return null
        }

        // Query purchases
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        return suspendCancellableCoroutine { continuation ->
            client.queryPurchasesAsync(params) { billingResult, purchases ->
                if (billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
                    Log.e(TAG, "Failed to query purchases for subscription info")
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
                    Log.w(TAG, "Product details not found in cache for $productId")
                    continuation.resume(null)
                    return@queryPurchasesAsync
                }

                // Extract price
                val offerDetails = productDetails.subscriptionOfferDetails?.firstOrNull()
                val pricePhase = offerDetails?.pricingPhases?.pricingPhaseList?.firstOrNull()
                val formattedPrice = pricePhase?.formattedPrice ?: "N/A"

                // Determine product name
                val isMonthly = productId == PRODUCT_ID_MONTHLY
                val productName = if (isMonthly) "Monatlich" else "Jährlich"

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
        Log.d(TAG, "destroy() called")

        // Cancel any pending purchase flow to prevent continuation leak
        pendingPurchaseContinuation?.let {
            Log.w(TAG, "Cancelling pending purchase continuation on destroy")
            try {
                it.resume(PurchaseResult.Error("Billing service disconnected"))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resume continuation on destroy", e)
            }
            pendingPurchaseContinuation = null
        }

        // Safely disconnect billing client
        try {
            billingClient?.endConnection()
        } catch (e: Exception) {
            Log.e(TAG, "Error disconnecting billing client", e)
        }

        billingClient = null
        Log.d(TAG, "BillingClient destroyed")
    }
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
    val productName: String,                  // "Monatlich" or "Jährlich"
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
