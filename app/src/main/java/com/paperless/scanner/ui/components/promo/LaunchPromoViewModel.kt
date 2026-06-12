package com.paperless.scanner.ui.components.promo

import android.app.Activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.data.analytics.AnalyticsEvent
import com.paperless.scanner.data.analytics.AnalyticsService
import com.paperless.scanner.data.billing.BasePlanPrices
import com.paperless.scanner.data.billing.BillingManager
import com.paperless.scanner.data.billing.LaunchPromoManager
import com.paperless.scanner.data.billing.LaunchPromoState
import com.paperless.scanner.data.billing.PremiumPurchaseCoordinator
import com.paperless.scanner.data.billing.PurchaseResult
import com.paperless.scanner.data.datastore.TokenManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

/** Analytics trigger value shared by the banner's impression/click/dismiss events. */
const val LAUNCH_PROMO_TRIGGER = "launch_promo_banner"

sealed interface LaunchPromoBannerState {
    data object Hidden : LaunchPromoBannerState
    data class Visible(
        val promoPrice: String,
        val regularPrice: String,
        val endDateFormatted: String
    ) : LaunchPromoBannerState
}

/** Promo display data for the upgrade sheet (null = no promo live). */
data class LaunchPromoSheetUi(
    val promoPrice: String,
    val regularPrice: String,
    val endDateFormatted: String
)

@HiltViewModel
class LaunchPromoViewModel @Inject constructor(
    launchPromoManager: LaunchPromoManager,
    billingManager: BillingManager,
    private val tokenManager: TokenManager,
    private val analyticsService: AnalyticsService,
    private val purchaseCoordinator: PremiumPurchaseCoordinator
) : ViewModel() {

    private var impressionLogged = false

    /** Banner on Home: promo Active AND not dismissed by the user. */
    val bannerState: StateFlow<LaunchPromoBannerState> = combine(
        launchPromoManager.state,
        tokenManager.launchPromoBannerDismissed
    ) { promo, dismissed ->
        if (promo is LaunchPromoState.Active && !dismissed) {
            LaunchPromoBannerState.Visible(
                promoPrice = promo.promoPrice,
                regularPrice = promo.regularPrice,
                endDateFormatted = formatEndDate(promo.endEpochMs)
            )
        } else {
            LaunchPromoBannerState.Hidden
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LaunchPromoBannerState.Hidden)

    /** Promo display for the upgrade sheet — intentionally ignores the banner dismiss flag. */
    val sheetPromo: StateFlow<LaunchPromoSheetUi?> = launchPromoManager.state
        .map { promo ->
            (promo as? LaunchPromoState.Active)?.let {
                LaunchPromoSheetUi(
                    promoPrice = it.promoPrice,
                    regularPrice = it.regularPrice,
                    endDateFormatted = formatEndDate(it.endEpochMs)
                )
            }
        }
        // Eagerly: the sheet reads this on first frame for plan pre-selection — a late
        // upstream emission would flash monthly→yearly. Upstream is a hot singleton, so
        // this costs nothing.
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Localized base-plan prices for the sheet (null until billing loads). */
    val basePlanPrices: StateFlow<BasePlanPrices?> = billingManager.basePlanPrices

    fun onBannerVisible() {
        if (impressionLogged) return
        impressionLogged = true
        analyticsService.trackEvent(AnalyticsEvent.LaunchPromoBannerShown)
    }

    fun onBannerClicked() {
        analyticsService.trackEvent(AnalyticsEvent.PremiumPromptShown(trigger = LAUNCH_PROMO_TRIGGER))
    }

    fun dismissBanner() {
        viewModelScope.launch {
            tokenManager.setLaunchPromoBannerDismissed()
            // Log only after the flag is persisted: if the DataStore write fails, the
            // banner reappears next start and an unconditional event would inflate
            // the dismiss metric.
            analyticsService.trackEvent(
                AnalyticsEvent.PremiumPromptDismissed(trigger = LAUNCH_PROMO_TRIGGER)
            )
        }
    }

    /** Purchase entry point for the Home banner path — same routing as Settings. */
    suspend fun purchase(activity: Activity, productId: String): PurchaseResult =
        purchaseCoordinator.purchase(activity, productId)

    /** dd.MM.yyyy, same display format as the premium expiry date in Settings. */
    private fun formatEndDate(endEpochMs: Long): String =
        SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(endEpochMs))
}
