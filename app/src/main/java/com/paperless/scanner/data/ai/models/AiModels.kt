package com.paperless.scanner.data.ai.models

import android.content.Context
import androidx.annotation.StringRes
import com.paperless.scanner.R

/**
 * Result of AI document analysis.
 */
data class DocumentAnalysis(
    val suggestedTitle: String? = null,
    val suggestedTags: List<TagSuggestion> = emptyList(),
    val suggestedCorrespondent: String? = null,
    val suggestedDocumentType: String? = null,
    val extractedText: String? = null,
    val suggestedDate: String? = null,
    val confidence: Float = 0f
)

/**
 * A tag suggestion with confidence score and source.
 */
data class TagSuggestion(
    val tagId: Int? = null,
    val tagName: String,
    val confidence: Float,
    val reason: String
) {
    companion object {
        const val REASON_AI_DETECTED = "AI detected"
        const val REASON_KEYWORD_MATCH = "Keyword match"
        const val REASON_FUZZY_MATCH = "Fuzzy match"
        const val REASON_PAPERLESS_API = "Paperless suggestion"
    }
}

/**
 * Source of the suggestion for UI display.
 */
enum class SuggestionSource {
    FIREBASE_AI,
    PAPERLESS_API,
    LOCAL_MATCHING
}

/**
 * Result wrapper for suggestion operations.
 */
sealed class SuggestionResult {
    data class Success(
        val analysis: DocumentAnalysis,
        val source: SuggestionSource
    ) : SuggestionResult()

    data class Error(
        val error: SuggestionError,
        val exception: Throwable? = null
    ) : SuggestionResult()

    /**
     * WiFi is required for AI suggestions, but device is not connected to WiFi.
     * UI should show banner with "Use anyway" option.
     */
    data object WiFiRequired : SuggestionResult()

    data object Loading : SuggestionResult()
}

/**
 * Typed causes for failed suggestion operations (#364).
 *
 * The data layer classifies failures into these codes; the UI layer resolves them
 * to localized text via [getLocalizedMessage]. Raw SDK/exception messages must
 * never reach the UI — keep them in logs only (see LogSanitizer conventions).
 */
enum class SuggestionError(@StringRes val messageResId: Int) {
    /** Device has no internet connection — AI analysis is unavailable. */
    OFFLINE(R.string.suggestion_error_offline),

    /** The AI call timed out. */
    TIMEOUT(R.string.suggestion_error_timeout),

    /** The AI quota is exhausted. */
    QUOTA_EXHAUSTED(R.string.suggestion_error_quota),

    /** The AI backend rejected the call (e.g. Vertex AI not enabled). Detail belongs in logs. */
    NOT_CONFIGURED(R.string.suggestion_error_not_configured),

    /** The document image could not be decoded/read for analysis. */
    DOCUMENT_READ_FAILED(R.string.error_analyze_document),

    /** Any other failure. */
    UNKNOWN(R.string.suggestion_error_unknown)
}

/**
 * Resolves the localized user-facing message for this error.
 * Mirrors [com.paperless.scanner.domain.error.getLocalizedMessage] for PaperlessException.
 */
fun SuggestionError.getLocalizedMessage(context: Context): String =
    context.getString(messageResId)
