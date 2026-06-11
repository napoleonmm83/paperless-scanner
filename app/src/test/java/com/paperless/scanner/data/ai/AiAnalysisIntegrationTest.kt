package com.paperless.scanner.data.ai

import android.content.Context
import android.graphics.Bitmap
import com.paperless.scanner.data.ai.models.SuggestionError
import com.paperless.scanner.data.ai.models.SuggestionResult
import com.paperless.scanner.data.ai.models.SuggestionSource
import com.paperless.scanner.data.billing.PremiumFeature
import com.paperless.scanner.data.billing.PremiumFeatureManager
import com.paperless.scanner.data.datastore.TokenManager
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
 * These exercise the REAL [SuggestionOrchestrator] fallback-chain logic. Test doubles
 * follow a hybrid approach (#363):
 * - [OcrTextExtractor] → recording FAKE (it is an interface seam): the premium-only OCR
 *   contract pins are state assertions on recorded invocations, not mock verification.
 * - [TagMatchingEngine] → REAL instance (pure keyword/fuzzy/synonym logic, no deps):
 *   local-matching assertions check actual matching results against the test tags.
 * - Remaining collaborators → STRICT mocks (#143): they are final `@Inject` classes
 *   without contract seams (same constraint that deferred #202/#239). Every exercised
 *   call must be stubbed, so an unexpected call — e.g. the Paperless API while offline —
 *   fails the test with a MockKException. That property doubles as the negative-call
 *   pin without `coVerify(exactly = 0)` interaction checks.
 * - AiAnalysisService is real but spyk'd per AI test (Firebase never actually invoked).
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
    private lateinit var tokenManager: TokenManager
    private lateinit var tagRepository: TagRepository
    private lateinit var correspondentRepository: CorrespondentRepository
    private lateinit var documentTypeRepository: DocumentTypeRepository
    private lateinit var ocrTextExtractor: RecordingOcrTextExtractor
    private lateinit var context: Context

    // Test data — the real TagMatchingEngine matches against these `match` keyword
    // patterns: "invoice"/"rechnung" → Invoice, "vertrag" → Contract, etc. The English
    // tag names deliberately don't collide with the engine's German-keyed synonym map.
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
        // context stays relaxed: it backs the real AiAnalysisService(context) constructed below
        // (Firebase/Android calls), which is spyk'd with analyzeImage stubbed in the AI tests.
        context = mockk(relaxed = true)
        // Collaborators without seams are STRICT mocks (#143): every exercised call must be
        // stubbed, so a missing behaviour surfaces as a MockKException instead of a silent
        // relaxed default — and an unexpected call fails the test (negative-call pin).
        premiumFeatureManager = mockk()
        networkMonitor = mockk()
        tokenManager = mockk()
        tagRepository = mockk()
        correspondentRepository = mockk()
        documentTypeRepository = mockk()
        paperlessSuggestionsService = mockk()
        // #363: real pure matching engine + recording OCR fake (see class kdoc).
        tagMatchingEngine = TagMatchingEngine()
        ocrTextExtractor = RecordingOcrTextExtractor()

        // Setup repository flows
        every { tagRepository.observeTags() } returns flowOf(testTags)
        coEvery { correspondentRepository.getCachedCorrespondents() } returns testCorrespondents
        coEvery { documentTypeRepository.getCachedDocumentTypes() } returns testDocumentTypes

        // Setup tokenManager flows (required by SuggestionOrchestrator)
        every { tokenManager.aiWifiOnly } returns flowOf(false)
        every { tokenManager.aiNewTagsEnabled } returns flowOf(true)

        // Create real AiAnalysisService (we'll mock Firebase via constructor)
        aiAnalysisService = AiAnalysisService(context)

        // Create real SuggestionOrchestrator with mixed real/fake/mock dependencies
        suggestionOrchestrator = SuggestionOrchestrator(
            premiumFeatureManager = premiumFeatureManager,
            aiAnalysisService = aiAnalysisService,
            tagMatchingEngine = tagMatchingEngine,
            paperlessSuggestionsService = paperlessSuggestionsService,
            networkMonitor = networkMonitor,
            tokenManager = tokenManager,
            tagRepository = tagRepository,
            correspondentRepository = correspondentRepository,
            documentTypeRepository = documentTypeRepository,
            ocrTextExtractor = ocrTextExtractor
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

        // Then: Should use local matching only (no AI) — the real engine matched the
        // Invoice keyword pattern in the caller text.
        assertTrue(result is SuggestionResult.Success)
        val success = result as SuggestionResult.Success
        assertEquals(SuggestionSource.LOCAL_MATCHING, success.source)
        assertEquals(listOf("Invoice"), success.analysis.suggestedTags.map { it.tagName })
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

        // When: Request suggestions with bitmap
        val bitmap = mockk<Bitmap>(relaxed = true)
        val result = buildOrchestrator(aiService).getSuggestions(bitmap = bitmap)

        // Then: Should use AI — FIREBASE_AI as source is only reachable via the AI call
        assertTrue(result is SuggestionResult.Success)
        val success = result as SuggestionResult.Success
        assertEquals(SuggestionSource.FIREBASE_AI, success.source)
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

        // Mock Paperless API success with actual tags — stubbed for documentId 123 ONLY,
        // so a call with any other id fails the strict mock (id-routing pin without verify).
        val paperlessSuggestions = createMockDocumentAnalysis(
            tags = listOf(
                com.paperless.scanner.data.ai.models.TagSuggestion(
                    tagId = 1, tagName = "Invoice", confidence = 0.85f,
                    reason = com.paperless.scanner.data.ai.models.TagSuggestion.REASON_PAPERLESS_API
                )
            )
        )
        coEvery {
            paperlessSuggestionsService.getSuggestions(123)
        } returns Result.success(paperlessSuggestions)

        // When: Request suggestions with documentId
        val bitmap = mockk<Bitmap>(relaxed = true)
        val result = buildOrchestrator(aiService).getSuggestions(
            bitmap = bitmap,
            documentId = 123
        )

        // Then: Should fallback to Paperless API via the (id-specific) stubbed call
        assertTrue(result is SuggestionResult.Success)
        val success = result as SuggestionResult.Success
        assertEquals(SuggestionSource.PAPERLESS_API, success.source)

        // The AI attempt leaves no observable state when Paperless answers afterwards,
        // so this boundary verification IS the fallback-order contract (codex P3): without
        // it, an orchestrator that skips AI entirely would pass this test.
        coVerify(exactly = 1) { aiService.analyzeImage(any(), any(), any(), any()) }
    }

    @Test
    fun `fallback chain reaches local matching when all services fail`() = runTest {
        // Given: All services fail or unavailable. The Paperless mock is deliberately
        // NOT stubbed — a call to it (we're offline) would fail the test.
        every { premiumFeatureManager.isFeatureAvailable(PremiumFeature.AI_ANALYSIS) } returns true
        every { networkMonitor.checkOnlineStatus() } returns false // Offline

        val aiService = spyk(aiAnalysisService)
        coEvery {
            aiService.analyzeImage(any(), any(), any(), any())
        } returns Result.failure(Exception("Network error"))

        // When: Request suggestions offline
        val bitmap = mockk<Bitmap>(relaxed = true)
        val result = buildOrchestrator(aiService).getSuggestions(
            bitmap = bitmap,
            extractedText = "Invoice from ACME Corp"
        )

        // Then: Should fallback to local matching — the real engine matched Invoice.
        assertTrue(result is SuggestionResult.Success)
        val success = result as SuggestionResult.Success
        assertEquals(SuggestionSource.LOCAL_MATCHING, success.source)
        assertEquals(listOf("Invoice"), success.analysis.suggestedTags.map { it.tagName })

        // Same rationale as the Paperless fallback test: a failed AI attempt is not
        // observable in the result, so the boundary verification pins the chain order.
        coVerify(exactly = 1) { aiService.analyzeImage(any(), any(), any(), any()) }
    }

    // ==================== Network Status Tests ====================

    @Test
    fun `offline status skips Paperless API`() = runTest {
        // Given: Offline. The Paperless mock is deliberately NOT stubbed — a call to it
        // would fail the test (the offline-skip pin).
        every { premiumFeatureManager.isFeatureAvailable(PremiumFeature.AI_ANALYSIS) } returns false
        every { networkMonitor.checkOnlineStatus() } returns false

        // When: Request suggestions with documentId (would normally use Paperless API)
        val result = suggestionOrchestrator.getSuggestions(
            extractedText = "Test document",
            documentId = 123
        )

        // Then: Should skip Paperless API and use local matching (no match for this text)
        assertTrue(result is SuggestionResult.Success)
        val success = result as SuggestionResult.Success
        assertEquals(SuggestionSource.LOCAL_MATCHING, success.source)
        assertTrue(success.analysis.suggestedTags.isEmpty())
    }

    @Test
    fun `online status allows Paperless API`() = runTest {
        // Given: Online
        every { premiumFeatureManager.isFeatureAvailable(PremiumFeature.AI_ANALYSIS) } returns false
        every { networkMonitor.checkOnlineStatus() } returns true

        val paperlessSuggestions = createMockDocumentAnalysis()
        coEvery {
            paperlessSuggestionsService.getSuggestions(123)
        } returns Result.success(paperlessSuggestions)

        // When: Request suggestions with documentId
        val result = suggestionOrchestrator.getSuggestions(documentId = 123)

        // Then: Should use Paperless API (id-specific stub — wrong id would throw)
        assertTrue(result is SuggestionResult.Success)
        val success = result as SuggestionResult.Success
        assertEquals(SuggestionSource.PAPERLESS_API, success.source)
    }

    // ==================== Suggestion Merging Tests ====================

    @Test
    fun `suggestions are AI-only when AI analysis succeeds`() = runTest {
        // Given: AI analysis succeeds with suggestions
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

        // When: Request suggestions with AI available. The caller text matches Contract
        // (id=3) — a tag the AI deliberately does NOT return — so an accidental fallback
        // run cannot hide behind tag-id deduplication (codex P3).
        val bitmap = mockk<Bitmap>(relaxed = true)
        val result = buildOrchestrator(aiService).getSuggestions(
            bitmap = bitmap,
            extractedText = "Vertrag mit Global Ltd"
        )

        // Then: Only AI suggestions returned (local matching skipped when AI succeeds)
        assertTrue(result is SuggestionResult.Success)
        val success = result as SuggestionResult.Success
        assertEquals(SuggestionSource.FIREBASE_AI, success.source)

        // Exactly the 2 AI tags and no Contract — had the fallback run, the real engine's
        // Contract match would survive dedup and appear here (state proof of AI-only mode).
        assertEquals(2, success.analysis.suggestedTags.size)
        assertNull(success.analysis.suggestedTags.find { it.tagId == 3 })

        // #296: no wasted OCR on the premium happy path — the recording fake saw no call.
        assertEquals(0, ocrTextExtractor.invocations)

        // Invoice should have AI confidence
        val invoiceTag = success.analysis.suggestedTags.find { it.tagId == 1 }
        assertNotNull(invoiceTag)
        assertEquals(0.95f, invoiceTag!!.confidence, 0.01f)

        // Tags should be sorted by confidence descending
        assertTrue(success.analysis.suggestedTags[0].confidence >= success.analysis.suggestedTags[1].confidence)
    }

    // ==================== OCR Text Extraction Tests (#296) ====================

    @Test
    fun `free user never gets OCR - intelligent suggestions are premium-only`() = runTest {
        // Given: no premium, offline, no caller-provided text. Product decision
        // (2026-06-11): on-device text recognition is part of the paid intelligent
        // suggestions — free users keep the pre-#296 behavior (no suggestions here).
        every { premiumFeatureManager.isFeatureAvailable(PremiumFeature.AI_ANALYSIS) } returns false
        every { networkMonitor.checkOnlineStatus() } returns false

        // When: scan-flow shape — bitmap only
        val bitmap = mockk<Bitmap>(relaxed = true)
        val result = suggestionOrchestrator.getSuggestions(bitmap = bitmap)

        // Then: OCR never runs (recorded zero invocations), empty success
        assertTrue(result is SuggestionResult.Success)
        val success = result as SuggestionResult.Success
        assertTrue(success.analysis.suggestedTags.isEmpty())
        assertEquals(0, ocrTextExtractor.invocations)
    }

    @Test
    fun `caller-provided text still feeds local matching for free users - without OCR`() = runTest {
        // Given: DocumentDetail shape — server-side content is already available. This
        // pre-existing free functionality must survive the premium-only OCR gating.
        every { premiumFeatureManager.isFeatureAvailable(PremiumFeature.AI_ANALYSIS) } returns false
        every { networkMonitor.checkOnlineStatus() } returns false

        // When: both text and bitmap provided
        val bitmap = mockk<Bitmap>(relaxed = true)
        val result = suggestionOrchestrator.getSuggestions(
            bitmap = bitmap,
            extractedText = "Invoice from ACME Corp"
        )

        // Then: the provided text was matched (real engine found Invoice), OCR never ran
        assertTrue(result is SuggestionResult.Success)
        val success = result as SuggestionResult.Success
        assertEquals(listOf("Invoice"), success.analysis.suggestedTags.map { it.tagName })
        assertEquals(0, ocrTextExtractor.invocations)
    }

    @Test
    fun `premium AI failure falls back to OCR-backed local matching`() = runTest {
        // Given: premium, but the AI call fails; offline so Paperless is skipped too
        every { premiumFeatureManager.isFeatureAvailable(PremiumFeature.AI_ANALYSIS) } returns true
        every { networkMonitor.checkOnlineStatus() } returns false

        val aiService = spyk(aiAnalysisService)
        coEvery {
            aiService.analyzeImage(any(), any(), any(), any())
        } returns Result.failure(Exception("Firebase AI error"))

        ocrTextExtractor.text = "Vertrag mit Global Ltd"

        // When
        val bitmap = mockk<Bitmap>(relaxed = true)
        val result = buildOrchestrator(aiService).getSuggestions(bitmap = bitmap)

        // Then: instead of a bare error, the user gets local suggestions — the real
        // engine matched the Contract keyword pattern in the OCR text.
        assertTrue(result is SuggestionResult.Success)
        val success = result as SuggestionResult.Success
        assertEquals(SuggestionSource.LOCAL_MATCHING, success.source)
        assertEquals(listOf("Contract"), success.analysis.suggestedTags.map { it.tagName })
        assertEquals(1, ocrTextExtractor.invocations)
        assertSame(bitmap, ocrTextExtractor.lastBitmap)
    }

    @Test
    fun `WiFi-required skip does not burn OCR for a result it would discard`() = runTest {
        // Given: premium, WiFi-only enabled, no WiFi — the chain ends in WiFiRequired,
        // so any OCR-backed local suggestions would be thrown away (codex P3).
        every { premiumFeatureManager.isFeatureAvailable(PremiumFeature.AI_ANALYSIS) } returns true
        every { tokenManager.aiWifiOnly } returns flowOf(true)
        every { networkMonitor.isWifiConnectedSync() } returns false
        every { networkMonitor.checkOnlineStatus() } returns false

        // When
        val bitmap = mockk<Bitmap>(relaxed = true)
        val result = suggestionOrchestrator.getSuggestions(bitmap = bitmap)

        // Then: banner shown promptly, no OCR spent
        assertTrue(result is SuggestionResult.WiFiRequired)
        assertEquals(0, ocrTextExtractor.invocations)
    }

    @Test
    fun `blank OCR result reproduces the pre-OCR behavior`() = runTest {
        // Given: premium, AI fails, OCR finds nothing (non-Latin doc, blank page) —
        // before #296 this exact scenario surfaced the AI error; it still must.
        every { premiumFeatureManager.isFeatureAvailable(PremiumFeature.AI_ANALYSIS) } returns true
        every { networkMonitor.checkOnlineStatus() } returns false

        val aiService = spyk(aiAnalysisService)
        coEvery {
            aiService.analyzeImage(any(), any(), any(), any())
        } returns Result.failure(Exception("Firebase AI error"))
        // ocrTextExtractor.text stays "" (the fake's default)

        // When
        val bitmap = mockk<Bitmap>(relaxed = true)
        val result = buildOrchestrator(aiService).getSuggestions(bitmap = bitmap)

        // Then: OCR ran but produced nothing, so no suggestions exist and the AI error
        // surfaces — byte-identical UX to before #296.
        assertTrue(result is SuggestionResult.Error)
        assertEquals(1, ocrTextExtractor.invocations)
    }

    // ==================== SuggestionError Classification Tests (#364) ====================

    /**
     * Runs the chain with premium active, a failing AI call carrying [sdkMessage], blank OCR
     * (no local matches) and the given connectivity — the end-of-chain classifier (#364) decides.
     */
    private suspend fun errorFromFailedAi(sdkMessage: String, online: Boolean): SuggestionResult {
        every { premiumFeatureManager.isFeatureAvailable(PremiumFeature.AI_ANALYSIS) } returns true
        every { networkMonitor.checkOnlineStatus() } returns online

        val aiService = spyk(aiAnalysisService)
        coEvery {
            aiService.analyzeImage(any(), any(), any(), any())
        } returns Result.failure(Exception(sdkMessage))

        return buildOrchestrator(aiService).getSuggestions(bitmap = mockk<Bitmap>(relaxed = true))
    }

    @Test
    fun `AI failure while offline classifies as OFFLINE regardless of SDK wording`() = runTest {
        // "timeout" in the SDK text must NOT win over the actionable root cause: no connectivity.
        val result = errorFromFailedAi("Request timeout: Something unexpected happened.", online = false)

        assertTrue(result is SuggestionResult.Error)
        assertEquals(SuggestionError.OFFLINE, (result as SuggestionResult.Error).error)
    }

    @Test
    fun `AI timeout while online classifies as TIMEOUT`() = runTest {
        val result = errorFromFailedAi("Deadline exceeded: request Timeout", online = true)

        assertTrue(result is SuggestionResult.Error)
        assertEquals(SuggestionError.TIMEOUT, (result as SuggestionResult.Error).error)
    }

    @Test
    fun `AI quota exhaustion while online classifies as QUOTA_EXHAUSTED`() = runTest {
        val result = errorFromFailedAi("Quota exceeded for aiplatform.googleapis.com", online = true)

        assertTrue(result is SuggestionResult.Error)
        assertEquals(SuggestionError.QUOTA_EXHAUSTED, (result as SuggestionResult.Error).error)
    }

    @Test
    fun `AI permission denial while online classifies as NOT_CONFIGURED`() = runTest {
        val result = errorFromFailedAi("PERMISSION_DENIED: Vertex AI API has not been used", online = true)

        assertTrue(result is SuggestionResult.Error)
        assertEquals(SuggestionError.NOT_CONFIGURED, (result as SuggestionResult.Error).error)
    }

    @Test
    fun `unrecognized AI failure while online classifies as UNKNOWN and keeps the exception out of the UI contract`() = runTest {
        val result = errorFromFailedAi("Something unexpected happened.", online = true)

        assertTrue(result is SuggestionResult.Error)
        val error = result as SuggestionResult.Error
        assertEquals(SuggestionError.UNKNOWN, error.error)
        // The raw SDK text travels only in the exception (for logs) — the typed code has no
        // message field, so the UI can only ever render the localized resource (#364).
        assertEquals("Something unexpected happened.", error.exception?.message)
    }

    // ==================== Error Handling Tests ====================

    @Test
    fun `handles null bitmap gracefully`() = runTest {
        // Given: Premium available
        every { premiumFeatureManager.isFeatureAvailable(PremiumFeature.AI_ANALYSIS) } returns true
        every { networkMonitor.checkOnlineStatus() } returns false

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

    /** Builds an orchestrator around an [AiAnalysisService] spy with stubbed analyzeImage. */
    private fun buildOrchestrator(aiService: AiAnalysisService): SuggestionOrchestrator =
        SuggestionOrchestrator(
            premiumFeatureManager = premiumFeatureManager,
            aiAnalysisService = aiService,
            tagMatchingEngine = tagMatchingEngine,
            paperlessSuggestionsService = paperlessSuggestionsService,
            networkMonitor = networkMonitor,
            tokenManager = tokenManager,
            tagRepository = tagRepository,
            correspondentRepository = correspondentRepository,
            documentTypeRepository = documentTypeRepository,
            ocrTextExtractor = ocrTextExtractor
        )

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

/**
 * Recording fake for the [OcrTextExtractor] seam (#363): returns scripted [text] and
 * records invocations, so the premium-only OCR contract (#296) is pinned through state
 * assertions (`invocations == 0` for free users / WiFi-skip) instead of mock verification.
 */
private class RecordingOcrTextExtractor(var text: String = "") : OcrTextExtractor {
    var invocations = 0
        private set
    var lastBitmap: Bitmap? = null
        private set

    override suspend fun extractText(bitmap: Bitmap): String {
        invocations++
        lastBitmap = bitmap
        return text
    }
}
