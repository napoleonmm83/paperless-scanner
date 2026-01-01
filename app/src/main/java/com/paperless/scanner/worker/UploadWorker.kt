package com.paperless.scanner.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.paperless.scanner.R
import com.paperless.scanner.data.database.UploadStatus
import com.paperless.scanner.data.repository.DocumentRepository
import com.paperless.scanner.data.repository.UploadQueueRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val uploadQueueRepository: UploadQueueRepository,
    private val documentRepository: DocumentRepository
) : CoroutineWorker(context, workerParams) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result {
        Log.d(TAG, "UploadWorker started")

        createNotificationChannel()

        var successCount = 0
        var failCount = 0

        while (true) {
            val pendingUpload = uploadQueueRepository.getNextPendingUpload() ?: break

            Log.d(TAG, "Processing upload: ${pendingUpload.id}")
            uploadQueueRepository.markAsUploading(pendingUpload.id)

            setForeground(createForegroundInfo("Dokument wird hochgeladen..."))

            try {
                val result = if (pendingUpload.isMultiPage) {
                    val uris = uploadQueueRepository.getAllUris(pendingUpload)
                    documentRepository.uploadMultiPageDocument(
                        uris = uris,
                        title = pendingUpload.title,
                        tagIds = pendingUpload.tagIds,
                        documentTypeId = pendingUpload.documentTypeId,
                        correspondentId = pendingUpload.correspondentId,
                        onProgress = { progress ->
                            setProgressAsync(workDataOf(PROGRESS_KEY to progress))
                        }
                    )
                } else {
                    documentRepository.uploadDocument(
                        uri = Uri.parse(pendingUpload.uri),
                        title = pendingUpload.title,
                        tagIds = pendingUpload.tagIds,
                        documentTypeId = pendingUpload.documentTypeId,
                        correspondentId = pendingUpload.correspondentId,
                        onProgress = { progress ->
                            setProgressAsync(workDataOf(PROGRESS_KEY to progress))
                        }
                    )
                }

                result.onSuccess {
                    Log.d(TAG, "Upload successful: ${pendingUpload.id}")
                    uploadQueueRepository.markAsCompleted(pendingUpload.id)
                    successCount++
                }.onFailure { e ->
                    Log.e(TAG, "Upload failed: ${pendingUpload.id}", e)
                    uploadQueueRepository.markAsFailed(pendingUpload.id, e.message)
                    failCount++

                    if (pendingUpload.retryCount >= MAX_RETRIES) {
                        Log.w(TAG, "Max retries reached for: ${pendingUpload.id}")
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Unexpected error during upload: ${pendingUpload.id}", e)
                uploadQueueRepository.markAsFailed(pendingUpload.id, e.message)
                failCount++
            }
        }

        showCompletionNotification(successCount, failCount)

        Log.d(TAG, "UploadWorker completed: $successCount success, $failCount failed")
        return if (failCount > 0 && successCount == 0) Result.failure() else Result.success()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Upload-Status",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Zeigt den Fortschritt von Dokument-Uploads"
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun createForegroundInfo(message: String): ForegroundInfo {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle("Paperless Scanner")
            .setContentText(message)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()

        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private fun showCompletionNotification(successCount: Int, failCount: Int) {
        val message = when {
            failCount == 0 && successCount > 0 -> "$successCount Dokument(e) erfolgreich hochgeladen"
            failCount > 0 && successCount > 0 -> "$successCount hochgeladen, $failCount fehlgeschlagen"
            failCount > 0 -> "$failCount Upload(s) fehlgeschlagen"
            else -> return
        }

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(
                if (failCount == 0) android.R.drawable.stat_sys_upload_done
                else android.R.drawable.stat_notify_error
            )
            .setContentTitle("Upload abgeschlossen")
            .setContentText(message)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(COMPLETION_NOTIFICATION_ID, notification)
    }

    companion object {
        private const val TAG = "UploadWorker"
        const val WORK_NAME = "upload_queue_worker"
        const val CHANNEL_ID = "upload_channel"
        const val NOTIFICATION_ID = 1001
        const val COMPLETION_NOTIFICATION_ID = 1002
        const val PROGRESS_KEY = "upload_progress"
        const val MAX_RETRIES = 3
    }
}
