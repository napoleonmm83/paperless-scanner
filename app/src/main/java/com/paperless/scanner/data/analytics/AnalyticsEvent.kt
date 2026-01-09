package com.paperless.scanner.data.analytics

/**
 * Type-safe analytics events for Firebase Analytics.
 * All events are anonymized and GDPR-compliant.
 */
sealed class AnalyticsEvent(
    val name: String,
    val params: Map<String, Any> = emptyMap()
) {
    // ==================== Authentication Events ====================

    /** User started login process */
    data object LoginStarted : AnalyticsEvent("login_started")

    /** Login successful */
    data class LoginSuccess(val method: String) : AnalyticsEvent(
        "login_success",
        mapOf("method" to method) // "password", "token", "biometric"
    )

    /** Login failed */
    data class LoginFailed(val errorType: String) : AnalyticsEvent(
        "login_failed",
        mapOf("error_type" to errorType)
    )

    /** User enabled biometric authentication */
    data object BiometricEnabled : AnalyticsEvent("biometric_enabled")

    /** User logged out */
    data object Logout : AnalyticsEvent("logout")

    // ==================== Upload Events ====================

    /** Upload started */
    data class UploadStarted(val pageCount: Int, val isMultiPage: Boolean) : AnalyticsEvent(
        "upload_started",
        mapOf(
            "page_count" to pageCount,
            "is_multi_page" to isMultiPage
        )
    )

    /** Upload completed successfully */
    data class UploadSuccess(val pageCount: Int, val durationMs: Long) : AnalyticsEvent(
        "upload_success",
        mapOf(
            "page_count" to pageCount,
            "duration_ms" to durationMs
        )
    )

    /** Upload failed */
    data class UploadFailed(val errorType: String) : AnalyticsEvent(
        "upload_failed",
        mapOf("error_type" to errorType)
    )

    /** Upload queued (offline mode) */
    data class UploadQueued(val isOffline: Boolean) : AnalyticsEvent(
        "upload_queued",
        mapOf("is_offline" to isOffline)
    )

    /** Upload retried from queue */
    data object UploadRetried : AnalyticsEvent("upload_retried")

    // ==================== Scan Events ====================

    /** Page added to scan */
    data class ScanPageAdded(val totalPages: Int) : AnalyticsEvent(
        "scan_page_added",
        mapOf("total_pages" to totalPages)
    )

    /** Scan completed */
    data class ScanCompleted(val pageCount: Int) : AnalyticsEvent(
        "scan_completed",
        mapOf("page_count" to pageCount)
    )

    /** Page rotated */
    data object ScanPageRotated : AnalyticsEvent("scan_page_rotated")

    /** Page removed */
    data object ScanPageRemoved : AnalyticsEvent("scan_page_removed")

    /** Pages reordered */
    data object ScanPagesReordered : AnalyticsEvent("scan_pages_reordered")

    // ==================== Document Events ====================

    /** Document viewed */
    data object DocumentViewed : AnalyticsEvent("document_viewed")

    /** Document search performed */
    data class DocumentSearched(val hasResults: Boolean) : AnalyticsEvent(
        "document_searched",
        mapOf("has_results" to hasResults)
    )

    /** Document filtered by tag */
    data object DocumentFiltered : AnalyticsEvent("document_filtered")

    /** Document deleted */
    data object DocumentDeleted : AnalyticsEvent("document_deleted")

    /** Document updated (metadata changed) */
    data object DocumentUpdated : AnalyticsEvent("document_updated")

    /** Document downloaded/shared */
    data object DocumentShared : AnalyticsEvent("document_shared")

    // ==================== Feature Usage Events ====================

    /** Batch import feature used */
    data class BatchImportUsed(val documentCount: Int) : AnalyticsEvent(
        "batch_import_used",
        mapOf("document_count" to documentCount)
    )

    /** Offline mode activated */
    data object OfflineModeUsed : AnalyticsEvent("offline_mode_used")

    /** PDF viewer opened */
    data object PdfViewerOpened : AnalyticsEvent("pdf_viewer_opened")

    /** Tag created */
    data object TagCreated : AnalyticsEvent("tag_created")

    /** Note added to document */
    data object NoteAdded : AnalyticsEvent("note_added")

    // ==================== App Lifecycle Events ====================

    /** App opened */
    data object AppOpened : AnalyticsEvent("app_opened")

    /** App returned from background */
    data object AppResumed : AnalyticsEvent("app_resumed")

    /** Network status changed */
    data class NetworkStatusChanged(val isOnline: Boolean) : AnalyticsEvent(
        "network_status_changed",
        mapOf("is_online" to isOnline)
    )

    // ==================== Settings Events ====================

    /** Analytics consent changed */
    data class AnalyticsConsentChanged(val granted: Boolean) : AnalyticsEvent(
        "analytics_consent_changed",
        mapOf("granted" to granted)
    )

    /** Upload quality setting changed */
    data class UploadQualityChanged(val quality: String) : AnalyticsEvent(
        "upload_quality_changed",
        mapOf("quality" to quality)
    )

    /** Notifications setting changed */
    data class NotificationsChanged(val enabled: Boolean) : AnalyticsEvent(
        "notifications_changed",
        mapOf("enabled" to enabled)
    )

    // ==================== AI Feature Events ====================

    /**
     * AI feature used for document analysis
     *
     * @param featureType Type of AI feature: "analyze_image", "analyze_pdf", "suggest_tags", "generate_title", "generate_summary"
     * @param inputTokens Number of input tokens sent to AI
     * @param outputTokens Number of output tokens received from AI
     * @param estimatedCostUsd Estimated cost in USD (calculated using Gemini Flash pricing)
     * @param subscriptionType User's subscription: "free", "monthly", "yearly"
     * @param success Whether the AI operation succeeded
     */
    data class AiFeatureUsed(
        val featureType: String,
        val inputTokens: Int,
        val outputTokens: Int,
        val estimatedCostUsd: Double,
        val subscriptionType: String,
        val success: Boolean = true
    ) : AnalyticsEvent(
        "ai_feature_used",
        mapOf(
            "feature_type" to featureType,
            "input_tokens" to inputTokens,
            "output_tokens" to outputTokens,
            "estimated_cost_usd" to estimatedCostUsd,
            "subscription_type" to subscriptionType,
            "success" to success
        )
    )

    /**
     * AI suggestion accepted by user
     *
     * @param featureType Type of AI feature that generated the suggestion
     * @param suggestionCount Number of suggestions accepted
     */
    data class AiSuggestionAccepted(
        val featureType: String,
        val suggestionCount: Int = 1
    ) : AnalyticsEvent(
        "ai_suggestion_accepted",
        mapOf(
            "feature_type" to featureType,
            "suggestion_count" to suggestionCount
        )
    )

    /**
     * AI suggestion rejected by user
     *
     * @param featureType Type of AI feature that generated the suggestion
     */
    data class AiSuggestionRejected(
        val featureType: String
    ) : AnalyticsEvent(
        "ai_suggestion_rejected",
        mapOf("feature_type" to featureType)
    )

    /**
     * User upgraded to Premium subscription
     *
     * @param plan Subscription plan: "monthly" or "yearly"
     * @param priceUsd Price in USD
     */
    data class PremiumSubscribed(
        val plan: String,
        val priceUsd: Double
    ) : AnalyticsEvent(
        "premium_subscribed",
        mapOf(
            "plan" to plan,
            "price_usd" to priceUsd
        )
    )

    /**
     * Premium upgrade prompt shown to user
     *
     * @param trigger What triggered the prompt: "ai_feature_locked", "settings", "upload_screen"
     */
    data class PremiumPromptShown(
        val trigger: String
    ) : AnalyticsEvent(
        "premium_prompt_shown",
        mapOf("trigger" to trigger)
    )

    /**
     * User dismissed Premium upgrade prompt
     *
     * @param trigger What triggered the prompt
     */
    data class PremiumPromptDismissed(
        val trigger: String
    ) : AnalyticsEvent(
        "premium_prompt_dismissed",
        mapOf("trigger" to trigger)
    )

    /**
     * AI usage limit warning shown
     *
     * @param currentCalls Current number of AI calls this month
     * @param limitType Type of limit: "soft_100", "soft_200", "hard_300"
     */
    data class AiUsageLimitWarning(
        val currentCalls: Int,
        val limitType: String
    ) : AnalyticsEvent(
        "ai_usage_limit_warning",
        mapOf(
            "current_calls" to currentCalls,
            "limit_type" to limitType
        )
    )

    /**
     * AI usage limit reached (hard block)
     *
     * @param monthlyCallCount Total calls this month
     */
    data class AiUsageLimitReached(
        val monthlyCallCount: Int
    ) : AnalyticsEvent(
        "ai_usage_limit_reached",
        mapOf("monthly_call_count" to monthlyCallCount)
    )
}
