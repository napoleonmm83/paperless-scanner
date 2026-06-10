package com.paperless.scanner.ui.screens.upload.usecase

import android.content.Context
import android.util.Log
import com.paperless.scanner.data.ai.SuggestionOrchestrator
import com.paperless.scanner.data.analytics.AnalyticsService
import com.paperless.scanner.data.billing.PremiumFeature
import com.paperless.scanner.data.billing.PremiumFeatureManager
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.data.repository.AiUsageRepository
import com.paperless.scanner.data.repository.UsageLimitStatus
import com.paperless.scanner.util.CoroutineDispatchers
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AnalyzeDocumentUseCaseTest {

    private lateinit var context: Context
    private lateinit var suggestionOrchestrator: SuggestionOrchestrator
    private lateinit var aiUsageRepository: AiUsageRepository
    private lateinit var premiumFeatureManager: PremiumFeatureManager
    private lateinit var analyticsService: AnalyticsService
    private lateinit var tokenManager: TokenManager
    private lateinit var useCase: AnalyzeDocumentUseCase

    private val dispatchers = UnconfinedTestDispatcher().let {
        CoroutineDispatchers(io = it, default = it, main = it)
    }

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0

        context = mockk(relaxed = true)
        suggestionOrchestrator = mockk(relaxed = true)
        aiUsageRepository = mockk(relaxed = true)
        premiumFeatureManager = mockk(relaxed = true)
        analyticsService = mockk(relaxed = true)
        tokenManager = mockk(relaxed = true)

        // Explicit stub (#296): a relaxed suspend-String mock would silently return ""
        // and the exact-value verifications below would fail confusingly.
        coEvery { premiumFeatureManager.analyticsSubscriptionType() } returns "free"

        useCase = AnalyzeDocumentUseCase(
            context = context,
            suggestionOrchestrator = suggestionOrchestrator,
            aiUsageRepository = aiUsageRepository,
            premiumFeatureManager = premiumFeatureManager,
            analyticsService = analyticsService,
            tokenManager = tokenManager,
            dispatchers = dispatchers,
        )
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `checkUsageLimit delegates to repository`() = runTest {
        coEvery { aiUsageRepository.checkUsageLimit() } returns UsageLimitStatus.SOFT_LIMIT_200

        assertEquals(UsageLimitStatus.SOFT_LIMIT_200, useCase.checkUsageLimit())
    }

    @Test
    fun `logFirebaseUsage logs usage and tracks analytics`() = runTest {
        useCase.logFirebaseUsage()

        coVerify {
            aiUsageRepository.logUsage(
                featureType = "document_analysis",
                inputTokens = 1000,
                outputTokens = 200,
                success = true,
                subscriptionType = "free"
            )
        }
        coVerify {
            analyticsService.trackAiFeatureUsage(
                featureType = "document_analysis",
                inputTokens = 1000,
                outputTokens = 200,
                subscriptionType = "free"
            )
        }
        // Compute-once pin (#296): both records must come from a single lookup.
        coVerify(exactly = 1) { premiumFeatureManager.analyticsSubscriptionType() }
    }

    @Test
    fun `logFirebaseUsage reports premium for subscribed users on BOTH records`() = runTest {
        coEvery { premiumFeatureManager.analyticsSubscriptionType() } returns "premium"

        useCase.logFirebaseUsage()

        coVerify {
            aiUsageRepository.logUsage(
                featureType = "document_analysis",
                inputTokens = 1000,
                outputTokens = 200,
                success = true,
                subscriptionType = "premium"
            )
        }
        coVerify {
            analyticsService.trackAiFeatureUsage(
                featureType = "document_analysis",
                inputTokens = 1000,
                outputTokens = 200,
                subscriptionType = "premium"
            )
        }
    }

    @Test
    fun `isAiAvailable delegates to premium feature manager`() {
        every { premiumFeatureManager.isFeatureAvailable(PremiumFeature.AI_ANALYSIS) } returns true

        assertTrue(useCase.isAiAvailable())
    }
}
