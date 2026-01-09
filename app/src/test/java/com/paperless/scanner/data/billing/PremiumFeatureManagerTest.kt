package com.paperless.scanner.data.billing

import app.cash.turbine.test
import com.paperless.scanner.data.datastore.TokenManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
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
 * - Subscription status (from BillingManager)
 * - User preferences (from TokenManager)
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

        // Mock BillingManager flows
        every { billingManager.isSubscriptionActive } returns mockSubscriptionActive
        every { billingManager.isSubscriptionActiveSync() } answers { mockSubscriptionActive.value }

        // Mock TokenManager flows
        every { tokenManager.aiSuggestionsEnabled } returns mockAiSuggestionsEnabled
        every { tokenManager.aiNewTagsEnabled } returns mockAiNewTagsEnabled
        every { tokenManager.aiWifiOnly } returns mockAiWifiOnly

        premiumFeatureManager = PremiumFeatureManager(billingManager, tokenManager)
    }

    // ==================== isAiEnabled Tests ====================

    @Test
    fun `isAiEnabled is true when subscription active and user enabled`() = runTest {
        mockSubscriptionActive.value = true
        mockAiSuggestionsEnabled.value = true

        premiumFeatureManager.isAiEnabled.test {
            assertTrue(awaitItem())
        }
    }

    @Test
    fun `isAiEnabled is false when subscription inactive`() = runTest {
        mockSubscriptionActive.value = false
        mockAiSuggestionsEnabled.value = true

        premiumFeatureManager.isAiEnabled.test {
            assertFalse(awaitItem())
        }
    }

    @Test
    fun `isAiEnabled is false when user disabled AI`() = runTest {
        mockSubscriptionActive.value = true
        mockAiSuggestionsEnabled.value = false

        premiumFeatureManager.isAiEnabled.test {
            assertFalse(awaitItem())
        }
    }

    // ==================== isAiNewTagsEnabled Tests ====================

    @Test
    fun `isAiNewTagsEnabled is true when AI enabled and new tags enabled`() = runTest {
        mockSubscriptionActive.value = true
        mockAiSuggestionsEnabled.value = true
        mockAiNewTagsEnabled.value = true

        premiumFeatureManager.isAiNewTagsEnabled.test {
            assertTrue(awaitItem())
        }
    }

    @Test
    fun `isAiNewTagsEnabled is false when new tags disabled`() = runTest {
        mockSubscriptionActive.value = true
        mockAiSuggestionsEnabled.value = true
        mockAiNewTagsEnabled.value = false

        premiumFeatureManager.isAiNewTagsEnabled.test {
            assertFalse(awaitItem())
        }
    }

    @Test
    fun `isAiNewTagsEnabled is false when AI disabled`() = runTest {
        mockSubscriptionActive.value = true
        mockAiSuggestionsEnabled.value = false
        mockAiNewTagsEnabled.value = true

        premiumFeatureManager.isAiNewTagsEnabled.test {
            assertFalse(awaitItem())
        }
    }

    // ==================== isFeatureAvailable Sync Tests ====================

    @Test
    fun `isFeatureAvailable AI_ANALYSIS returns true when subscribed`() {
        mockSubscriptionActive.value = true

        assertTrue(premiumFeatureManager.isFeatureAvailable(PremiumFeature.AI_ANALYSIS))
    }

    @Test
    fun `isFeatureAvailable AI_ANALYSIS returns false when not subscribed`() {
        mockSubscriptionActive.value = false

        assertFalse(premiumFeatureManager.isFeatureAvailable(PremiumFeature.AI_ANALYSIS))
    }

    @Test
    fun `isFeatureAvailable AI_SUMMARY returns false (not implemented)`() {
        mockSubscriptionActive.value = true

        assertFalse(premiumFeatureManager.isFeatureAvailable(PremiumFeature.AI_SUMMARY))
    }

    // ==================== isFeatureAvailableAsync Tests ====================

    @Test
    fun `isFeatureAvailableAsync AI_ANALYSIS respects user preference`() = runTest {
        mockSubscriptionActive.value = true
        mockAiSuggestionsEnabled.value = false

        // Should return false because user disabled it
        assertFalse(premiumFeatureManager.isFeatureAvailableAsync(PremiumFeature.AI_ANALYSIS))
    }

    @Test
    fun `isFeatureAvailableAsync AI_ANALYSIS returns true when enabled`() = runTest {
        mockSubscriptionActive.value = true
        mockAiSuggestionsEnabled.value = true

        assertTrue(premiumFeatureManager.isFeatureAvailableAsync(PremiumFeature.AI_ANALYSIS))
    }

    // ==================== requireFeature Tests ====================

    @Test
    fun `requireFeature returns Granted when feature available`() = runTest {
        mockSubscriptionActive.value = true
        mockAiSuggestionsEnabled.value = true

        val result = premiumFeatureManager.requireFeature(PremiumFeature.AI_ANALYSIS)

        assertEquals(FeatureAccessResult.Granted, result)
    }

    @Test
    fun `requireFeature returns RequiresUpgrade when no subscription`() = runTest {
        mockSubscriptionActive.value = false
        mockAiSuggestionsEnabled.value = true

        val result = premiumFeatureManager.requireFeature(PremiumFeature.AI_ANALYSIS)

        assertEquals(FeatureAccessResult.RequiresUpgrade, result)
    }

    @Test
    fun `requireFeature returns DisabledInSettings when subscribed but disabled`() = runTest {
        mockSubscriptionActive.value = true
        mockAiSuggestionsEnabled.value = false

        val result = premiumFeatureManager.requireFeature(PremiumFeature.AI_ANALYSIS)

        assertEquals(FeatureAccessResult.DisabledInSettings, result)
    }

    // ==================== Reactive Updates Tests ====================

    @Test
    fun `isAiEnabled reacts to subscription changes`() = runTest {
        mockAiSuggestionsEnabled.value = true

        premiumFeatureManager.isAiEnabled.test {
            // Initially false (no subscription)
            mockSubscriptionActive.value = false
            assertFalse(awaitItem())

            // Becomes true when subscription activates
            mockSubscriptionActive.value = true
            assertTrue(awaitItem())

            // Becomes false when subscription expires
            mockSubscriptionActive.value = false
            assertFalse(awaitItem())
        }
    }

    @Test
    fun `isAiEnabled reacts to user preference changes`() = runTest {
        mockSubscriptionActive.value = true

        premiumFeatureManager.isAiEnabled.test {
            // Initially true (subscribed + enabled)
            mockAiSuggestionsEnabled.value = true
            assertTrue(awaitItem())

            // Becomes false when user disables
            mockAiSuggestionsEnabled.value = false
            assertFalse(awaitItem())

            // Becomes true when user re-enables
            mockAiSuggestionsEnabled.value = true
            assertTrue(awaitItem())
        }
    }
}
