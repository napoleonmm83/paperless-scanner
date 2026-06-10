package com.paperless.scanner.data.analytics

/**
 * Test-double seam for [AnalyticsService] (#321): the event surface consumed by
 * ServerHealthViewModel. NOTE: recordException lives on [CrashlyticsHelperContract],
 * not here (the design doc wrongly placed it on AnalyticsService).
 */
interface AnalyticsServiceContract {
    fun trackEvent(event: AnalyticsEvent)
}
