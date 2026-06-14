package com.paperless.scanner.worker

import android.content.Context
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UploadWorkManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val constraintsProvider: UploadConstraintsProvider
) {
    private val workManager = WorkManager.getInstance(context)

    /**
     * Schedule an immediate drain of the pending-upload queue.
     *
     * Constraints come from [UploadConstraintsProvider] so the network requirement
     * (any connection vs. unmetered-only) and the battery/storage deferral (#134) stay
     * identical across every upload-queue trigger. The work stays enqueued and runs as
     * soon as the constraints clear (mirrors SyncWorker).
     *
     * Uses [ExistingWorkPolicy.APPEND_OR_REPLACE] (#130): a second call while a
     * drain is already in flight appends a follow-up worker behind the running
     * one instead of REPLACE-cancelling it. That avoids both failure modes —
     * REPLACE drops queue rows the running worker had begun draining, while KEEP
     * would strand rows enqueued after the worker's start-time maxIterations cap
     * with no follow-up work. The appended worker returns Result.success()
     * immediately if the predecessor already drained the queue. _OR_REPLACE
     * guards against a failed/cancelled predecessor leaving the appended work
     * permanently blocked.
     */
    fun scheduleImmediateUpload() {
        Log.d(TAG, "scheduleImmediateUpload() called")
        val uploadRequest = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraintsProvider.build())
            .build()

        Log.d(TAG, "Enqueuing work with APPEND_OR_REPLACE policy, workId: ${uploadRequest.id}")
        workManager.enqueueUniqueWork(
            UploadWorker.WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            uploadRequest
        )
        Log.d(TAG, "Work enqueued successfully")
    }

    /**
     * Re-applies the current upload constraints to the pending-upload queue after the user
     * changes the "upload only on unmetered networks" preference.
     *
     * WorkManager bakes constraints into a WorkRequest at enqueue time and never re-reads
     * them, so toggling the preference would otherwise leave already-enqueued work on its old
     * constraint: disabling the setting would leave uploads that were queued (and held) under
     * [androidx.work.NetworkType.UNMETERED] stuck, and enabling it would not re-hold pending
     * work. [ExistingWorkPolicy.REPLACE] swaps the constraint; the persistent upload queue
     * (DB-backed) is untouched and a cancelled in-flight drain resets its row safely (#128).
     *
     * REPLACE is safe here — unlike [scheduleImmediateUpload] this fires only on a deliberate,
     * infrequent preference change, not on every queued upload, so it cannot livelock a drain
     * the way per-upload REPLACE did (#130).
     */
    fun rescheduleForConstraintChange() {
        Log.d(TAG, "rescheduleForConstraintChange() called - re-applying upload constraints")
        val uploadRequest = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraintsProvider.build())
            .build()
        workManager.enqueueUniqueWork(
            UploadWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            uploadRequest
        )
    }

    fun cancelUpload() {
        workManager.cancelUniqueWork(UploadWorker.WORK_NAME)
    }

    fun getWorkStatus(): Flow<UploadWorkStatus> {
        return workManager.getWorkInfosForUniqueWorkFlow(UploadWorker.WORK_NAME)
            .map { workInfos ->
                val workInfo = workInfos.firstOrNull()
                when (workInfo?.state) {
                    WorkInfo.State.RUNNING -> {
                        val progress = workInfo.progress.getFloat(UploadWorker.PROGRESS_KEY, 0f)
                        UploadWorkStatus.Running(progress)
                    }
                    WorkInfo.State.ENQUEUED -> UploadWorkStatus.Enqueued
                    WorkInfo.State.SUCCEEDED -> UploadWorkStatus.Succeeded
                    WorkInfo.State.FAILED -> UploadWorkStatus.Failed
                    WorkInfo.State.BLOCKED -> UploadWorkStatus.Blocked
                    WorkInfo.State.CANCELLED -> UploadWorkStatus.Cancelled
                    null -> UploadWorkStatus.Idle
                }
            }
    }
}

sealed class UploadWorkStatus {
    data object Idle : UploadWorkStatus()
    data object Enqueued : UploadWorkStatus()
    data class Running(val progress: Float) : UploadWorkStatus()
    data object Succeeded : UploadWorkStatus()
    data object Failed : UploadWorkStatus()
    data object Blocked : UploadWorkStatus()
    data object Cancelled : UploadWorkStatus()
}

private const val TAG = "UploadWorkManager"
