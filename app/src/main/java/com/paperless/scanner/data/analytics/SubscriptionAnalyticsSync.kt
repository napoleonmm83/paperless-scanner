package com.paperless.scanner.data.analytics

import com.paperless.scanner.data.billing.BillingManager
import com.paperless.scanner.data.billing.analyticsName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mirrors the subscription status into the Crashlytics `subscription_status` custom key
 * and the Firebase Analytics user property for the process lifetime (#296).
 *
 * Combining with [AnalyticsService.enabled] is load-bearing: billing connects
 * asynchronously and may emit ACTIVE while analytics consent is still ungranted —
 * [AnalyticsService.updateCrashlyticsSubscriptionStatus] no-ops while disabled, and a
 * distinct-until-changed StateFlow never re-emits that status. The consent grant
 * (first-launch dialog or the settings toggle) re-fires the combine with the LATEST
 * status, so a premium user can never end up with a permanently missing/stale key.
 *
 * Started once from PaperlessApp with the application scope; the scope's
 * cancellation in onTerminate is the teardown (same pattern as the app's other
 * process-lifetime collectors, #142).
 */
@Singleton
class SubscriptionAnalyticsSync @Inject constructor(
    private val billingManager: BillingManager,
    private val analyticsService: AnalyticsService,
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            combine(
                analyticsService.enabled,
                billingManager.subscriptionStatus,
            ) { _, status -> status }
                .collect { status ->
                    val name = status.analyticsName()
                    analyticsService.updateCrashlyticsSubscriptionStatus(name)
                    analyticsService.setSubscriptionStatus(name)
                }
        }
    }
}
