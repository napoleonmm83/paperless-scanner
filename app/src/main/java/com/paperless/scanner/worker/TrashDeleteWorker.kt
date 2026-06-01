package com.paperless.scanner.worker

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.paperless.scanner.R
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.api.isRetryable
import com.paperless.scanner.data.database.dao.CachedDocumentDao
import com.paperless.scanner.data.database.entities.SyncHistoryEntry
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.data.repository.TrashRepository
import com.paperless.scanner.data.repository.SyncHistoryRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import com.paperless.scanner.data.analytics.CrashlyticsHelper
import kotlinx.coroutines.CancellationException

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
    private val trashRepository: TrashRepository,
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

        // Defense-in-depth: if the document was restored (isDeleted=0) since the delete
        // was scheduled, never permanently delete it. getDocument returns only
        // non-deleted rows, so a non-null result means it was restored. The restore flow
        // normally clears the entry; this is the backstop. (#129)
        val restoredDoc = try {
            cachedDocumentDao.getDocument(documentId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Fail closed: if we can't verify the doc wasn't restored, do NOT proceed with
            // a permanent delete. Retry and keep the entry so a transient DB read error
            // can't cause the exact wrong delete this guard prevents. (#129, CodeRabbit)
            Log.w(TAG, "Failed to verify restored state for $documentId, retrying", e)
            return Result.retry()
        }
        if (restoredDoc != null) {
            Log.d(TAG, "Document $documentId was restored, skipping permanent delete")
            tokenManager.removePendingTrashDelete(documentId)
            return Result.success()
        }

        // Title for the history entry. getDocument filters isDeleted=0, so a doc still in
        // trash returns null → the document-number fallback is used.
        val documentTitle = applicationContext.getString(R.string.document_number, documentId)

        // The pending-delete entry is intentionally NOT removed before the attempt: it
        // must survive a failed attempt so a 5xx can be retried (and so a restore can
        // clear it before the retry fires). Cleared on success or terminal failure. (#129)

        // Perform the actual deletion
        return trashRepository.permanentlyDeleteDocument(documentId)
            .fold(
                onSuccess = {
                    Log.d(TAG, "Successfully deleted document $documentId")
                    crashlyticsHelper.logActionBreadcrumb("WORKER_TRASH", "success, document $documentId")

                    // The delete is committed on the server → drop the pending entry. (#129)
                    tokenManager.removePendingTrashDelete(documentId)

                    // Record success in SyncHistory
                    try {
                        syncHistoryRepository.recordSuccess(
                            actionType = SyncHistoryEntry.ACTION_DELETE_TRASH,
                            title = applicationContext.getString(R.string.sync_document_deleted),
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
                            is PaperlessException.ClientError -> error.code
                            is PaperlessException.ServerError -> error.code
                            is PaperlessException.AuthError -> error.code
                            else -> null
                        }
                        syncHistoryRepository.recordFailure(
                            actionType = SyncHistoryEntry.ACTION_DELETE_TRASH,
                            title = applicationContext.getString(R.string.sync_deletion_failed),
                            userMessage = syncHistoryRepository.getUserFriendlyError(applicationContext, httpCode, error as? Exception),
                            technicalError = syncHistoryRepository.getTechnicalError(httpCode, error.message, error as? Exception),
                            details = documentTitle,
                            documentId = documentId
                        )
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to record sync history: ${e.message}")
                    }

                    val retryable = (error as? PaperlessException)?.isRetryable == true
                    if (retryable && runAttemptCount < MAX_DELETE_RETRIES) {
                        // Transient (5xx) → retry with exponential backoff. Keep the pending
                        // entry so the retry re-checks it (and a restore can clear it
                        // meanwhile). (#129)
                        Log.w(TAG, "Transient delete failure for $documentId (attempt $runAttemptCount), retrying")
                        Result.retry()
                    } else {
                        // Permanent (4xx) or retries exhausted → terminal. Clear the entry so
                        // restorePendingDeletes() doesn't reschedule it forever. (#129)
                        tokenManager.removePendingTrashDelete(documentId)
                        Result.failure()
                    }
                }
            )
    }

    companion object {
        const val TAG = "TrashDeleteWorker"
        const val KEY_DOCUMENT_ID = "document_id"

        /**
         * Max WorkManager run attempts for a transient (5xx) delete failure before
         * giving up terminally. Bounds the exponential backoff so a permanently-5xx
         * server can't reschedule the delete forever. (#129)
         */
        const val MAX_DELETE_RETRIES = 3

        /**
         * Generate unique work name for a specific document.
         * This allows cancelling individual delete operations.
         */
        fun workName(documentId: Int) = "trash_delete_$documentId"
    }
}
