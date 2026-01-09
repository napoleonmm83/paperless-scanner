package com.paperless.scanner.data.billing

/**
 * Enum representing Premium features that require a subscription.
 *
 * This defines which features are gated behind the Premium subscription
 * and allows for granular feature control and future expansion.
 */
enum class PremiumFeature {
    /**
     * AI-powered document analysis using Firebase AI (Gemini).
     * Includes:
     * - Automatic title extraction
     * - Tag suggestions based on document content
     * - Correspondent detection
     * - Document type classification
     * - Date extraction
     */
    AI_ANALYSIS,

    /**
     * Allow AI to suggest new tags that don't exist in Paperless yet.
     * Without this, AI can only suggest from existing tags.
     */
    AI_NEW_TAGS,

    /**
     * AI-powered document summary generation.
     * (Future feature - not yet implemented)
     */
    AI_SUMMARY;

    companion object {
        /**
         * Check if any AI-related features are requested.
         */
        fun PremiumFeature.isAiFeature(): Boolean {
            return this in listOf(AI_ANALYSIS, AI_NEW_TAGS, AI_SUMMARY)
        }
    }
}
