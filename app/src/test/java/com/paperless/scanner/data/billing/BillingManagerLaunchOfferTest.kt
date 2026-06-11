package com.paperless.scanner.data.billing

import com.android.billingclient.api.ProductDetails
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BillingManagerLaunchOfferTest {

    private val billingManager = BillingManager(mockk(relaxed = true))

    private fun pricingPhase(micros: Long, formatted: String): ProductDetails.PricingPhase = mockk {
        every { priceAmountMicros } returns micros
        every { formattedPrice } returns formatted
    }

    private fun offer(
        tags: List<String>,
        token: String,
        phases: List<ProductDetails.PricingPhase>
    ): ProductDetails.SubscriptionOfferDetails = mockk {
        every { offerTags } returns tags
        every { offerToken } returns token
        every { pricingPhases } returns mockk { every { pricingPhaseList } returns phases }
    }

    private fun productDetails(offers: List<ProductDetails.SubscriptionOfferDetails>?): ProductDetails = mockk {
        every { subscriptionOfferDetails } returns offers
    }

    @Test
    fun `launch50-tagged offer with trial, intro and regular phase is extracted`() {
        val details = productDetails(
            listOf(
                offer(tags = emptyList(), token = "base-token", phases = listOf(pricingPhase(39_990_000, "CHF 39.99"))),
                offer(
                    tags = listOf("launch50"),
                    token = "promo-token",
                    phases = listOf(
                        pricingPhase(0, "Free"),               // 14d trial
                        pricingPhase(19_990_000, "CHF 19.99"), // discounted first year
                        pricingPhase(39_990_000, "CHF 39.99")  // regular renewal
                    )
                )
            )
        )

        assertEquals(
            LaunchOfferDetails(
                offerToken = "promo-token",
                introFormattedPrice = "CHF 19.99",
                regularFormattedPrice = "CHF 39.99"
            ),
            billingManager.extractLaunchOffer(details)
        )
    }

    @Test
    fun `product without launch50 tag yields null`() {
        val details = productDetails(
            listOf(offer(tags = listOf("winback"), token = "x", phases = listOf(pricingPhase(39_990_000, "CHF 39.99"))))
        )
        assertNull(billingManager.extractLaunchOffer(details))
    }

    @Test
    fun `tagged offer without real discount yields null`() {
        val details = productDetails(
            listOf(
                offer(
                    tags = listOf("launch50"),
                    token = "promo-token",
                    phases = listOf(pricingPhase(39_990_000, "CHF 39.99"), pricingPhase(39_990_000, "CHF 39.99"))
                )
            )
        )
        assertNull(billingManager.extractLaunchOffer(details))
    }

    @Test
    fun `null product details yields null`() {
        assertNull(billingManager.extractLaunchOffer(null))
    }
}
