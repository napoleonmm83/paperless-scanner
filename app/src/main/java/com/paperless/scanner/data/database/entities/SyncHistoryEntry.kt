package com.paperless.scanner.data.database.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity for persistent sync history.
 *
 * Stores completed sync operations (uploads, trash deletions, restores, etc.)
 * with both user-friendly and technical error information.
 *
 * Used by SyncCenterScreen to display:
 * - Recently completed operations
 * - Failed operations with retry capability
 * - Detailed error messages
 */
@Entity(
    tableName = "sync_history",
    indices = [
        Index(value = ["createdAt"]),  // For sorting and cleanup queries
        Index(value = ["status"])       // For filtering by status
    ]
)
data class SyncHistoryEntry(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /**
     * Type of sync action performed.
     * Values: "upload", "delete_trash", "restore", "update_document", "sync"
     */
    val actionType: String,

    /**
     * Result status of the operation.
     * Values: "success", "failed", "partial"
     */
    val status: String,

    /**
     * User-friendly title describing the operation.
     * Examples: "Dokument hochgeladen", "Papierkorb geleert", "3 Dokumente gelöscht"
     */
    val title: String,

    /**
     * Additional details (filename, document title, etc.)
     * Example: "Rechnung_2024.pdf"
     */
    val details: String? = null,

    /**
     * JSON array of affected document IDs for batch operations.
     * Example: "[281, 282, 283]"
     */
    val affectedDocumentIds: String? = null,

    /**
     * User-friendly error message (for failed operations).
     * Example: "Datei zu groß (max 50MB)"
     */
    val userMessage: String? = null,

    /**
     * Technical error details (HTTP code, exception message).
     * Example: "HTTP 413 Request Entity Too Large"
     */
    val technicalError: String? = null,

    /**
     * Timestamp when the operation completed.
     */
    val createdAt: Long = System.currentTimeMillis(),

    /**
     * Number of documents affected (for batch operations).
     * Default: 1 for single document operations.
     */
    val documentCount: Int = 1
) {
    companion object {
        // Action types
        const val ACTION_UPLOAD = "upload"
        const val ACTION_DELETE_TRASH = "delete_trash"
        const val ACTION_RESTORE = "restore"
        const val ACTION_UPDATE_DOCUMENT = "update_document"
        const val ACTION_SYNC = "sync"

        // Status values
        const val STATUS_SUCCESS = "success"
        const val STATUS_FAILED = "failed"
        const val STATUS_PARTIAL = "partial"

        /**
         * Create a success entry for a single document operation.
         */
        fun success(
            actionType: String,
            title: String,
            details: String? = null,
            documentId: Int? = null
        ): SyncHistoryEntry = SyncHistoryEntry(
            actionType = actionType,
            status = STATUS_SUCCESS,
            title = title,
            details = details,
            affectedDocumentIds = documentId?.let { "[$it]" }
        )

        /**
         * Create a success entry for a batch operation.
         */
        fun successBatch(
            actionType: String,
            title: String,
            documentIds: List<Int>,
            details: String? = null
        ): SyncHistoryEntry = SyncHistoryEntry(
            actionType = actionType,
            status = STATUS_SUCCESS,
            title = title,
            details = details,
            affectedDocumentIds = documentIds.joinToString(prefix = "[", postfix = "]"),
            documentCount = documentIds.size
        )

        /**
         * Create a failure entry with user-friendly and technical error messages.
         */
        fun failure(
            actionType: String,
            title: String,
            userMessage: String,
            technicalError: String? = null,
            details: String? = null,
            documentId: Int? = null
        ): SyncHistoryEntry = SyncHistoryEntry(
            actionType = actionType,
            status = STATUS_FAILED,
            title = title,
            details = details,
            affectedDocumentIds = documentId?.let { "[$it]" },
            userMessage = userMessage,
            technicalError = technicalError
        )
    }
}
