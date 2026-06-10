package com.paperless.scanner.data.billing

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-function pin for the analytics dimension mapping (#296). Exhaustiveness over
 * the sealed class is compiler-enforced; note that only "free" and "premium" are
 * currently producible at runtime (no producer emits EXPIRED/GRACE_PERIOD/ON_HOLD yet).
 */
class SubscriptionStatusAnalyticsNameTest {

    @Test
    fun `maps every status to its analytics name`() {
        assertEquals("free", SubscriptionStatus.FREE.analyticsName())
        assertEquals("premium", SubscriptionStatus.ACTIVE(expiryDateMs = 1L).analyticsName())
        assertEquals("expired", SubscriptionStatus.EXPIRED.analyticsName())
        assertEquals("grace_period", SubscriptionStatus.GRACE_PERIOD.analyticsName())
        assertEquals("on_hold", SubscriptionStatus.ON_HOLD.analyticsName())
    }
}
