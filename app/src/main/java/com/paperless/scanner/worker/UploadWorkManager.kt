package com.paperless.scanner.worker

import android.content.Context
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
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val uploadRequest = OneTimeWorkRequestBuilder<UploadWorker>()
            .setConstraints(constraints)
            .build()

        workManager.enqueueUniqueWork(
            UploadWorker.WORK_NAME,
            ExistingWorkPolicy.KEEP,
            uploadRequest
        )
    }

    fun scheduleImmediateUpload() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
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
