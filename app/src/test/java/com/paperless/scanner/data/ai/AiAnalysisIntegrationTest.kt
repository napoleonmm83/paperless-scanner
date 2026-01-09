package com.paperless.scanner.data.ai

import android.content.Context
import android.graphics.Bitmap
import com.paperless.scanner.data.ai.models.SuggestionResult
import com.paperless.scanner.data.ai.models.SuggestionSource
import com.paperless.scanner.data.billing.PremiumFeature
import com.paperless.scanner.data.billing.PremiumFeatureManager
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.data.repository.CorrespondentRepository
import com.paperless.scanner.data.repository.DocumentTypeRepository
import com.paperless.scanner.data.repository.TagRepository
import com.paperless.scanner.domain.model.Correspondent
import com.paperless.scanner.domain.model.DocumentType
import com.paperless.scanner.domain.model.Tag
import io.mockk.*
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Integration tests for AI Analysis flow.
 *
 * These tests verify the complete integration between:
 * - SuggestionOrchestrator (fallback chain logic)
 * - AiAnalysisService (Firebase AI integration)
 * - PremiumFeatureManager (premium checks)
 * - TagMatchingEngine (local fallback)
 * - NetworkMonitor (online status)
 * - Repositories (data access)
 *
 * Focus areas:
 * 1. End-to-end suggestion flow
 * 2. Fallback chain (AI → Paperless → Local)
 * 3. Premium feature gating
 * 4. Network status handling
 * 5. Error recovery
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AiAnalysisIntegrationTest {

    private lateinit var suggestionOrchestrator: SuggestionOrchestrator
    private lateinit var aiAnalysisService: AiAnalysisService
    private lateinit var tagMatchingEngine: TagMatchingEngine
    private lateinit var paperlessSuggestionsService: PaperlessSuggestionsService
    private lateinit var premiumFeatureManager: PremiumFeatureManager
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var tagRepository: TagRepository
    private lateinit var correspondentRepository: CorrespondentRepository
    private lateinit var documentTypeRepository: DocumentTypeRepository
    private lateinit var context: Context

    // Test data
    private val testTags = listOf(
        Tag(id = 1, name = "Invoice", color = "#FF0000", match = "invoice,bill,rechnung"),
        Tag(id = 2, name = "Receipt", color = "#00FF00", match = "receipt,quittung"),
        Tag(id = 3, name = "Contract", color = "#0000FF", match = "contract,vertrag")
    )

    private val testCorrespondents = listOf(
        Correspondent(id = 1, name = "ACME Corp"),
        Correspondent(id = 2, name = "Global Ltd")
    )

    private val testDocumentTypes = listOf(
        DocumentType(id = 1, name = "Invoice"),
        DocumentType(id = 2, name = "Contract")
    )

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        premiumFeatureManager = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)
        tagRepository = mockk(relaxed = true)
        correspondentRepository = mockk(relaxed = true)
        documentTypeRepository = mockk(relaxed = true)
        paperlessSuggestionsService = mockk(relaxed = true)
        tagMatchingEngine = mockk(relaxed = true)

        // Setup repository flows
        every { tagRepository.observeTags() } returns flowOf(testTags)
        every { correspondentRepository.observeCorrespondents() } returns flowOf(testCorrespondents)
        every { documentTypeRepository.observeDocumentTypes() } returns flowOf(testDocumentTypes)

        // Create real AiAnalysisService (we'll mock Firebase via constructor)
        aiAnalysisService = AiAnalysisService(context)

        // Create real SuggestionOrchestrator with mixed real/mock dependencies
        suggestionOrchestrator = SuggestionOrchestrator(
            premiumFeatureManager = premiumFeatureManager,
            aiAnalysisService = aiAnalysisService,
            tagMatchingEngine = tagMatchingEngine,
            paperlessSuggestionsService = paperlessSuggestionsService,
            networkMonitor = networkMonitor,
            tagRepository = tagRepository,
            correspondentRepository = correspondentRepository,
            documentTypeRepository = documentTypeRepository
        )
    }

    // ==================== Premium Feature Gating Tests ====================

    @Test
    fun `getSuggestions skips AI when premium not available`() = runTest {
        // Given: Premium NOT available
        every { premiumFeatureManager.isFeatureAvailable(PremiumFeature.AI_ANALYSIS) } returns false
        every { networkMonitor.checkOnlineStatus() } returns false

        // When: Request suggestions with bitmap
        val bitmap = mockk<Bitmap>(relaxed = true)
        val result = suggestionOrchestrator.getSuggestions(
            bitmap = bitmap,
            extractedText = "This is an invoice from ACME Corp"
        )

        // Then: Should use local matching only (no AI)
        assertTrue(result is SuggestionResult.Success)
        val success = result as SuggestionResult.Success
        assertEquals(SuggestionSource.LOCAL_MATCHING, success.source)
    }

    @Test
    fun `getSuggestions uses AI when premium available`() = runTest {
        // Given: Premium IS available
        every { premiumFeatureManager.isFeatureAvailable(PremiumFeature.AI_ANALYSIS) } returns true
        every { networkMonitor.checkOnlineStatus() } returns true

        // Mock successful AI analysis
        val aiService = spyk(aiAnalysisService)
        val testSuggestions = createMockDocumentAnalysis()
        coEvery {
            aiService.analyzeImage(any(), any(), any(), any())
        } returns Result.success(testSuggestions)

        // Create orchestrator with mocked AI service
        val orchestrator = SuggestionOrchestrator(
            premiumFeatureManager = premiumFeatureManager,
            aiAnalysisService = aiService,
            tagMatchingEngine = tagMatchingEngine,
            paperlessSuggestionsService = paperlessSuggestionsService,
            networkMonitor = networkMonitor,
            tagRepository = tagRepository,
            correspondentRepository = correspondentRepository,
            documentTypeRepository = documentTypeRepository
        )

        // When: Request suggestions with bitmap
        val bitmap = mockk<Bitmap>(relaxed = true)
        val result = orchestrator.getSuggestions(bitmap = bitmap)

        // Then: Should use AI
        assertTrue(result is SuggestionResult.Success)
        val success = result as SuggestionResult.Success
        assertEquals(SuggestionSource.FIREBASE_AI, success.source)

        // Verify AI was called
        coVerify(exactly = 1) { aiService.analyzeImage(any(), any(), any(), any()) }
    }

    // ==================== Fallback Chain Tests ====================

    @Test
    fun `fallback chain works when AI fails`() = runTest {
        // Given: Premium available but AI fails
        every { premiumFeatureManager.isFeatureAvailable(PremiumFeature.AI_ANALYSIS) } returns true
        every { networkMonitor.checkOnlineStatus() } returns true

        val aiService = spyk(aiAnalysisService)
        coEvery {
            aiService.analyzeImage(any(), any(), any(), any())
        } returns Result.failure(Exception("Firebase AI error"))

        // Mock Paperless API success
        val paperlessSuggestions = createMockDocumentAnalysis()
        coEvery {
            paperlessSuggestionsService.getSuggestions(any())
        } returns Result.success(paperlessSuggestions)

        // Create orchestrator
        val orchestrator = SuggestionOrchestrator(
            premiumFeatureManager = premiumFeatureManager,
            aiAnalysisService = aiService,
            tagMatchingEngine = tagMatchingEngine,
            paperlessSuggestionsService = paperlessSuggestionsService,
            networkMonitor = networkMonitor,
            tagRepository = tagRepository,
            correspondentRepository = correspondentRepository,
            documentTypeRepository = documentTypeRepository
        )

        // When: Request suggestions with documentId
        val bitmap = mockk<Bitmap>(relaxed = true)
        val result = orchestrator.getSuggestions(
            bitmap = bitmap,
            documentId = 123
        )

        // Then: Should fallback to Paperless API
        assertTrue(result is SuggestionResult.Success)
        val success = result as SuggestionResult.Success
        assertEquals(SuggestionSource.PAPERLESS_API, success.source)

        // Verify fallback chain
        coVerify(exactly = 1) { aiService.analyzeImage(any(), any(), any(), any()) }
        coVerify(exactly = 1) { paperlessSuggestionsService.getSuggestions(123) }
    }

    @Test
    fun `fallback chain reaches local matching when all services fail`() = runTest {
        // Given: All services fail or unavailable
        every { premiumFeatureManager.isFeatureAvailable(PremiumFeature.AI_ANALYSIS) } returns true
        every { networkMonitor.checkOnlineStatus() } returns false // Offline

        val aiService = spyk(aiAnalysisService)
        coEvery {
            aiService.analyzeImage(any(), any(), any(), any())
        } returns Result.failure(Exception("Network error"))

        // Mock local matching success
        val localSuggestions = listOf(
            com.paperless.scanner.data.ai.models.TagSuggestion(
                tagId = 1,
                tagName = "Invoice",
                confidence = 0.8f,
                reason = com.paperless.scanner.data.ai.models.TagSuggestion.REASON_KEYWORD_MATCH
            )
        )
        every { tagMatchingEngine.findMatchingTags(any(), any()) } returns localSuggestions

        // Create orchestrator
        val orchestrator = SuggestionOrchestrator(
            premiumFeatureManager = premiumFeatureManager,
            aiAnalysisService = aiService,
            tagMatchingEngine = tagMatchingEngine,
            paperlessSuggestionsService = paperlessSuggestionsService,
            networkMonitor = networkMonitor,
            tagRepository = tagRepository,
            correspondentRepository = correspondentRepository,
            documentTypeRepository = documentTypeRepository
        )

        // When: Request suggestions offline
        val bitmap = mockk<Bitmap>(relaxed = true)
        val result = orchestrator.getSuggestions(
            bitmap = bitmap,
            extractedText = "Invoice from ACME Corp"
        )

        // Then: Should fallback to local matching
        assertTrue(result is SuggestionResult.Success)
        val success = result as SuggestionResult.Success
        assertEquals(SuggestionSource.LOCAL_MATCHING, success.source)
        assertEquals(1, success.analysis.suggestedTags.size)

        // Verify fallback chain
        verify { tagMatchingEngine.findMatchingTags(any(), any()) }
        coVerify(exactly = 0) { paperlessSuggestionsService.getSuggestions(any()) } // Skipped (offline)
    }

    // ==================== Network Status Tests ====================

    @Test
    fun `offline status skips Paperless API`() = runTest {
        // Given: Offline
        every { premiumFeatureManager.isFeatureAvailable(PremiumFeature.AI_ANALYSIS) } returns false
        every { networkMonitor.checkOnlineStatus() } returns false

        every { tagMatchingEngine.findMatchingTags(any(), any()) } returns emptyList()

        // When: Request suggestions with documentId (would normally use Paperless API)
        val result = suggestionOrchestrator.getSuggestions(
            extractedText = "Test document",
            documentId = 123
        )

        // Then: Should skip Paperless API and use local matching
        assertTrue(result is SuggestionResult.Success)
        val success = result as SuggestionResult.Success
        assertEquals(SuggestionSource.LOCAL_MATCHING, success.source)

        // Verify Paperless API was NOT called
        coVerify(exactly = 0) { paperlessSuggestionsService.getSuggestions(any()) }
    }

    @Test
    fun `online status allows Paperless API`() = runTest {
        // Given: Online
        every { premiumFeatureManager.isFeatureAvailable(PremiumFeature.AI_ANALYSIS) } returns false
        every { networkMonitor.checkOnlineStatus() } returns true

        val paperlessSuggestions = createMockDocumentAnalysis()
        coEvery {
            paperlessSuggestionsService.getSuggestions(any())
        } returns Result.success(paperlessSuggestions)

        // When: Request suggestions with documentId
        val result = suggestionOrchestrator.getSuggestions(documentId = 123)

        // Then: Should use Paperless API
        assertTrue(result is SuggestionResult.Success)
        val success = result as SuggestionResult.Success
        assertEquals(SuggestionSource.PAPERLESS_API, success.source)

        // Verify Paperless API was called
        coVerify(exactly = 1) { paperlessSuggestionsService.getSuggestions(123) }
    }

    // ==================== Suggestion Merging Tests ====================

    @Test
    fun `suggestions are merged and deduplicated correctly`() = runTest {
        // Given: Multiple sources return overlapping suggestions
        every { premiumFeatureManager.isFeatureAvailable(PremiumFeature.AI_ANALYSIS) } returns true
        every { networkMonitor.checkOnlineStatus() } returns true

        val aiService = spyk(aiAnalysisService)
        val aiSuggestions = createMockDocumentAnalysis(
            tags = listOf(
                com.paperless.scanner.data.ai.models.TagSuggestion(
                    tagId = 1, tagName = "Invoice", confidence = 0.95f,
                    reason = com.paperless.scanner.data.ai.models.TagSuggestion.REASON_AI_DETECTED
                ),
                com.paperless.scanner.data.ai.models.TagSuggestion(
                    tagId = 2, tagName = "Receipt", confidence = 0.85f,
                    reason = com.paperless.scanner.data.ai.models.TagSuggestion.REASON_AI_DETECTED
                )
            )
        )
        coEvery { aiService.analyzeImage(any(), any(), any(), any()) } returns Result.success(aiSuggestions)

        // Local matching also finds "Invoice" but with lower confidence
        val localSuggestions = listOf(
            com.paperless.scanner.data.ai.models.TagSuggestion(
                tagId = 1, tagName = "Invoice", confidence = 0.7f,
                reason = com.paperless.scanner.data.ai.models.TagSuggestion.REASON_KEYWORD_MATCH
            ),
            com.paperless.scanner.data.ai.models.TagSuggestion(
                tagId = 3, tagName = "Contract", confidence = 0.6f,
                reason = com.paperless.scanner.data.ai.models.TagSuggestion.REASON_KEYWORD_MATCH
            )
        )
        every { tagMatchingEngine.findMatchingTags(any(), any()) } returns localSuggestions

        // Create orchestrator
        val orchestrator = SuggestionOrchestrator(
            premiumFeatureManager = premiumFeatureManager,
            aiAnalysisService = aiService,
            tagMatchingEngine = tagMatchingEngine,
            paperlessSuggestionsService = paperlessSuggestionsService,
            networkMonitor = networkMonitor,
            tagRepository = tagRepository,
            correspondentRepository = correspondentRepository,
            documentTypeRepository = documentTypeRepository
        )

        // When: Request suggestions
        val bitmap = mockk<Bitmap>(relaxed = true)
        val result = orchestrator.getSuggestions(
            bitmap = bitmap,
            extractedText = "Invoice document"
        )

        // Then: Suggestions should be merged and deduplicated
        assertTrue(result is SuggestionResult.Success)
        val success = result as SuggestionResult.Success

        // Should have 3 unique tags: Invoice (AI confidence 0.95), Receipt (AI 0.85), Contract (local 0.6)
        assertEquals(3, success.analysis.suggestedTags.size)

        // Invoice should use higher AI confidence, not local
        val invoiceTag = success.analysis.suggestedTags.find { it.tagId == 1 }
        assertNotNull(invoiceTag)
        assertEquals(0.95f, invoiceTag!!.confidence, 0.01f)

        // Tags should be sorted by confidence descending
        assertTrue(success.analysis.suggestedTags[0].confidence >= success.analysis.suggestedTags[1].confidence)
        assertTrue(success.analysis.suggestedTags[1].confidence >= success.analysis.suggestedTags[2].confidence)
    }

    // ==================== Error Handling Tests ====================

    @Test
    fun `handles null bitmap gracefully`() = runTest {
        // Given: Premium available
        every { premiumFeatureManager.isFeatureAvailable(PremiumFeature.AI_ANALYSIS) } returns true
        every { networkMonitor.checkOnlineStatus() } returns false

        every { tagMatchingEngine.findMatchingTags(any(), any()) } returns emptyList()

        // When: No bitmap provided
        val result = suggestionOrchestrator.getSuggestions(
            bitmap = null,
            extractedText = "Test"
        )

        // Then: Should fallback to local matching
        assertTrue(result is SuggestionResult.Success)
        val success = result as SuggestionResult.Success
        assertEquals(SuggestionSource.LOCAL_MATCHING, success.source)
    }

    @Test
    fun `handles empty text gracefully`() = runTest {
        // Given: No premium, offline
        every { premiumFeatureManager.isFeatureAvailable(PremiumFeature.AI_ANALYSIS) } returns false
        every { networkMonitor.checkOnlineStatus() } returns false

        // When: Empty text provided
        val result = suggestionOrchestrator.getSuggestions(extractedText = "")

        // Then: Should return success with empty suggestions
        assertTrue(result is SuggestionResult.Success)
        val success = result as SuggestionResult.Success
        assertTrue(success.analysis.suggestedTags.isEmpty())
    }

    // ==================== Helper Methods ====================

    private fun createMockDocumentAnalysis(
        tags: List<com.paperless.scanner.data.ai.models.TagSuggestion> = emptyList()
    ): com.paperless.scanner.data.ai.models.DocumentAnalysis {
        return com.paperless.scanner.data.ai.models.DocumentAnalysis(
            suggestedTitle = "Test Document",
            suggestedTags = tags,
            suggestedCorrespondent = "Test Corp",
            suggestedDocumentType = "Invoice",
            suggestedDate = "2024-01-15",
            confidence = 0.9f
        )
    }
}
