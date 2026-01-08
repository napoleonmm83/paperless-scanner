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
}
