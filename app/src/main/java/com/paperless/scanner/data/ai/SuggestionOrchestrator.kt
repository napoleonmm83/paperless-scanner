package com.paperless.scanner.data.ai

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import com.paperless.scanner.data.ai.models.DocumentAnalysis
import com.paperless.scanner.data.ai.models.SuggestionResult
import com.paperless.scanner.data.ai.models.SuggestionSource
import com.paperless.scanner.data.ai.models.TagSuggestion
import com.paperless.scanner.data.billing.PremiumFeatureManager
import com.paperless.scanner.data.billing.PremiumFeature
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.data.repository.CorrespondentRepository
import com.paperless.scanner.data.repository.DocumentTypeRepository
import com.paperless.scanner.data.repository.TagRepository
import com.paperless.scanner.domain.model.Tag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Central orchestrator for all suggestion sources.
 *
 * Implements a fallback chain:
 * 1. Premium active? → Firebase AI Analysis
 *    - Success → Return AI Suggestions
 *    - Error → Continue to step 2
 * 2. Online + documentId available? → Paperless Suggestions API
 *    - Success → Return Server Suggestions
 *    - Error → Continue to step 3
 * 3. ALWAYS → Local Tag Matching
 *    - Return Local Suggestions
 *
 * Suggestions from multiple sources are merged and deduplicated.
 * AI suggestions have highest priority, followed by Paperless API, then local matching.
 */
@Singleton
class SuggestionOrchestrator @Inject constructor(
    private val premiumFeatureManager: PremiumFeatureManager,
    private val aiAnalysisService: AiAnalysisService,
    private val tagMatchingEngine: TagMatchingEngine,
    private val paperlessSuggestionsService: PaperlessSuggestionsService,
    private val networkMonitor: NetworkMonitor,
    private val tagRepository: TagRepository,
    private val correspondentRepository: CorrespondentRepository,
    private val documentTypeRepository: DocumentTypeRepository,
    private val tokenManager: com.paperless.scanner.data.datastore.TokenManager
) {
    /**
     * Get suggestions for a document using the fallback chain.
     *
     * @param bitmap Optional bitmap for AI analysis (Premium feature)
     * @param imageUri Optional image URI for AI analysis (alternative to bitmap)
     * @param extractedText Optional text for local matching
     * @param documentId Optional document ID for Paperless API suggestions
     * @param overrideWifiOnly Optional override for WiFi-only setting (for "Use anyway" button)
     * @return SuggestionResult with merged suggestions and source info
     */
    suspend fun getSuggestions(
        bitmap: Bitmap? = null,
        imageUri: Uri? = null,
        extractedText: String? = null,
        documentId: Int? = null,
        overrideWifiOnly: Boolean = false
    ): SuggestionResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Starting suggestion orchestration")

        val availableTags = tagRepository.observeTags().first()
        val availableCorrespondents = correspondentRepository.observeCorrespondents().first()
        val availableDocumentTypes = documentTypeRepository.observeDocumentTypes().first()

        // Read AI settings
        val aiWifiOnly = tokenManager.aiWifiOnly.first()
        val aiNewTagsEnabled = tokenManager.aiNewTagsEnabled.first()

        var primarySource: SuggestionSource = SuggestionSource.LOCAL_MATCHING
        var aiAnalysis: DocumentAnalysis? = null
        var paperlessAnalysis: DocumentAnalysis? = null
        var localSuggestions: List<TagSuggestion> = emptyList()
        var aiError: Throwable? = null
        var aiAttempted = false

        // Step 1: Try Firebase AI (Premium only)
        if (premiumFeatureManager.isFeatureAvailable(PremiumFeature.AI_ANALYSIS)) {
            Log.d(TAG, "Premium active - checking WiFi requirements")

            // Check WiFi requirement
            if (aiWifiOnly && !overrideWifiOnly) {
                val isWifiConnected = networkMonitor.isWifiConnectedSync()
                if (!isWifiConnected) {
                    Log.d(TAG, "WiFi-only mode active, but not connected to WiFi - skipping AI")
                    // Don't attempt AI, but don't treat it as error - return WiFiRequired
                    // Continue to Paperless API / Local matching as fallback
                    if (bitmap != null || imageUri != null) {
                        // User tried to use AI, but WiFi is required
                        // Return WiFiRequired ONLY if no other source can provide suggestions
                        // For now, let's continue with fallback and check at the end
                        aiAttempted = true
                        Log.d(TAG, "WiFi required - will return WiFiRequired if no other suggestions available")
                    }
                } else {
                    Log.d(TAG, "WiFi connected - proceeding with AI analysis")
                    aiAttempted = true

                    val aiResult = when {
                        bitmap != null -> aiAnalysisService.analyzeImage(
                            bitmap = bitmap,
                            availableTags = availableTags,
                            availableCorrespondents = availableCorrespondents.map { it.name },
                            availableDocumentTypes = availableDocumentTypes.map { it.name },
                            allowNewTags = aiNewTagsEnabled
                        )
                        imageUri != null -> aiAnalysisService.analyzeImageUri(
                            uri = imageUri,
                            availableTags = availableTags,
                            availableCorrespondents = availableCorrespondents.map { it.name },
                            availableDocumentTypes = availableDocumentTypes.map { it.name },
                            allowNewTags = aiNewTagsEnabled
                        )
                        else -> null
                    }

                    if (aiResult != null) {
                        aiResult.onSuccess { analysis ->
                            Log.d(TAG, "Firebase AI analysis successful: ${analysis.suggestedTags.size} tags")
                            aiAnalysis = analysis
                            primarySource = SuggestionSource.FIREBASE_AI
                        }.onFailure { e ->
                            Log.e(TAG, "Firebase AI analysis failed: ${e.message}", e)
                            aiError = e
                        }
                    }
                }
            } else {
                // WiFi-only disabled or overridden - proceed with AI
                Log.d(TAG, "WiFi-only disabled or overridden - proceeding with AI analysis")
                aiAttempted = true

                val aiResult = when {
                    bitmap != null -> aiAnalysisService.analyzeImage(
                        bitmap = bitmap,
                        availableTags = availableTags,
                        availableCorrespondents = availableCorrespondents.map { it.name },
                        availableDocumentTypes = availableDocumentTypes.map { it.name },
                        allowNewTags = aiNewTagsEnabled
                    )
                    imageUri != null -> aiAnalysisService.analyzeImageUri(
                        uri = imageUri,
                        availableTags = availableTags,
                        availableCorrespondents = availableCorrespondents.map { it.name },
                        availableDocumentTypes = availableDocumentTypes.map { it.name },
                        allowNewTags = aiNewTagsEnabled
                    )
                    else -> null
                }

                if (aiResult != null) {
                    aiResult.onSuccess { analysis ->
                        Log.d(TAG, "Firebase AI analysis successful: ${analysis.suggestedTags.size} tags")
                        aiAnalysis = analysis
                        primarySource = SuggestionSource.FIREBASE_AI
                    }.onFailure { e ->
                        Log.e(TAG, "Firebase AI analysis failed: ${e.message}", e)
                        aiError = e
                    }
                }
            }
        } else {
            Log.d(TAG, "Premium not active - skipping Firebase AI")
        }

        // Step 2: Try Paperless API (if online and documentId available)
        if (aiAnalysis == null && networkMonitor.checkOnlineStatus() && documentId != null) {
            Log.d(TAG, "Attempting Paperless API suggestions for document $documentId")

            paperlessSuggestionsService.getSuggestions(documentId)
                .onSuccess { analysis ->
                    Log.d(TAG, "Paperless API suggestions successful: ${analysis.suggestedTags.size} tags")
                    paperlessAnalysis = analysis
                    if (primarySource != SuggestionSource.FIREBASE_AI) {
                        primarySource = SuggestionSource.PAPERLESS_API
                    }
                }.onFailure { e ->
                    Log.w(TAG, "Paperless API suggestions failed, continuing to local matching", e)
                }
        }

        // Step 3: Local matching only as FALLBACK when no AI/Paperless suggestions
        // Skip local matching when AI analysis succeeded (AI is fully comprehensive)
        if (aiAnalysis == null && extractedText != null && extractedText.isNotBlank()) {
            Log.d(TAG, "No AI analysis - running local tag matching as fallback")
            localSuggestions = tagMatchingEngine.findMatchingTags(extractedText, availableTags)
            Log.d(TAG, "Local matching found ${localSuggestions.size} suggestions")

            if (paperlessAnalysis == null) {
                primarySource = SuggestionSource.LOCAL_MATCHING
            }
        } else if (aiAnalysis != null) {
            Log.d(TAG, "AI analysis available - skipping local matching (AI is primary source)")
        }

        // Merge all suggestions
        val mergedAnalysis = mergeAnalysisResults(
            aiAnalysis = aiAnalysis,
            paperlessAnalysis = paperlessAnalysis,
            localSuggestions = localSuggestions
        )

        Log.d(TAG, "Orchestration complete: ${mergedAnalysis.suggestedTags.size} total suggestions, source: $primarySource")

        // Check if WiFi was required but not available
        if (aiWifiOnly && !overrideWifiOnly && !networkMonitor.isWifiConnectedSync() &&
            aiAttempted && aiAnalysis == null && aiError == null) {
            // WiFi was required, user tried to use AI (bitmap/imageUri provided), but WiFi not available
            // Return WiFiRequired to show banner with "Use anyway" option
            Log.d(TAG, "Returning WiFiRequired - AI skipped due to WiFi-only setting")
            return@withContext SuggestionResult.WiFiRequired
        }

        // If AI was attempted but failed, and no other source provided suggestions, return error
        if (mergedAnalysis.suggestedTags.isEmpty() && aiAttempted && aiError != null) {
            val errorMessage = when {
                aiError?.message?.contains("PERMISSION_DENIED") == true ->
                    "Firebase AI not configured. Please enable Vertex AI in Firebase Console."
                aiError?.message?.contains("timeout") == true ->
                    "AI analysis timeout. Please try again."
                aiError?.message?.contains("quota") == true ->
                    "AI quota exhausted. Please try again later."
                else ->
                    "AI analysis failed: ${aiError?.message ?: "Unknown error"}"
            }
            Log.e(TAG, "All suggestion sources failed. AI error: ${aiError?.message}")
            return@withContext SuggestionResult.Error(errorMessage, aiError)
        }

        SuggestionResult.Success(
            analysis = mergedAnalysis,
            source = primarySource
        )
    }

    /**
     * Get suggestions for a document that's already uploaded.
     * Uses Paperless API suggestions as primary, with local matching as fallback.
     *
     * @param documentId The Paperless document ID
     * @param documentContent Optional document content for local matching enhancement
     * @return SuggestionResult with suggestions
     */
    suspend fun getSuggestionsForUploadedDocument(
        documentId: Int,
        documentContent: String? = null
    ): SuggestionResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Getting suggestions for uploaded document $documentId")

        val availableTags = tagRepository.observeTags().first()

        var paperlessAnalysis: DocumentAnalysis? = null
        var localSuggestions: List<TagSuggestion> = emptyList()
        var primarySource = SuggestionSource.LOCAL_MATCHING

        // Try Paperless API first
        if (networkMonitor.checkOnlineStatus()) {
            paperlessSuggestionsService.getSuggestions(documentId)
                .onSuccess { analysis ->
                    paperlessAnalysis = analysis
                    primarySource = SuggestionSource.PAPERLESS_API
                }.onFailure { e ->
                    Log.w(TAG, "Paperless suggestions failed for document $documentId", e)
                }
        }

        // Enhance with local matching if content available
        if (documentContent != null && documentContent.isNotBlank()) {
            localSuggestions = tagMatchingEngine.findMatchingTags(documentContent, availableTags)
        }

        val mergedAnalysis = mergeAnalysisResults(
            aiAnalysis = null,
            paperlessAnalysis = paperlessAnalysis,
            localSuggestions = localSuggestions
        )

        SuggestionResult.Success(
            analysis = mergedAnalysis,
            source = primarySource
        )
    }

    /**
     * Merge analysis results from multiple sources.
     * Priority: AI > Paperless API > Local Matching
     * Deduplicates by tag ID.
     */
    private fun mergeAnalysisResults(
        aiAnalysis: DocumentAnalysis?,
        paperlessAnalysis: DocumentAnalysis?,
        localSuggestions: List<TagSuggestion>
    ): DocumentAnalysis {
        // Collect all tag suggestions, maintaining priority order
        val allSuggestions = mutableListOf<TagSuggestion>()

        // AI suggestions first (highest priority)
        aiAnalysis?.suggestedTags?.let { allSuggestions.addAll(it) }

        // Paperless API suggestions second
        paperlessAnalysis?.suggestedTags?.let { allSuggestions.addAll(it) }

        // Local suggestions last
        allSuggestions.addAll(localSuggestions)

        // Deduplicate by tag ID, keeping highest confidence version
        val deduplicatedTags = allSuggestions
            .groupBy { it.tagId ?: it.tagName.hashCode() }
            .map { (_, suggestions) ->
                suggestions.maxByOrNull { it.confidence } ?: suggestions.first()
            }
            .sortedByDescending { it.confidence }
            .take(MAX_SUGGESTIONS)

        // Use AI analysis as base if available, otherwise Paperless, otherwise create new
        return when {
            aiAnalysis != null -> aiAnalysis.copy(suggestedTags = deduplicatedTags)
            paperlessAnalysis != null -> paperlessAnalysis.copy(suggestedTags = deduplicatedTags)
            else -> DocumentAnalysis(suggestedTags = deduplicatedTags)
        }
    }

    companion object {
        private const val TAG = "SuggestionOrchestrator"
        private const val MAX_SUGGESTIONS = 10
    }
}
