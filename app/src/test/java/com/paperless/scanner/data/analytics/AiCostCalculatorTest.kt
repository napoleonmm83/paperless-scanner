package com.paperless.scanner.data.analytics

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for AI cost calculation.
 * Ensures accurate cost tracking for business profitability.
 */
class AiCostCalculatorTest {

    companion object {
        private const val DELTA = 0.00001 // Acceptable floating point error
    }

    @Test
    fun `calculateCost with typical usage returns correct value`() {
        // Typical image analysis: 1500 input, 200 output tokens
        val cost = AiCostCalculator.calculateCost(
            inputTokens = 1500,
            outputTokens = 200
        )

        // Expected: (1500/1M * 0.30) + (200/1M * 2.50)
        //         = 0.00045 + 0.0005
        //         = 0.00095
        assertEquals(0.00095, cost, DELTA)
    }

    @Test
    fun `calculateCost with zero tokens returns zero`() {
        val cost = AiCostCalculator.calculateCost(
            inputTokens = 0,
            outputTokens = 0
        )

        assertEquals(0.0, cost, DELTA)
    }

    @Test
    fun `calculateCost with only input tokens`() {
        val cost = AiCostCalculator.calculateCost(
            inputTokens = 1000,
            outputTokens = 0
        )

        // Expected: 1000/1M * 0.30 = 0.0003
        assertEquals(0.0003, cost, DELTA)
    }

    @Test
    fun `calculateCost with only output tokens`() {
        val cost = AiCostCalculator.calculateCost(
            inputTokens = 0,
            outputTokens = 500
        )

        // Expected: 500/1M * 2.50 = 0.00125
        assertEquals(0.00125, cost, DELTA)
    }

    @Test
    fun `calculateCost with large token counts`() {
        // Heavy PDF analysis: 10,000 input, 2,000 output
        val cost = AiCostCalculator.calculateCost(
            inputTokens = 10_000,
            outputTokens = 2_000
        )

        // Expected: (10000/1M * 0.30) + (2000/1M * 2.50)
        //         = 0.003 + 0.005
        //         = 0.008
        assertEquals(0.008, cost, DELTA)
    }

    @Test
    fun `calculateInputCost returns correct value`() {
        val cost = AiCostCalculator.calculateInputCost(inputTokens = 1500)

        // Expected: 1500/1M * 0.30 = 0.00045
        assertEquals(0.00045, cost, DELTA)
    }

    @Test
    fun `calculateInputCost with zero returns zero`() {
        val cost = AiCostCalculator.calculateInputCost(inputTokens = 0)

        assertEquals(0.0, cost, DELTA)
    }

    @Test
    fun `getAverageCostPerCall returns expected value`() {
        val avgCost = AiCostCalculator.getAverageCostPerCall()

        // Expected: calculateCost(1500, 200) = 0.00095
        assertEquals(0.00095, avgCost, DELTA)
    }

    @Test
    fun `cost per 30 calls per month is under $0_03`() {
        // Business requirement: 30 calls/month should cost <$0.03
        val costPer30Calls = AiCostCalculator.getAverageCostPerCall() * 30

        // Expected: ~0.0285 (well under $0.03)
        assert(costPer30Calls < 0.03) {
            "Cost per 30 calls ($costPer30Calls) exceeds $0.03 budget"
        }
    }

    @Test
    fun `profit margin calculation for monthly subscription`() {
        // Monthly subscription: €1.99 (~$2.20)
        // Average user: 30 calls/month
        // Expected profit margin: >80%

        val revenueUsd = 2.20
        val apiCostUsd = AiCostCalculator.getAverageCostPerCall() * 30
        val googleFee = revenueUsd * 0.15 // 15% for first $1M

        val netProfit = revenueUsd - googleFee - apiCostUsd
        val profitMargin = (netProfit / revenueUsd) * 100

        // Expected: ~83% margin
        assert(profitMargin > 80.0) {
            "Profit margin ($profitMargin%) is below 80% target"
        }

        println("Profit Analysis (Monthly):")
        println("  Revenue: $${"%.2f".format(revenueUsd)}")
        println("  Google Fee: $${"%.2f".format(googleFee)}")
        println("  API Cost: $${"%.4f".format(apiCostUsd)}")
        println("  Net Profit: $${"%.2f".format(netProfit)}")
        println("  Margin: ${"%.1f".format(profitMargin)}%")
    }

    @Test
    fun `break-even point for heavy users`() {
        // Find how many calls before we lose money
        // Monthly subscription: €1.99 (~$2.20)

        val revenueUsd = 2.20
        val googleFee = revenueUsd * 0.15
        val netRevenueAfterFee = revenueUsd - googleFee
        val costPerCall = AiCostCalculator.getAverageCostPerCall()

        val breakEvenCalls = (netRevenueAfterFee / costPerCall).toInt()

        // Expected: ~1,968 calls before losing money
        // With 300 call limit, we're safe
        assert(breakEvenCalls > 300) {
            "Break-even point ($breakEvenCalls calls) is too close to usage limit (300)"
        }

        println("Break-Even Analysis:")
        println("  Net Revenue (after Google Fee): $${"%.2f".format(netRevenueAfterFee)}")
        println("  Cost Per Call: $${"%.6f".format(costPerCall)}")
        println("  Break-Even Calls: $breakEvenCalls")
        println("  Safety Buffer: ${breakEvenCalls - 300} calls above limit")
    }

    @Test
    fun `worst case scenario - maximum usage limit`() {
        // Worst case: User hits 300 call limit
        val maxCalls = 300
        val totalCost = AiCostCalculator.getAverageCostPerCall() * maxCalls

        val revenueUsd = 2.20
        val googleFee = revenueUsd * 0.15
        val netProfit = revenueUsd - googleFee - totalCost
        val profitMargin = (netProfit / revenueUsd) * 100

        // Even at max usage, we should be profitable
        assert(netProfit > 0) {
            "Losing money at max usage! Net profit: $$netProfit"
        }

        println("Worst Case (300 calls):")
        println("  API Cost: $${"%.2f".format(totalCost)}")
        println("  Net Profit: $${"%.2f".format(netProfit)}")
        println("  Margin: ${"%.1f".format(profitMargin)}%")
    }

    @Test
    fun `pricing update test - verify constants are current`() {
        // This test documents current Gemini Flash pricing
        // Update if Google changes pricing

        // As of January 2026:
        // - Input: $0.30 per 1M tokens
        // - Output: $2.50 per 1M tokens

        // Calculate 1M tokens cost
        val inputCostPer1M = AiCostCalculator.calculateInputCost(1_000_000)
        val outputCostPer1M = AiCostCalculator.calculateCost(0, 1_000_000)

        assertEquals(0.30, inputCostPer1M, DELTA)
        assertEquals(2.50, outputCostPer1M, DELTA)
    }

    @Test
    fun `cost precision for small token counts`() {
        // Ensure accurate calculation for very small requests
        val cost = AiCostCalculator.calculateCost(
            inputTokens = 10,
            outputTokens = 5
        )

        // Expected: (10/1M * 0.30) + (5/1M * 2.50)
        //         = 0.000003 + 0.0000125
        //         = 0.0000155
        assertEquals(0.0000155, cost, DELTA)
    }

    @Test
    fun `cost for different feature types`() {
        // Test typical token counts for each feature

        // 1. analyze_image
        val imageCost = AiCostCalculator.calculateCost(1500, 200)
        assertEquals(0.00095, imageCost, DELTA)

        // 2. analyze_pdf (longer)
        val pdfCost = AiCostCalculator.calculateCost(2000, 300)
        assertEquals(0.00135, pdfCost, DELTA)

        // 3. suggest_tags (shorter output)
        val tagsCost = AiCostCalculator.calculateCost(1000, 50)
        assertEquals(0.000425, tagsCost, DELTA)

        // 4. generate_title (very short)
        val titleCost = AiCostCalculator.calculateCost(800, 20)
        assertEquals(0.00029, titleCost, DELTA)

        // 5. generate_summary
        val summaryCost = AiCostCalculator.calculateCost(1200, 150)
        assertEquals(0.000735, summaryCost, DELTA)

        println("Cost per Feature Type:")
        println("  analyze_image: $${"%.6f".format(imageCost)}")
        println("  analyze_pdf: $${"%.6f".format(pdfCost)}")
        println("  suggest_tags: $${"%.6f".format(tagsCost)}")
        println("  generate_title: $${"%.6f".format(titleCost)}")
        println("  generate_summary: $${"%.6f".format(summaryCost)}")
    }
}
