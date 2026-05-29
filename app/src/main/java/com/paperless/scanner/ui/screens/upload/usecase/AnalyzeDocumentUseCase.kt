package com.paperless.scanner.ui.screens.upload.usecase

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.paperless.scanner.R
import com.paperless.scanner.data.ai.SuggestionOrchestrator
import com.paperless.scanner.data.ai.models.SuggestionResult
import com.paperless.scanner.data.analytics.AnalyticsService
import com.paperless.scanner.data.billing.PremiumFeature
import com.paperless.scanner.data.billing.PremiumFeatureManager
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.data.repository.AiUsageRepository
import com.paperless.scanner.data.repository.UsageLimitStatus
import com.paperless.scanner.util.CoroutineDispatchers
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Owns the pre-upload AI analysis extracted from `UploadViewModel` (issue #42):
 * image decoding + suggestion orchestration, usage-limit tracking, premium gating and the
 * AI-new-tags preference. The ViewModel keeps the AnalysisState machine and maps these results.
 */
class AnalyzeDocumentUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val suggestionOrchestrator: SuggestionOrchestrator,
    private val aiUsageRepository: AiUsageRepository,
    private val premiumFeatureManager: PremiumFeatureManager,
    private val analyticsService: AnalyticsService,
    private val tokenManager: TokenManager,
    private val dispatchers: CoroutineDispatchers,
) {
    /** Premium status for the AI Tagging PRO badge. */
    val isPremiumAccessEnabled: Flow<Boolean> = premiumFeatureManager.isPremiumAccessEnabled

    /** User preference: allow AI to propose brand-new tags. */
    val aiNewTagsEnabled: Flow<Boolean> = tokenManager.aiNewTagsEnabled

    /** Localized fallback message for analysis failures (decode / usage / logging). */
    val analysisErrorMessage: String
        get() = context.getString(R.string.error_analyze_document)

    /**
     * Whether AI suggestions are available (Debug build or Premium subscription).
     * In release builds without Premium, suggestions are only available AFTER upload
     * via the Paperless API in DocumentDetailScreen.
     */
    fun isAiAvailable(): Boolean = premiumFeatureManager.isFeatureAvailable(PremiumFeature.AI_ANALYSIS)

    fun observeCurrentMonthCallCount(): Flow<Int> = aiUsageRepository.observeCurrentMonthCallCount()

    suspend fun checkUsageLimit(): UsageLimitStatus = aiUsageRepository.checkUsageLimit()

    suspend fun setAiNewTagsEnabled(enabled: Boolean) {
        tokenManager.setAiNewTagsEnabled(enabled)
    }

    /**
     * Decodes the image and runs the centralized suggestion fallback chain (AI → Paperless → Local).
     * Returns a [SuggestionResult]; decode/IO failures are mapped to [SuggestionResult.Error].
     */
    suspend fun analyze(uri: Uri, overrideWifiOnly: Boolean): SuggestionResult = withContext(dispatchers.io) {
        try {
            // Decode bitmap for AI/local analysis
            val bitmap = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it)
            }

            if (bitmap == null) {
                Log.w(TAG, "Could not decode image for analysis")
                return@withContext SuggestionResult.Error(context.getString(R.string.error_analyze_document))
            }

            // Use SuggestionOrchestrator for centralized suggestion logic
            // Handles Premium check, fallback chain (AI → Paperless → Local), and merging
            suggestionOrchestrator.getSuggestions(
                bitmap = bitmap,
                extractedText = "", // TODO: Add OCR text extraction in future
                documentId = null, // Not applicable for pre-upload analysis
                overrideWifiOnly = overrideWifiOnly
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Document analysis failed", e)
            SuggestionResult.Error(e.message ?: context.getString(R.string.error_analyze_document))
        }
    }

    /** Logs a Firebase-AI document-analysis call to usage tracking + analytics. */
    suspend fun logFirebaseUsage() {
        withContext(dispatchers.io) {
            val estimatedInputTokens = 1000  // ~1 image
            val estimatedOutputTokens = 200  // ~200 tokens for suggestions

            aiUsageRepository.logUsage(
                featureType = "document_analysis",
                inputTokens = estimatedInputTokens,
                outputTokens = estimatedOutputTokens,
                success = true,
                subscriptionType = "free" // TODO: Update when premium implemented
            )

            analyticsService.trackAiFeatureUsage(
                featureType = "document_analysis",
                inputTokens = estimatedInputTokens,
                outputTokens = estimatedOutputTokens,
                subscriptionType = "free"
            )
        }
    }

    companion object {
        private const val TAG = "AnalyzeDocumentUseCase"
    }
}
