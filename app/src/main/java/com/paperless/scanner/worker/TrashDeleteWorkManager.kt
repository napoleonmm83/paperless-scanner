package com.paperless.scanner.worker

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages background trash deletion using WorkManager.
 *
 * This ensures that pending deletions complete even when:
 * - User navigates away from TrashScreen
 * - AppLock is triggered
 * - App is killed/backgrounded
 *
 * Works in conjunction with TrashViewModel which handles the UI countdown animation.
 */
@Singleton
class TrashDeleteWorkManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val workManager = WorkManager.getInstance(context)

    /**
     * Schedule a document for permanent deletion after the countdown delay.
     *
     * @param documentId The document ID to delete
     * @param delaySeconds The delay before deletion (countdown duration)
     */
    fun schedulePendingDelete(documentId: Int, delaySeconds: Long) {
        Log.d(TAG, "Scheduling pending delete for document $documentId in $delaySeconds seconds")

        val inputData = workDataOf(
            TrashDeleteWorker.KEY_DOCUMENT_ID to documentId
        )

        val deleteRequest = OneTimeWorkRequestBuilder<TrashDeleteWorker>()
            .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
            .setInputData(inputData)
            // Permanent deletion hits the server API, so require a network. (#134)
            .setConstraints(deleteConstraints())
            // Transient (5xx) delete failures are retried by the worker with this
            // exponential backoff; the worker caps attempts at MAX_DELETE_RETRIES. (#129)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()

        // Use REPLACE policy - if user taps delete again, restart the countdown
        workManager.enqueueUniqueWork(
            TrashDeleteWorker.workName(documentId),
            ExistingWorkPolicy.REPLACE,
            deleteRequest
        )

        Log.d(TAG, "Scheduled work ${deleteRequest.id} for document $documentId")
    }

    /**
     * Schedule a document for immediate deletion (countdown already expired).
     *
     * @param documentId The document ID to delete
     */
    fun scheduleImmediateDelete(documentId: Int) {
        Log.d(TAG, "Scheduling immediate delete for document $documentId")

        val inputData = workDataOf(
            TrashDeleteWorker.KEY_DOCUMENT_ID to documentId
        )

        val deleteRequest = OneTimeWorkRequestBuilder<TrashDeleteWorker>()
            .setInputData(inputData)
            // Permanent deletion hits the server API, so require a network. (#134)
            .setConstraints(deleteConstraints())
            // See schedulePendingDelete: exponential backoff for transient retries. (#129)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
            .build()

        // KEEP (not REPLACE): this is only called from restorePendingDeletes() on VM
        // recreation. A delete already in flight or in WorkManager backoff-retry carries
        // its runAttemptCount and backoff; REPLACE would cancel it and reset the count,
        // running the delete immediately (before the user can restore) and bypassing
        // MAX_DELETE_RETRIES on repeated recreations. KEEP preserves the existing work and
        // only enqueues fresh when none exists (e.g. after process death). (#129)
        workManager.enqueueUniqueWork(
            TrashDeleteWorker.workName(documentId),
            ExistingWorkPolicy.KEEP,
            deleteRequest
        )
    }

    /**
     * Cancel a pending deletion (user pressed undo).
     *
     * @param documentId The document ID to cancel deletion for
     */
    fun cancelPendingDelete(documentId: Int) {
        Log.d(TAG, "Cancelling pending delete for document $documentId")
        workManager.cancelUniqueWork(TrashDeleteWorker.workName(documentId))
    }

    /** Trash deletion is a server API call, so it requires a network connection. */
    private fun deleteConstraints(): Constraints =
        Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

    companion object {
        private const val TAG = "TrashDeleteWorkManager"

        /** Exponential-backoff base for transient (5xx) delete retries. (#129) */
        private const val BACKOFF_MINUTES = 15L
    }
}
