package com.paperless.scanner.testing.fakes

import com.paperless.scanner.data.analytics.AnalyticsEvent
import com.paperless.scanner.data.analytics.AnalyticsServiceContract

/**
 * Typed fake for [AnalyticsServiceContract] (#239/#321): records every tracked event
 * so tests assert on real data instead of relaxed-mock verify calls.
 */
class FakeAnalyticsService : AnalyticsServiceContract {
    val trackedEvents = mutableListOf<AnalyticsEvent>()

    override fun trackEvent(event: AnalyticsEvent) {
        trackedEvents += event
    }
}
