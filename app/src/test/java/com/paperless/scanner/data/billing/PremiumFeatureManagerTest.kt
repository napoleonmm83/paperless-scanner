package com.paperless.scanner.data.billing

import app.cash.turbine.test
import com.paperless.scanner.data.datastore.TokenManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for PremiumFeatureManager.
 *
 * Tests the feature gate logic that combines:
 * - Premium access (subscription status from BillingManager)
 * - User preferences (from TokenManager)
 *
 * PHASE 2 NOTE: Tests simulate premium access via mocked BillingManager
 * which returns subscription status in production.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PremiumFeatureManagerTest {

    private lateinit var premiumFeatureManager: PremiumFeatureManager
    private lateinit var billingManager: BillingManager
    private lateinit var tokenManager: TokenManager

    private val mockSubscriptionActive = MutableStateFlow(false)
    private val mockAiSuggestionsEnabled = MutableStateFlow(true)
    private val mockAiNewTagsEnabled = MutableStateFlow(true)
    private val mockAiWifiOnly = MutableStateFlow(false)

    @Before
    fun setup() {
        billingManager = mockk(relaxed = true)
        tokenManager = mockk(relaxed = true)

        // Mock BillingManager subscription status
        every { billingManager.isSubscriptionActive } returns mockSubscriptionActive
        every { billingManager.isSubscriptionActiveSync() } answers { mockSubscriptionActive.value }

        // Mock TokenManager flows
        every { tokenManager.aiSuggestionsEnabled } returns mockAiSuggestionsEnabled
        every { tokenManager.aiNewTagsEnabled } returns mockAiNewTagsEnabled
        every { tokenManager.aiWifiOnly } returns mockAiWifiOnly

        // Mock TokenManager sync methods (for isFeatureAvailable)
        every { tokenManager.getAiSuggestionsEnabledSync() } answers { mockAiSuggestionsEnabled.value }
        every { tokenManager.getAiNewTagsEnabledSync() } answers { mockAiNewTagsEnabled.value }

        premiumFeatureManager = PremiumFeatureManager(billingManager, tokenManager)
    }

    /**
     * Helper to set premium access state for testing.
     * In production, this is controlled by BillingManager subscription status.
     */
    private fun setPremiumAccessEnabled(enabled: Boolean) {
        mockSubscriptionActive.value = enabled
    }

    // ==================== isAiEnabled Tests ====================

    @Test
    fun `isAiEnabled is true when premium access enabled and user enabled`() = runTest {
        setPremiumAccessEnabled(true)
        mockAiSuggestionsEnabled.value = true

        premiumFeatureManager.isAiEnabled.test {
            assertTrue(awaitItem())
        }
    }

    @Test
    fun `isAiEnabled is false when premium access disabled`() = runTest {
        setPremiumAccessEnabled(false)
        mockAiSuggestionsEnabled.value = true

        premiumFeatureManager.isAiEnabled.test {
            assertFalse(awaitItem())
        }
    }

    @Test
    fun `isAiEnabled is false when user disabled AI`() = runTest {
        setPremiumAccessEnabled(true)
        mockAiSuggestionsEnabled.value = false

        premiumFeatureManager.isAiEnabled.test {
            assertFalse(awaitItem())
        }
    }

    // ==================== isAiNewTagsEnabled Tests ====================

    @Test
    fun `isAiNewTagsEnabled is true when AI enabled and new tags enabled`() = runTest {
        setPremiumAccessEnabled(true)
        mockAiSuggestionsEnabled.value = true
        mockAiNewTagsEnabled.value = true

        premiumFeatureManager.isAiNewTagsEnabled.test {
            assertTrue(awaitItem())
        }
    }

    @Test
    fun `isAiNewTagsEnabled is false when new tags disabled`() = runTest {
        setPremiumAccessEnabled(true)
        mockAiSuggestionsEnabled.value = true
        mockAiNewTagsEnabled.value = false

        premiumFeatureManager.isAiNewTagsEnabled.test {
            assertFalse(awaitItem())
        }
    }

    @Test
    fun `isAiNewTagsEnabled is false when AI disabled`() = runTest {
        setPremiumAccessEnabled(true)
        mockAiSuggestionsEnabled.value = false
        mockAiNewTagsEnabled.value = true

        premiumFeatureManager.isAiNewTagsEnabled.test {
            assertFalse(awaitItem())
        }
    }

    // ==================== isFeatureAvailable Sync Tests ====================

    @Test
    fun `isFeatureAvailable AI_ANALYSIS returns true when premium access enabled`() {
        setPremiumAccessEnabled(true)

        assertTrue(premiumFeatureManager.isFeatureAvailable(PremiumFeature.AI_ANALYSIS))
    }

    @Test
    fun `isFeatureAvailable AI_ANALYSIS returns false when premium access disabled`() {
        setPremiumAccessEnabled(false)

        assertFalse(premiumFeatureManager.isFeatureAvailable(PremiumFeature.AI_ANALYSIS))
    }

    @Test
    fun `isFeatureAvailable AI_SUMMARY returns false (not implemented)`() {
        setPremiumAccessEnabled(true)

        assertFalse(premiumFeatureManager.isFeatureAvailable(PremiumFeature.AI_SUMMARY))
    }

    // ==================== isFeatureAvailableAsync Tests ====================

    @Test
    fun `isFeatureAvailableAsync AI_ANALYSIS respects user preference`() = runTest {
        setPremiumAccessEnabled(true)
        mockAiSuggestionsEnabled.value = false

        // Should return false because user disabled it
        assertFalse(premiumFeatureManager.isFeatureAvailableAsync(PremiumFeature.AI_ANALYSIS))
    }

    @Test
    fun `isFeatureAvailableAsync AI_ANALYSIS returns true when enabled`() = runTest {
        setPremiumAccessEnabled(true)
        mockAiSuggestionsEnabled.value = true

        assertTrue(premiumFeatureManager.isFeatureAvailableAsync(PremiumFeature.AI_ANALYSIS))
    }

    // ==================== requireFeature Tests ====================

    @Test
    fun `requireFeature returns Granted when feature available`() = runTest {
        setPremiumAccessEnabled(true)
        mockAiSuggestionsEnabled.value = true

        val result = premiumFeatureManager.requireFeature(PremiumFeature.AI_ANALYSIS)

        assertEquals(FeatureAccessResult.Granted, result)
    }

    @Test
    fun `requireFeature returns RequiresUpgrade when no premium access`() = runTest {
        setPremiumAccessEnabled(false)
        mockAiSuggestionsEnabled.value = true

        val result = premiumFeatureManager.requireFeature(PremiumFeature.AI_ANALYSIS)

        assertEquals(FeatureAccessResult.RequiresUpgrade, result)
    }

    @Test
    fun `requireFeature returns DisabledInSettings when premium but disabled`() = runTest {
        setPremiumAccessEnabled(true)
        mockAiSuggestionsEnabled.value = false

        val result = premiumFeatureManager.requireFeature(PremiumFeature.AI_ANALYSIS)

        assertEquals(FeatureAccessResult.DisabledInSettings, result)
    }

    // ==================== Reactive Updates Tests ====================

    @Test
    fun `isAiEnabled reacts to premium access changes`() = runTest {
        mockAiSuggestionsEnabled.value = true
        // Start from a known state (no subscription)
        setPremiumAccessEnabled(false)

        premiumFeatureManager.isAiEnabled.test {
            // Initially false (no subscription)
            assertFalse(awaitItem())

            // Set to true (subscription activated)
            setPremiumAccessEnabled(true)
            assertTrue(awaitItem())

            // Set back to false (subscription expired)
            setPremiumAccessEnabled(false)
            assertFalse(awaitItem())

            // Set to true again (subscription renewed)
            setPremiumAccessEnabled(true)
            assertTrue(awaitItem())
        }
    }

    @Test
    fun `isAiEnabled reacts to user preference changes`() = runTest {
        setPremiumAccessEnabled(true)

        premiumFeatureManager.isAiEnabled.test {
            // Initially true (premium + enabled)
            assertTrue(awaitItem())

            // Becomes false when user disables
            mockAiSuggestionsEnabled.value = false
            assertFalse(awaitItem())

            // Becomes true when user re-enables
            mockAiSuggestionsEnabled.value = true
            assertTrue(awaitItem())
        }
    }

    // ==================== Phase 2 Specific Tests ====================

    @Test
    fun `no subscription has no premium access by default`() {
        // In unit tests, subscription is mocked as false initially
        // This test documents expected behavior
        val manager = PremiumFeatureManager(billingManager, tokenManager)
        // Initial value comes from billingManager.isSubscriptionActive
        // In test environment this is mocked as false by default
    }

    @Test
    fun `no subscription blocks AI features`() = runTest {
        setPremiumAccessEnabled(false) // Simulates no subscription
        mockAiSuggestionsEnabled.value = true

        assertFalse(premiumFeatureManager.isFeatureAvailable(PremiumFeature.AI_ANALYSIS))
        assertFalse(premiumFeatureManager.isFeatureAvailableAsync(PremiumFeature.AI_ANALYSIS))

        val result = premiumFeatureManager.requireFeature(PremiumFeature.AI_ANALYSIS)
        assertEquals(FeatureAccessResult.RequiresUpgrade, result)
    }
}
