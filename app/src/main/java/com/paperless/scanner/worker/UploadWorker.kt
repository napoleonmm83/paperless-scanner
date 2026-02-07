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
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.analytics.CrashlyticsHelper
import com.paperless.scanner.data.database.UploadStatus
import com.paperless.scanner.data.database.entities.SyncHistoryEntry
import com.paperless.scanner.data.repository.DocumentRepository
import com.paperless.scanner.data.repository.SyncHistoryRepository
import com.paperless.scanner.data.repository.UploadQueueRepository
import com.paperless.scanner.util.FileUtils
import com.paperless.scanner.widget.WidgetUpdateWorker
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.delay

@HiltWorker
class UploadWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val uploadQueueRepository: UploadQueueRepository,
    private val documentRepository: DocumentRepository,
    private val networkMonitor: com.paperless.scanner.data.network.NetworkMonitor,
    private val serverHealthMonitor: com.paperless.scanner.data.health.ServerHealthMonitor,
    private val syncHistoryRepository: SyncHistoryRepository,
    private val crashlyticsHelper: CrashlyticsHelper
) : CoroutineWorker(context, workerParams) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    // Throttling für Notification-Updates (verhindert Rate-Limiting)
    private var lastNotificationUpdate = 0L
    private var lastNotificationProgress = -1

    override suspend fun doWork(): Result {
        createNotificationChannel()

        // Pre-check: Ensure we have validated internet before starting uploads
        if (!networkMonitor.hasValidatedInternet()) {
            Log.w(TAG, "No validated internet connection - aborting upload worker")
            crashlyticsHelper.logStateBreadcrumb("WORKER_UPLOAD", "retry - no internet")
            return Result.retry() // Retry later when internet is validated
        }

        // Pre-check: Ensure Paperless server is reachable before starting uploads
        serverHealthMonitor.checkServerHealth()
        if (!serverHealthMonitor.isServerReachable.value) {
            Log.w(TAG, "Paperless server not reachable (status: ${serverHealthMonitor.serverStatus.value}) - aborting upload worker")
            crashlyticsHelper.logStateBreadcrumb("WORKER_UPLOAD", "retry - server unreachable")
            return Result.retry() // Retry later when server is reachable
        }

        // Zähle alle ausstehenden Uploads für Fortschrittsanzeige
        var totalUploads = uploadQueueRepository.getPendingUploadCount()
        crashlyticsHelper.logActionBreadcrumb("WORKER_UPLOAD", "start, $totalUploads pending")
        var currentUpload = 0
        var successCount = 0
        var failCount = 0

        // Falls keine Uploads vorhanden, direkt beenden
        if (totalUploads == 0) {
            return Result.success()
        }

        // Initiale Notification zeigen
        setForeground(createProgressForegroundInfo(
            currentUpload = 0,
            totalUploads = totalUploads,
            currentDocumentName = applicationContext.getString(R.string.notification_preparing),
            uploadProgress = 0
        ))

        // Track bereits verarbeitete Uploads um Endlosschleifen zu vermeiden
        val processedIds = mutableSetOf<Long>()
        val maxIterations = totalUploads + MAX_RETRIES * totalUploads + 10 // Safety limit

        while (true) {
            val pendingUpload = uploadQueueRepository.getNextPendingUpload() ?: break

            // Safety check: bereits in diesem Run verarbeitet?
            if (pendingUpload.id in processedIds) {
                Log.w(TAG, "Upload ${pendingUpload.id} already processed in this run, skipping")
                break
            }
            processedIds.add(pendingUpload.id)

            // Safety check: zu viele Iterationen?
            if (currentUpload >= maxIterations) {
                Log.e(TAG, "Max iterations reached ($maxIterations), breaking loop")
                break
            }

            currentUpload++

            // Falls mehr Uploads hinzugekommen sind als initial gezählt
            if (currentUpload > totalUploads) {
                totalUploads = currentUpload
            }

            val documentName = pendingUpload.title?.takeIf { it.isNotBlank() }
                ?: applicationContext.getString(R.string.document_number, currentUpload)

            // Validate that files exist before attempting upload
            if (pendingUpload.isMultiPage) {
                val uris = uploadQueueRepository.getAllUris(pendingUpload)
                val missingFiles = uris.filterNot { FileUtils.fileExists(it) }
                if (missingFiles.isNotEmpty()) {
                    Log.e(TAG, "Upload ${pendingUpload.id}: ${missingFiles.size}/${uris.size} files missing!")
                    missingFiles.forEach { uri ->
                        Log.e(TAG, "  Missing file: $uri")
                    }
                    uploadQueueRepository.markAsFailed(
                        pendingUpload.id,
                        applicationContext.getString(R.string.upload_error_files_not_found, missingFiles.size, uris.size)
                    )
                    failCount++
                    continue
                }
            } else {
                val uri = Uri.parse(pendingUpload.uri)
                if (!FileUtils.fileExists(uri)) {
                    val fileSize = FileUtils.getFileSize(uri)
                    Log.e(TAG, "Upload ${pendingUpload.id}: File not found or not readable: $uri (size: $fileSize bytes)")
                    uploadQueueRepository.markAsFailed(pendingUpload.id, applicationContext.getString(R.string.upload_error_file_not_found, uri.lastPathSegment ?: ""))
                    failCount++
                    continue
                }
            }

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
                        customFields = pendingUpload.customFields,
                        onProgress = { progress ->
                            setProgressAsync(workDataOf(PROGRESS_KEY to progress))
                            updateNotificationProgress(
                                currentUpload = currentUpload,
                                totalUploads = totalUploads,
                                currentDocumentName = applicationContext.getString(R.string.notification_pages_suffix, documentName, pageCount),
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
                        customFields = pendingUpload.customFields,
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
                    Log.d(TAG, "Upload ${pendingUpload.id}: Upload successful, task ID received")
                    uploadQueueRepository.markAsCompleted(pendingUpload.id)
                    successCount++

                    // Record success in SyncHistory
                    try {
                        val pageInfo = if (pendingUpload.isMultiPage) {
                            val uris = uploadQueueRepository.getAllUris(pendingUpload)
                            applicationContext.getString(R.string.sync_history_pages_suffix, uris.size)
                        } else ""
                        syncHistoryRepository.recordSuccess(
                            actionType = SyncHistoryEntry.ACTION_UPLOAD,
                            title = applicationContext.getString(R.string.sync_history_upload_success),
                            details = "${documentName}${pageInfo}"
                        )
                    } catch (historyError: Exception) {
                        Log.w(TAG, "Failed to record sync history: ${historyError.message}")
                    }

                    // Clean up local file copies after successful upload
                    val urisToClean = if (pendingUpload.isMultiPage) {
                        uploadQueueRepository.getAllUris(pendingUpload)
                    } else {
                        listOf(Uri.parse(pendingUpload.uri))
                    }
                    Log.d(TAG, "Upload ${pendingUpload.id}: Cleaning up ${urisToClean.size} local files")
                    urisToClean.forEach { uri ->
                        FileUtils.deleteLocalCopy(uri)
                    }
                    Log.d(TAG, "Upload ${pendingUpload.id}: Cleanup complete")
                }.onFailure { e ->
                    // Safe error message extraction (prevent secondary exceptions)
                    val uploadFailedMsg = applicationContext.getString(R.string.sync_history_upload_failed)
                    val safeErrorMessage = try {
                        e.message?.takeIf { it.isNotBlank() } ?: uploadFailedMsg
                    } catch (_: Exception) {
                        uploadFailedMsg
                    }
                    Log.e(TAG, "Upload failed: ${pendingUpload.id} - $safeErrorMessage", e)
                    Log.e(TAG, "Upload failed: Exception type: ${e.javaClass.simpleName}")
                    uploadQueueRepository.markAsFailed(pendingUpload.id, safeErrorMessage)
                    failCount++

                    // Record failure in SyncHistory with user-friendly and technical error
                    try {
                        val httpCode = when (e) {
                            is PaperlessException.ClientError -> e.code
                            is PaperlessException.ServerError -> e.code
                            is PaperlessException.AuthError -> e.code
                            else -> null
                        }
                        syncHistoryRepository.recordFailure(
                            actionType = SyncHistoryEntry.ACTION_UPLOAD,
                            title = applicationContext.getString(R.string.sync_history_upload_failed),
                            userMessage = syncHistoryRepository.getUserFriendlyError(applicationContext, httpCode, e as? Exception),
                            technicalError = syncHistoryRepository.getTechnicalError(httpCode, e.message, e as? Exception),
                            details = documentName
                        )
                    } catch (historyError: Exception) {
                        Log.w(TAG, "Failed to record sync history: ${historyError.message}")
                    }

                    if (pendingUpload.retryCount >= MAX_RETRIES) {
                        Log.w(TAG, "Max retries reached for: ${pendingUpload.id}")
                        // Clean up local files on permanent failure too
                        val urisToClean = if (pendingUpload.isMultiPage) {
                            uploadQueueRepository.getAllUris(pendingUpload)
                        } else {
                            listOf(Uri.parse(pendingUpload.uri))
                        }
                        urisToClean.forEach { uri ->
                            FileUtils.deleteLocalCopy(uri)
                        }
                    }
                }

            } catch (e: Exception) {
                // Safe error message extraction (prevent secondary exceptions)
                val unexpectedErrorMsg = applicationContext.getString(R.string.sync_history_unexpected_error)
                val safeErrorMessage = try {
                    e.message?.takeIf { it.isNotBlank() } ?: unexpectedErrorMsg
                } catch (_: Exception) {
                    unexpectedErrorMsg
                }
                Log.e(TAG, "Unexpected error during upload: ${pendingUpload.id} - $safeErrorMessage", e)
                uploadQueueRepository.markAsFailed(pendingUpload.id, safeErrorMessage)
                failCount++

                // Record unexpected failure in SyncHistory
                try {
                    syncHistoryRepository.recordFailure(
                        actionType = SyncHistoryEntry.ACTION_UPLOAD,
                        title = applicationContext.getString(R.string.sync_history_upload_failed),
                        userMessage = unexpectedErrorMsg,
                        technicalError = "${e.javaClass.simpleName}: ${e.message}",
                        details = documentName
                    )
                } catch (historyError: Exception) {
                    Log.w(TAG, "Failed to record sync history: ${historyError.message}")
                }
            }
        }

        showCompletionNotification(successCount, failCount)

        // Trigger widget update to reflect new pending count
        WidgetUpdateWorker.enqueue(applicationContext)

        crashlyticsHelper.logActionBreadcrumb("WORKER_UPLOAD", "done, success=$successCount, fail=$failCount")
        return if (failCount > 0 && successCount == 0) Result.failure() else Result.success()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            applicationContext.getString(R.string.notification_channel_upload),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = applicationContext.getString(R.string.notification_channel_upload_desc)
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
            applicationContext.getString(R.string.notification_upload_of, currentUpload, totalUploads)
        } else {
            applicationContext.getString(R.string.notification_uploading_document)
        }

        val progressText = if (uploadProgress > 0) {
            applicationContext.getString(R.string.notification_progress_percent, currentDocumentName, uploadProgress)
        } else {
            currentDocumentName
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(progressText)
            .setSubText(applicationContext.getString(R.string.notification_subtext))
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
                applicationContext.getString(R.string.notification_upload_successful),
                if (successCount == 1) applicationContext.getString(R.string.notification_document_uploaded)
                else applicationContext.getString(R.string.notification_documents_uploaded, successCount),
                android.R.drawable.stat_sys_upload_done
            )
            failCount > 0 && successCount > 0 -> Triple(
                applicationContext.getString(R.string.notification_upload_partial),
                applicationContext.getString(R.string.notification_uploaded_failed_count, successCount, failCount),
                android.R.drawable.stat_notify_error
            )
            failCount > 0 -> Triple(
                applicationContext.getString(R.string.notification_upload_failed),
                if (failCount == 1) applicationContext.getString(R.string.notification_document_not_uploaded)
                else applicationContext.getString(R.string.notification_documents_not_uploaded, failCount),
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
            .setSubText(applicationContext.getString(R.string.notification_subtext))
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
