package com.paperless.scanner.data.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalyticsEventLaunchPromoTest {

    @Test
    fun `LaunchPromoBannerShown has stable event name and no params`() {
        val event = AnalyticsEvent.LaunchPromoBannerShown
        assertEquals("launch_promo_banner_shown", event.name)
        assertEquals(emptyMap<String, Any>(), event.params)
    }

    @Test
    fun `PremiumSubscribed carries plan and offer tag`() {
        val event = AnalyticsEvent.PremiumSubscribed(plan = "yearly", offerTag = "launch50")
        assertEquals("premium_subscribed", event.name)
        assertEquals(mapOf("plan" to "yearly", "offer_tag" to "launch50"), event.params)
    }

    @Test
    fun `PremiumSubscribed defaults offer tag to none`() {
        val event = AnalyticsEvent.PremiumSubscribed(plan = "monthly")
        assertEquals("none", event.params["offer_tag"])
    }
}
