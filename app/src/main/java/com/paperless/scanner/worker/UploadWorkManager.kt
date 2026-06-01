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

    fun scheduleUpload() {
        Log.d(TAG, "scheduleUpload() called")
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val uploadRequest = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniqueWork(
            UploadWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
            uploadRequest
        )
    }

    /**
     * Schedule an immediate drain of the pending-upload queue.
     *
     * The battery/storage constraints defer the transfer below the OS
     * low-battery / low-storage thresholds rather than draining a nearly-empty
     * battery or a full disk; the work stays enqueued and runs as soon as the
     * constraint clears (mirrors SyncWorker). See issue #134.
     *
     * NOTE: still uses [ExistingWorkPolicy.REPLACE]. Switching to KEEP (#130) is
     * deferred: the worker's drain loop is bounded by a maxIterations safety cap
     * computed from the pending count at start, so a burst exceeding that cap
     * under KEEP could strand newly-enqueued rows with no follow-up work. A safe
     * KEEP requires hardening that loop first (tracked under #130).
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

        Log.d(TAG, "Enqueuing work with REPLACE policy, workId: ${uploadRequest.id}")
        workManager.enqueueUniqueWork(
            UploadWorker.WORK_NAME,
            ExistingWorkPolicy.REPLACE,
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
