package com.paperless.scanner.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.paperless.scanner.data.database.dao.CachedDocumentDao
import com.paperless.scanner.data.database.entities.SyncHistoryEntry
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.data.repository.DocumentRepository
import com.paperless.scanner.data.repository.SyncHistoryRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import com.paperless.scanner.data.analytics.CrashlyticsHelper

/**
 * Background Worker for permanent trash document deletion.
 *
 * This worker runs independently of the ViewModel lifecycle, ensuring that
 * pending deletions complete even when:
 * - User navigates away from TrashScreen
 * - AppLock is triggered
 * - App is killed/backgrounded
 *
 * The worker is scheduled with an initial delay (countdown duration).
 * When it runs, it checks if the delete is still pending (user may have cancelled).
 *
 * Records sync history entries for SyncCenter feature.
 */
@HiltWorker
class TrashDeleteWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val documentRepository: DocumentRepository,
    private val tokenManager: TokenManager,
    private val syncHistoryRepository: SyncHistoryRepository,
    private val cachedDocumentDao: CachedDocumentDao,
    private val crashlyticsHelper: CrashlyticsHelper
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val documentId = inputData.getInt(KEY_DOCUMENT_ID, -1)
        if (documentId == -1) {
            Log.e(TAG, "No document ID provided")
            crashlyticsHelper.logStateBreadcrumb("WORKER_TRASH_ERROR", "no document ID")
            return Result.failure()
        }

        crashlyticsHelper.logActionBreadcrumb("WORKER_TRASH", "delete document $documentId")
        Log.d(TAG, "Processing pending delete for document $documentId")

        // Check if this delete is still pending (user may have cancelled via undo)
        val pendingDeletes = tokenManager.getPendingTrashDeletesSync()
        val isStillPending = pendingDeletes?.split(",")?.any { entry ->
            entry.split(":").firstOrNull()?.toIntOrNull() == documentId
        } ?: false

        if (!isStillPending) {
            Log.d(TAG, "Document $documentId delete was cancelled, skipping")
            return Result.success()
        }

        // Get document title BEFORE deletion for history entry
        val documentTitle = try {
            cachedDocumentDao.getDocument(documentId)?.title ?: "Dokument #$documentId"
        } catch (e: Exception) {
            "Dokument #$documentId"
        }

        // Remove from pending deletes BEFORE actual deletion
        removePendingDelete(documentId)

        // Perform the actual deletion
        return documentRepository.permanentlyDeleteDocument(documentId)
            .fold(
                onSuccess = {
                    Log.d(TAG, "Successfully deleted document $documentId")
                    crashlyticsHelper.logActionBreadcrumb("WORKER_TRASH", "success, document $documentId")

                    // Record success in SyncHistory
                    try {
                        syncHistoryRepository.recordSuccess(
                            actionType = SyncHistoryEntry.ACTION_DELETE_TRASH,
                            title = "Dokument endgültig gelöscht",
                            details = documentTitle,
                            documentId = documentId
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to record sync history: ${e.message}")
                    }

                    Result.success()
                },
                onFailure = { error ->
                    Log.e(TAG, "Failed to delete document $documentId: ${error.message}")
                    crashlyticsHelper.logStateBreadcrumb("WORKER_TRASH_ERROR", "document $documentId: ${error.message}")

                    // Record failure in SyncHistory with user-friendly and technical messages
                    try {
                        // Extract HTTP code from PaperlessException subtypes
                        val httpCode = when (error) {
                            is com.paperless.scanner.data.api.PaperlessException.ClientError -> error.code
                            is com.paperless.scanner.data.api.PaperlessException.ServerError -> error.code
                            is com.paperless.scanner.data.api.PaperlessException.AuthError -> error.code
                            else -> null
                        }
                        syncHistoryRepository.recordFailure(
                            actionType = SyncHistoryEntry.ACTION_DELETE_TRASH,
                            title = "Löschen fehlgeschlagen",
                            userMessage = syncHistoryRepository.getUserFriendlyError(httpCode, error as? Exception),
                            technicalError = syncHistoryRepository.getTechnicalError(httpCode, error.message, error as? Exception),
                            details = documentTitle,
                            documentId = documentId
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to record sync history: ${e.message}")
                    }

                    // Don't retry - the document might not exist anymore or other permanent error
                    Result.failure()
                }
            )
    }

    /**
     * Remove a document from the pending deletes in DataStore.
     */
    private suspend fun removePendingDelete(documentId: Int) {
        val pendingDeletes = tokenManager.getPendingTrashDeletesSync() ?: return
        val updatedDeletes = pendingDeletes.split(",")
            .filter { entry ->
                entry.split(":").firstOrNull()?.toIntOrNull() != documentId
            }
            .joinToString(",")

        if (updatedDeletes.isEmpty()) {
            tokenManager.clearPendingTrashDeletes()
        } else {
            tokenManager.savePendingTrashDeletes(updatedDeletes)
        }
    }

    companion object {
        const val TAG = "TrashDeleteWorker"
        const val KEY_DOCUMENT_ID = "document_id"

        /**
         * Generate unique work name for a specific document.
         * This allows cancelling individual delete operations.
         */
        fun workName(documentId: Int) = "trash_delete_$documentId"
    }
}
