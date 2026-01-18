package com.paperless.scanner.data.billing

import android.app.Activity
import android.content.Context
import android.util.Log
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
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
 * Manages Premium subscription and billing with Google Play Billing Library 7.x
 *
 * Features:
 * - Subscription status tracking (active/inactive/expired)
 * - Purchase flow initiation
 * - Purchase restoration
 * - Reactive state updates via Flow
 *
 * @see PremiumFeatureManager for feature-level access control
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
            .enablePendingPurchases()
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
    private suspend fun queryProductDetails() {
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
            billingClient?.queryProductDetailsAsync(params) { billingResult, productDetailsList ->
                if (billingResult.responseCode == BillingClient.BillingResponseCode.OK) {
                    productDetailsCache = productDetailsList.associateBy { it.productId }
                    Log.d(TAG, "Product details loaded: ${productDetailsList.size} products")
                } else {
                    Log.e(TAG, "Failed to load product details: ${billingResult.debugMessage}")
                }
                continuation.resume(Unit)
            }
        }
    }

    /**
     * Query existing purchases and update subscription status.
     */
    private suspend fun queryPurchases() {
        val params = QueryPurchasesParams.newBuilder()
            .setProductType(BillingClient.ProductType.SUBS)
            .build()

        suspendCancellableCoroutine { continuation ->
            billingClient?.queryPurchasesAsync(params) { billingResult, purchases ->
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

        // Check if there's already a pending purchase
        if (pendingPurchaseContinuation != null) {
            Log.e(TAG, "✗ Purchase already in progress!")
            return PurchaseResult.Error("Purchase already in progress")
        }

        val productDetails = productDetailsCache[productId]
        if (productDetails == null) {
            Log.e(TAG, "✗ Product not found in cache!")
            Log.d(TAG, "Available products: ${productDetailsCache.keys}")
            return PurchaseResult.Error("Product not found. Please try again later.")
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

            // Only handle flow launch errors here
            // Success/Cancel/Error from actual purchase handled in purchasesUpdatedListener
            if (billingResult?.responseCode != BillingClient.BillingResponseCode.OK) {
                Log.e(TAG, "✗ Failed to launch billing flow!")
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
     */
    private fun handlePurchase(purchase: Purchase) {
        if (purchase.purchaseState == Purchase.PurchaseState.PURCHASED) {
            if (!purchase.isAcknowledged) {
                scope.launch {
                    acknowledgePurchase(purchase)
                }
            }
            updateSubscriptionStatus(SubscriptionStatus.ACTIVE(System.currentTimeMillis() + 30L * 24 * 60 * 60 * 1000))
            _isSubscriptionActive.value = true
            Log.d(TAG, "Purchase successful: ${purchase.products}")
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
     * Clean up billing client on destroy.
     */
    fun destroy() {
        billingClient?.endConnection()
        billingClient = null
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
