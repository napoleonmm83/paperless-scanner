package com.paperless.scanner.ui.components.promo

import android.app.Activity
import android.content.Context
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import com.paperless.scanner.R
import com.paperless.scanner.data.billing.PurchaseResult
import com.paperless.scanner.data.billing.RestoreResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Shared snackbar-driven wiring for [com.paperless.scanner.ui.screens.settings.PremiumUpgradeSheet]'s
 * subscribe/restore callbacks. Routes both money paths through [PremiumPurchaseCoordinator]
 * (via [LaunchPromoViewModel]) so every entry point stays promo-aware and consistent — used by
 * Home, Upload, MultiPageUpload and SmartTagging. Settings keeps its own dialog-based handling.
 *
 * Obtain via [rememberPremiumPurchaseActions] at the SCREEN's top level: the launching [scope] must
 * outlive the `if (showSheet)` block, otherwise the result snackbar is cancelled the moment the
 * sheet dismisses.
 */
class PremiumPurchaseActions internal constructor(
    private val context: Context,
    private val scope: CoroutineScope,
    private val snackbarHostState: SnackbarHostState,
    private val promoViewModel: LaunchPromoViewModel,
) {
    /**
     * Wire to `PremiumUpgradeSheet.onSubscribe`. [dismiss] always closes the sheet BEFORE the
     * result snackbar is shown — the snackbar host sits behind the modal sheet, so a snackbar
     * raised while the sheet is still open would be invisible (errors included). Cancellation
     * just closes the sheet with no message.
     */
    fun subscribe(productId: String, dismiss: () -> Unit) {
        val activity = context as? Activity
        // Activity-state guard at the money-path entry (project billing convention, cc0b331):
        // fail fast on a missing/finishing/destroyed Activity instead of kicking off a billing
        // flow that can only error. BillingManager re-checks this right before launchBillingFlow.
        if (activity == null || activity.isFinishing || activity.isDestroyed) {
            dismiss()
            scope.launch {
                snackbarHostState.showSnackbar(context.getString(R.string.error_unable_launch_purchase))
            }
            return
        }
        scope.launch {
            val message = when (val result = promoViewModel.purchase(activity, productId)) {
                is PurchaseResult.Success -> context.getString(R.string.premium_purchase_success)
                is PurchaseResult.Pending -> context.getString(R.string.premium_purchase_pending)
                is PurchaseResult.Cancelled -> null
                is PurchaseResult.Error ->
                    context.getString(R.string.premium_purchase_error, result.message)
            }
            dismiss()
            if (message != null) snackbarHostState.showSnackbar(message)
        }
    }

    /**
     * Wire to `PremiumUpgradeSheet.onRestore`. [dismiss] always closes the sheet BEFORE the result
     * snackbar — same reason as [subscribe]: a snackbar shown under the still-open modal sheet
     * (including "nothing found" and errors) would never reach the user.
     */
    fun restore(dismiss: () -> Unit) {
        scope.launch {
            val message = when (val result = promoViewModel.restorePurchases()) {
                is RestoreResult.Success ->
                    context.getString(R.string.premium_restore_success, result.restoredCount)
                is RestoreResult.NoPurchasesFound -> context.getString(R.string.premium_restore_none)
                is RestoreResult.Error ->
                    context.getString(R.string.premium_restore_error, result.message)
            }
            dismiss()
            snackbarHostState.showSnackbar(message)
        }
    }
}

/**
 * Creates [PremiumPurchaseActions] bound to the calling screen's lifecycle. Call this at the screen's
 * top level (not inside `if (showSheet)`) so the coroutine scope survives the sheet's dismissal.
 */
@Composable
fun rememberPremiumPurchaseActions(
    snackbarHostState: SnackbarHostState,
    promoViewModel: LaunchPromoViewModel = hiltViewModel(),
): PremiumPurchaseActions {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    return remember(context, scope, snackbarHostState, promoViewModel) {
        PremiumPurchaseActions(context, scope, snackbarHostState, promoViewModel)
    }
}
