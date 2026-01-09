package com.paperless.scanner.data.ai.models

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
        val message: String,
        val exception: Throwable? = null
    ) : SuggestionResult()

    data object Loading : SuggestionResult()
}
