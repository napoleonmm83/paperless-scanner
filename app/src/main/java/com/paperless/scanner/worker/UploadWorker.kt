package com.paperless.scanner.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.paperless.scanner.MainActivity
import com.paperless.scanner.R
import com.paperless.scanner.data.database.UploadStatus
import com.paperless.scanner.data.repository.DocumentRepository
import com.paperless.scanner.data.repository.UploadQueueRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay

@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val uploadQueueRepository: UploadQueueRepository,
    private val documentRepository: DocumentRepository
) : CoroutineWorker(context, workerParams) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // Throttling für Notification-Updates (verhindert Rate-Limiting)
    private var lastNotificationUpdate = 0L
    private var lastNotificationProgress = -1

    override suspend fun doWork(): Result {
        Log.d(TAG, "UploadWorker started")

        createNotificationChannel()

        // Zähle alle ausstehenden Uploads für Fortschrittsanzeige
        var totalUploads = uploadQueueRepository.getPendingUploadCount()
        var currentUpload = 0
        var successCount = 0
        var failCount = 0

        Log.d(TAG, "Total uploads pending: $totalUploads")

        // Falls keine Uploads vorhanden, direkt beenden
        if (totalUploads == 0) {
            Log.d(TAG, "No pending uploads found, exiting")
            return Result.success()
        }

        // Initiale Notification zeigen
        setForeground(createProgressForegroundInfo(
            currentUpload = 0,
            totalUploads = totalUploads,
            currentDocumentName = "Vorbereitung...",
            uploadProgress = 0
        ))

        while (true) {
            val pendingUpload = uploadQueueRepository.getNextPendingUpload() ?: break
            currentUpload++

            // Falls mehr Uploads hinzugekommen sind als initial gezählt
            if (currentUpload > totalUploads) {
                totalUploads = currentUpload
            }

            val documentName = pendingUpload.title?.takeIf { it.isNotBlank() }
                ?: "Dokument $currentUpload"

            Log.d(TAG, "Processing upload $currentUpload/$totalUploads: ${pendingUpload.id}")
            uploadQueueRepository.markAsUploading(pendingUpload.id)

            // Reset throttle state für neues Dokument
            lastNotificationProgress = -1

            // Notification mit aktuellem Dokument aktualisieren
            setForeground(createProgressForegroundInfo(
                currentUpload = currentUpload,
                totalUploads = totalUploads,
                currentDocumentName = documentName,
                uploadProgress = 0
            ))

            try {
                val result = if (pendingUpload.isMultiPage) {
                    val uris = uploadQueueRepository.getAllUris(pendingUpload)
                    val pageCount = uris.size
                    documentRepository.uploadMultiPageDocument(
                        uris = uris,
                        title = pendingUpload.title,
                        tagIds = pendingUpload.tagIds,
                        documentTypeId = pendingUpload.documentTypeId,
                        correspondentId = pendingUpload.correspondentId,
                        onProgress = { progress ->
                            setProgressAsync(workDataOf(PROGRESS_KEY to progress))
                            updateNotificationProgress(
                                currentUpload = currentUpload,
                                totalUploads = totalUploads,
                                currentDocumentName = "$documentName ($pageCount Seiten)",
                                uploadProgress = (progress * 100).toInt()
                            )
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
                            updateNotificationProgress(
                                currentUpload = currentUpload,
                                totalUploads = totalUploads,
                                currentDocumentName = documentName,
                                uploadProgress = (progress * 100).toInt()
                            )
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
            setShowBadge(true)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun createProgressForegroundInfo(
        currentUpload: Int,
        totalUploads: Int,
        currentDocumentName: String,
        uploadProgress: Int
    ): ForegroundInfo {
        val notification = buildProgressNotification(
            currentUpload = currentUpload,
            totalUploads = totalUploads,
            currentDocumentName = currentDocumentName,
            uploadProgress = uploadProgress
        )

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun buildProgressNotification(
        currentUpload: Int,
        totalUploads: Int,
        currentDocumentName: String,
        uploadProgress: Int
    ): android.app.Notification {
        // Intent um App zu öffnen wenn auf Notification geklickt wird
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = if (totalUploads > 1) {
            "Upload $currentUpload von $totalUploads"
        } else {
            "Dokument wird hochgeladen"
        }

        val progressText = if (uploadProgress > 0) {
            "$currentDocumentName • $uploadProgress%"
        } else {
            currentDocumentName
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(progressText)
            .setSubText("Paperless Scanner")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, uploadProgress, uploadProgress == 0)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun updateNotificationProgress(
        currentUpload: Int,
        totalUploads: Int,
        currentDocumentName: String,
        uploadProgress: Int
    ) {
        val now = System.currentTimeMillis()
        val progressDelta = kotlin.math.abs(uploadProgress - lastNotificationProgress)

        // Throttle: nur updaten wenn 500ms vergangen ODER Fortschritt >= 5% geändert
        if (now - lastNotificationUpdate < NOTIFICATION_THROTTLE_MS && progressDelta < 5) {
            return
        }

        lastNotificationUpdate = now
        lastNotificationProgress = uploadProgress

        val notification = buildProgressNotification(
            currentUpload = currentUpload,
            totalUploads = totalUploads,
            currentDocumentName = currentDocumentName,
            uploadProgress = uploadProgress
        )
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun showCompletionNotification(successCount: Int, failCount: Int) {
        val (title, message, icon) = when {
            failCount == 0 && successCount > 0 -> Triple(
                "Upload erfolgreich ✓",
                if (successCount == 1) "Dokument wurde hochgeladen"
                else "$successCount Dokumente wurden hochgeladen",
                android.R.drawable.stat_sys_upload_done
            )
            failCount > 0 && successCount > 0 -> Triple(
                "Upload teilweise erfolgreich",
                "$successCount hochgeladen, $failCount fehlgeschlagen",
                android.R.drawable.stat_notify_error
            )
            failCount > 0 -> Triple(
                "Upload fehlgeschlagen",
                if (failCount == 1) "Dokument konnte nicht hochgeladen werden"
                else "$failCount Dokumente konnten nicht hochgeladen werden",
                android.R.drawable.stat_notify_error
            )
            else -> return
        }

        // Intent um App zu öffnen
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(icon)
            .setContentTitle(title)
            .setContentText(message)
            .setSubText("Paperless Scanner")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
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
        private const val NOTIFICATION_THROTTLE_MS = 500L
    }
}
