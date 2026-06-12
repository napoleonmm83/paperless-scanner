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
        phases: List<ProductDetails.PricingPhase>,
        offerId: String? = null
    ): ProductDetails.SubscriptionOfferDetails = mockk {
        every { this@mockk.offerId } returns offerId
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

    @Test
    fun `tagged offer with a single paid phase yields null`() {
        // intro == regular phase → no real discount → fail closed
        val details = productDetails(
            listOf(
                offer(
                    tags = listOf("launch50"),
                    token = "promo-token",
                    phases = listOf(pricingPhase(39_990_000, "CHF 39.99"))
                )
            )
        )
        assertNull(billingManager.extractLaunchOffer(details))
    }

    @Test
    fun `tagged offer with only free phases yields null`() {
        // no paid phase at all → nothing to price → fail closed
        val details = productDetails(
            listOf(
                offer(
                    tags = listOf("launch50"),
                    token = "promo-token",
                    phases = listOf(pricingPhase(0, "Free"), pricingPhase(0, "Free"))
                )
            )
        )
        assertNull(billingManager.extractLaunchOffer(details))
    }

    @Test
    fun `offer identified by launch50 offerId with empty tags is extracted`() {
        // Reproduces the 1.5.218 production config: the Play Console offer was created with
        // offerId "launch50" but no offer tag, so offerTags is empty. Matching by offerId
        // (not just tag) must still recognize it as the launch promo.
        val details = productDetails(
            listOf(
                offer(
                    offerId = "launch50",
                    tags = emptyList(),
                    token = "promo-token",
                    phases = listOf(
                        pricingPhase(0, "Kostenlos"),          // 14d trial
                        pricingPhase(20_000_000, "CHF 20.00"), // discounted first year
                        pricingPhase(40_000_000, "CHF 40.00")  // regular renewal
                    )
                )
            )
        )

        assertEquals(
            LaunchOfferDetails(
                offerToken = "promo-token",
                introFormattedPrice = "CHF 20.00",
                regularFormattedPrice = "CHF 40.00"
            ),
            billingManager.extractLaunchOffer(details)
        )
    }

    @Test
    fun `offer with launch50 offerId and tag is extracted`() {
        val details = productDetails(
            listOf(
                offer(
                    offerId = "launch50",
                    tags = listOf("launch50"),
                    token = "promo-token",
                    phases = listOf(pricingPhase(19_990_000, "CHF 19.99"), pricingPhase(39_990_000, "CHF 39.99"))
                )
            )
        )
        assertEquals("promo-token", billingManager.extractLaunchOffer(details)?.offerToken)
    }

    @Test
    fun `offer with unrelated offerId and no tag yields null`() {
        val details = productDetails(
            listOf(offer(offerId = "winback", tags = emptyList(), token = "x", phases = listOf(pricingPhase(39_990_000, "CHF 39.99"))))
        )
        assertNull(billingManager.extractLaunchOffer(details))
    }

    // ── extractBasePlanPrices tests ──────────────────────────────────────────

    @Test
    fun `extractBasePlanPrices picks last paid phase of first non-promo offer`() {
        // Monthly: base offer only (trial + recurring).
        // Yearly: promo-tagged offer + base offer; base has trial + recurring.
        // Expectation: recurring (last paid) phase of the base offers is returned.
        val monthlyDetails = productDetails(
            listOf(
                offer(
                    tags = emptyList(),
                    token = "monthly-base",
                    phases = listOf(
                        pricingPhase(0, "Free"),                 // 7d trial
                        pricingPhase(3_990_000, "€3.99")         // recurring
                    )
                )
            )
        )
        val yearlyDetails = productDetails(
            listOf(
                offer(
                    tags = listOf("launch50"),
                    token = "yearly-promo",
                    phases = listOf(pricingPhase(19_990_000, "€19.99"), pricingPhase(39_990_000, "€39.99"))
                ),
                offer(
                    tags = emptyList(),
                    token = "yearly-base",
                    phases = listOf(
                        pricingPhase(0, "Free"),                 // 14d trial
                        pricingPhase(39_990_000, "€39.99")       // recurring
                    )
                )
            )
        )
        val cache = mapOf(
            BillingManager.PRODUCT_ID_MONTHLY to monthlyDetails,
            BillingManager.PRODUCT_ID_YEARLY to yearlyDetails
        )
        assertEquals(
            BasePlanPrices(monthlyFormatted = "€3.99", yearlyFormatted = "€39.99"),
            billingManager.extractBasePlanPrices(cache)
        )
    }

    @Test
    fun `extractBasePlanPrices excludes the launch offer identified by offerId, not just tag`() {
        // The launch offer carries offerId "launch50" but EMPTY tags (the 1.5.218 shape).
        // Base price selection must exclude it by offerId and read the real base offer.
        // Prices are deliberately distinct so the assertion fails if the launch offer is picked.
        val monthlyDetails = productDetails(
            listOf(offer(tags = emptyList(), token = "monthly-base", phases = listOf(pricingPhase(4_000_000, "CHF 4.00"))))
        )
        val yearlyDetails = productDetails(
            listOf(
                offer(
                    offerId = "launch50",
                    tags = emptyList(),
                    token = "yearly-promo",
                    phases = listOf(pricingPhase(20_000_000, "CHF 20.00"), pricingPhase(40_000_000, "CHF 40.00"))
                ),
                offer(
                    tags = emptyList(),
                    token = "yearly-base",
                    phases = listOf(pricingPhase(45_000_000, "CHF 45.00")) // distinct from the launch offer's renewal
                )
            )
        )
        val cache = mapOf(
            BillingManager.PRODUCT_ID_MONTHLY to monthlyDetails,
            BillingManager.PRODUCT_ID_YEARLY to yearlyDetails
        )
        assertEquals(
            BasePlanPrices(monthlyFormatted = "CHF 4.00", yearlyFormatted = "CHF 45.00"),
            billingManager.extractBasePlanPrices(cache)
        )
    }

    @Test
    fun `extractBasePlanPrices returns null when yearly has only promo-tagged offer`() {
        // Yearly product only has the launch50 offer — no base offer to read the price from.
        val monthlyDetails = productDetails(
            listOf(offer(tags = emptyList(), token = "monthly-base", phases = listOf(pricingPhase(3_990_000, "€3.99"))))
        )
        val yearlyDetailsPromoOnly = productDetails(
            listOf(offer(tags = listOf("launch50"), token = "yearly-promo", phases = listOf(pricingPhase(19_990_000, "€19.99"))))
        )
        val cache = mapOf(
            BillingManager.PRODUCT_ID_MONTHLY to monthlyDetails,
            BillingManager.PRODUCT_ID_YEARLY to yearlyDetailsPromoOnly
        )
        assertNull(billingManager.extractBasePlanPrices(cache))
    }

    @Test
    fun `extractBasePlanPrices returns null when monthly product is missing from cache`() {
        val yearlyDetails = productDetails(
            listOf(offer(tags = emptyList(), token = "yearly-base", phases = listOf(pricingPhase(39_990_000, "€39.99"))))
        )
        // Cache has no monthly entry at all.
        val cache = mapOf(BillingManager.PRODUCT_ID_YEARLY to yearlyDetails)
        assertNull(billingManager.extractBasePlanPrices(cache))
    }
}
