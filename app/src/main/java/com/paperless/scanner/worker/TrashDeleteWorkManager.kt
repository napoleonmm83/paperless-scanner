package com.paperless.scanner.worker

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
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
            .build()

        workManager.enqueueUniqueWork(
            TrashDeleteWorker.workName(documentId),
            ExistingWorkPolicy.REPLACE,
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

    companion object {
        private const val TAG = "TrashDeleteWorkManager"
    }
}
