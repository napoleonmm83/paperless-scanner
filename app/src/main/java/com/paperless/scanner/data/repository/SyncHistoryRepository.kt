package com.paperless.scanner.data.repository

import com.paperless.scanner.data.database.dao.SyncHistoryDao
import com.paperless.scanner.data.database.entities.SyncHistoryEntry
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing sync history entries.
 *
 * Provides convenient methods for recording sync operations from Workers
 * and observing history from ViewModels.
 */
@Singleton
class SyncHistoryRepository @Inject constructor(
    private val syncHistoryDao: SyncHistoryDao
) {
    companion object {
        // Default retention period for history entries
        private const val DEFAULT_RETENTION_DAYS = 30
        private const val MILLIS_PER_DAY = 24 * 60 * 60 * 1000L
    }

    // ==================== Reactive Flows (for UI) ====================

    /**
     * Observe recent history entries.
     * Used by SyncCenterScreen.
     */
    fun observeRecentHistory(limit: Int = 50): Flow<List<SyncHistoryEntry>> =
        syncHistoryDao.observeRecent(limit)

    /**
     * Observe successful entries only.
     */
    fun observeSuccessful(limit: Int = 50): Flow<List<SyncHistoryEntry>> =
        syncHistoryDao.observeSuccessful(limit)

    /**
     * Observe failed entries only.
     * Used by SyncCenterScreen "Failed" section.
     */
    fun observeFailed(): Flow<List<SyncHistoryEntry>> =
        syncHistoryDao.observeFailed()

    /**
     * Observe count of failed entries.
     * Used for badge display.
     */
    fun observeFailedCount(): Flow<Int> =
        syncHistoryDao.observeFailedCount()

    // ==================== Recording Methods (for Workers) ====================

    /**
     * Record a successful sync operation.
     *
     * @param actionType Type of action (upload, delete_trash, restore, etc.)
     * @param title User-friendly title
     * @param details Additional details (filename, etc.)
     * @param documentId Optional document ID
     */
    suspend fun recordSuccess(
        actionType: String,
        title: String,
        details: String? = null,
        documentId: Int? = null
    ) {
        val entry = SyncHistoryEntry.success(
            actionType = actionType,
            title = title,
            details = details,
            documentId = documentId
        )
        syncHistoryDao.insert(entry)
    }

    /**
     * Record a successful batch operation.
     *
     * @param actionType Type of action
     * @param title User-friendly title
     * @param documentIds List of affected document IDs
     * @param details Additional details
     */
    suspend fun recordSuccessBatch(
        actionType: String,
        title: String,
        documentIds: List<Int>,
        details: String? = null
    ) {
        val entry = SyncHistoryEntry.successBatch(
            actionType = actionType,
            title = title,
            documentIds = documentIds,
            details = details
        )
        syncHistoryDao.insert(entry)
    }

    /**
     * Record a failed sync operation.
     *
     * @param actionType Type of action
     * @param title User-friendly title
     * @param userMessage User-friendly error message
     * @param technicalError Technical error details
     * @param details Additional details
     * @param documentId Optional document ID
     */
    suspend fun recordFailure(
        actionType: String,
        title: String,
        userMessage: String,
        technicalError: String? = null,
        details: String? = null,
        documentId: Int? = null
    ) {
        val entry = SyncHistoryEntry.failure(
            actionType = actionType,
            title = title,
            userMessage = userMessage,
            technicalError = technicalError,
            details = details,
            documentId = documentId
        )
        syncHistoryDao.insert(entry)
    }

    // ==================== Cleanup Methods ====================

    /**
     * Delete history entries older than retention period.
     * Should be called periodically (e.g., during app startup or via WorkManager).
     *
     * @param retentionDays Number of days to keep history (default 30)
     */
    suspend fun cleanupOldEntries(retentionDays: Int = DEFAULT_RETENTION_DAYS) {
        val cutoffTime = System.currentTimeMillis() - (retentionDays * MILLIS_PER_DAY)
        syncHistoryDao.deleteOlderThan(cutoffTime)
    }

    /**
     * Delete all failed entries.
     * Used by "Clear Failed" action in UI.
     */
    suspend fun clearFailed() {
        syncHistoryDao.deleteAllFailed()
    }

    /**
     * Delete all history entries.
     * Used by "Clear All History" action in UI.
     */
    suspend fun clearAll() {
        syncHistoryDao.deleteAll()
    }

    /**
     * Delete a single entry by ID.
     */
    suspend fun deleteEntry(id: Long) {
        syncHistoryDao.deleteById(id)
    }

    // ==================== Helper Methods ====================

    /**
     * Get user-friendly error message for common HTTP errors.
     */
    fun getUserFriendlyError(httpCode: Int?, exception: Exception? = null): String {
        return when (httpCode) {
            401 -> "Nicht autorisiert - bitte erneut anmelden"
            403 -> "Zugriff verweigert"
            404 -> "Dokument nicht gefunden"
            413 -> "Datei zu groß (max 50MB)"
            429 -> "Zu viele Anfragen - bitte warten"
            500, 502, 503, 504 -> "Serverfehler - bitte später erneut versuchen"
            else -> when {
                exception?.message?.contains("timeout", ignoreCase = true) == true ->
                    "Zeitüberschreitung - Server antwortet nicht"
                exception?.message?.contains("network", ignoreCase = true) == true ->
                    "Keine Internetverbindung"
                exception?.message?.contains("ssl", ignoreCase = true) == true ->
                    "Sicherheitsfehler - Zertifikat ungültig"
                else -> exception?.message ?: "Unbekannter Fehler"
            }
        }
    }

    /**
     * Get technical error description.
     */
    fun getTechnicalError(httpCode: Int?, message: String?, exception: Exception?): String {
        return buildString {
            if (httpCode != null) {
                append("HTTP $httpCode")
                if (message != null) {
                    append(": $message")
                }
            }
            if (exception != null) {
                if (isNotEmpty()) append("\n")
                append(exception::class.simpleName)
                exception.message?.let { append(": $it") }
            }
        }
    }
}
