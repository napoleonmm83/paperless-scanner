package com.paperless.scanner.worker

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
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
    @ApplicationContext private val context: Context
) {
    private val workManager = WorkManager.getInstance(context)

    /**
     * Schedule an immediate drain of the pending-upload queue.
     *
     * The battery/storage constraints defer the transfer below the OS
     * low-battery / low-storage thresholds rather than draining a nearly-empty
     * battery or a full disk; the work stays enqueued and runs as soon as the
     * constraint clears (mirrors SyncWorker). See issue #134.
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
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()

        val uploadRequest = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints)
            .build()

        Log.d(TAG, "Enqueuing work with APPEND_OR_REPLACE policy, workId: ${uploadRequest.id}")
        workManager.enqueueUniqueWork(
            UploadWorker.WORK_NAME,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            uploadRequest
        )
        Log.d(TAG, "Work enqueued successfully")
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
