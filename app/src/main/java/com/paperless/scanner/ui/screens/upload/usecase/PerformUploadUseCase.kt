package com.paperless.scanner.ui.screens.upload.usecase

import android.content.Context
import android.net.Uri
import android.util.Log
import com.paperless.scanner.R
import com.paperless.scanner.data.analytics.AnalyticsEvent
import com.paperless.scanner.data.analytics.AnalyticsService
import com.paperless.scanner.data.health.ServerHealthMonitor
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.data.repository.UploadQueueRepository
import com.paperless.scanner.ui.screens.scan.ScannedPage
import com.paperless.scanner.util.CoroutineDispatchers
import com.paperless.scanner.util.FileUtils
import com.paperless.scanner.utils.StorageUtil
import com.paperless.scanner.worker.UploadWorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject

/**
 * Outcome of an upload queueing operation, emitted as a stream so the intermediate
 * "queuing" state is observable. UI-agnostic — the ViewModel maps it to its UiState.
 */
sealed interface UploadResult {
    /** Files are being copied / validated before queueing. */
    data object Queuing : UploadResult

    /** Upload(s) successfully added to the queue; WorkManager will process them. */
    data object Queued : UploadResult

    /** Queueing failed; carries already-resolved user-facing strings. */
    data class Failed(
        val userMessage: String,
        val technicalDetails: String?,
        val isRetryable: Boolean = false,
    ) : UploadResult
}

/**
 * Owns the upload orchestration extracted from `UploadViewModel` (issue #42):
 * storage checks, file IO (copy/validate), queueing, work scheduling and analytics.
 *
 * Also exposes the connectivity StateFlows the UploadScreen consumes for status messaging,
 * since they are queueing-relevant context.
 */
class PerformUploadUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val uploadQueueRepository: UploadQueueRepository,
    private val uploadWorkManager: UploadWorkManager,
    private val analyticsService: AnalyticsService,
    private val networkMonitor: NetworkMonitor,
    private val serverHealthMonitor: ServerHealthMonitor,
    private val dispatchers: CoroutineDispatchers,
) {
    val isWifiConnected: StateFlow<Boolean> = networkMonitor.isWifiConnected
    val isOnline: StateFlow<Boolean> = networkMonitor.isOnline
    val isServerReachable: StateFlow<Boolean> = serverHealthMonitor.isServerReachable

    fun uploadSingle(
        uri: Uri,
        title: String? = null,
        tagIds: List<Int> = emptyList(),
        documentTypeId: Int? = null,
        correspondentId: Int? = null,
        customFields: Map<Int, String> = emptyMap(),
    ): Flow<UploadResult> = flow {
        analyticsService.trackEvent(AnalyticsEvent.UploadStarted(pageCount = 1, isMultiPage = false))

        // Check storage BEFORE queueing
        checkStorage(listOf(uri))?.let { error ->
            emit(error)
            return@flow
        }

        emit(UploadResult.Queuing)

        try {
            // Copy file to local storage to ensure WorkManager can access it later
            // Content URIs from SAF lose permissions when passed to WorkManager
            val localUri = if (FileUtils.isLocalFileUri(uri)) {
                uri
            } else {
                FileUtils.copyToLocalStorage(context, uri) ?: run {
                    Log.e(TAG, "Failed to copy file for queue: $uri")
                    emit(UploadResult.Failed(
                        userMessage = context.getString(R.string.error_saving_file),
                        technicalDetails = context.getString(R.string.error_queue_add),
                        isRetryable = false
                    ))
                    return@flow
                }
            }

            // Verify file exists before queueing
            if (!FileUtils.fileExists(localUri)) {
                Log.e(TAG, "File validation failed after copy: $localUri")
                emit(UploadResult.Failed(
                    userMessage = context.getString(R.string.error_file_not_saved),
                    technicalDetails = context.getString(R.string.error_file_not_accessible, localUri.lastPathSegment ?: ""),
                    isRetryable = false
                ))
                return@flow
            }

            // Queue the upload - WorkManager will handle retry, network checks, etc.
            uploadQueueRepository.queueUpload(
                uri = localUri,
                title = title,
                tagIds = tagIds,
                documentTypeId = documentTypeId,
                correspondentId = correspondentId,
                customFields = customFields
            )

            // Trigger immediate upload processing
            uploadWorkManager.scheduleImmediateUpload()

            analyticsService.trackEvent(AnalyticsEvent.UploadQueued(isOffline = false))
            emit(UploadResult.Queued)
        } catch (e: Exception) {
            Log.e(TAG, "Error queueing upload", e)
            analyticsService.trackEvent(AnalyticsEvent.UploadFailed(errorType = "queue_error"))
            emit(UploadResult.Failed(
                userMessage = context.getString(R.string.error_adding_to_queue),
                technicalDetails = e.message ?: context.getString(R.string.error_unknown_short),
                isRetryable = false
            ))
        }
    }.flowOn(dispatchers.io)

    fun uploadMultiPage(
        uris: List<Uri>,
        uploadAsSingleDocument: Boolean = true,
        title: String? = null,
        tagIds: List<Int> = emptyList(),
        documentTypeId: Int? = null,
        correspondentId: Int? = null,
        customFields: Map<Int, String> = emptyMap(),
    ): Flow<UploadResult> = flow {
        val pageCount = uris.size

        analyticsService.trackEvent(AnalyticsEvent.UploadStarted(pageCount = pageCount, isMultiPage = true))

        // Check storage BEFORE queueing
        checkStorage(uris)?.let { error ->
            emit(error)
            return@flow
        }

        emit(UploadResult.Queuing)

        try {
            // Copy files to local storage to ensure WorkManager can access them later
            // Content URIs from SAF lose permissions when passed to WorkManager
            // For large batches, this may take several minutes - process sequentially to avoid OOM
            val localUris = uris.mapIndexedNotNull { index, uri ->
                // Log progress every 10 files for large batches
                if (pageCount > 50 && (index + 1) % 10 == 0) {
                    Log.d(TAG, "Copying progress: ${index + 1}/$pageCount files")
                }

                if (FileUtils.isLocalFileUri(uri)) {
                    uri
                } else {
                    val copiedUri = FileUtils.copyToLocalStorage(context, uri)
                    if (copiedUri == null) {
                        Log.e(TAG, "Page ${index + 1}/$pageCount: Failed to copy: $uri")
                    }
                    copiedUri
                }
            }

            if (localUris.isEmpty()) {
                Log.e(TAG, "Failed to copy any files for queue (0/${uris.size} succeeded)")
                emit(UploadResult.Failed(
                    userMessage = context.getString(R.string.error_saving_file),
                    technicalDetails = context.getString(R.string.error_queue_add),
                    isRetryable = false
                ))
                return@flow
            }

            // Verify all files exist before queueing
            val missingFiles = localUris.filterNot { FileUtils.fileExists(it) }
            if (missingFiles.isNotEmpty()) {
                Log.e(TAG, "File validation failed: ${missingFiles.size}/${localUris.size} files not accessible")
                missingFiles.forEach { uri ->
                    Log.e(TAG, "  Missing: $uri")
                }
                emit(UploadResult.Failed(
                    userMessage = context.getString(R.string.error_files_not_saved),
                    technicalDetails = context.getString(R.string.error_files_not_accessible, missingFiles.size, localUris.size),
                    isRetryable = false
                ))
                return@flow
            }

            // Queue the upload - WorkManager will handle retry, network checks, etc.
            if (uploadAsSingleDocument) {
                // Combined: Merge all pages into a single PDF document
                uploadQueueRepository.queueMultiPageUpload(
                    uris = localUris,
                    title = title,
                    tagIds = tagIds,
                    documentTypeId = documentTypeId,
                    correspondentId = correspondentId,
                    customFields = customFields
                )
            } else {
                // Individual: Queue each page as a separate document
                localUris.forEachIndexed { index, uri ->
                    val individualTitle = if (title.isNullOrBlank()) {
                        null
                    } else if (localUris.size > 1) {
                        "$title (${index + 1}/${localUris.size})"
                    } else {
                        title
                    }
                    uploadQueueRepository.queueUpload(
                        uri = uri,
                        title = individualTitle,
                        tagIds = tagIds,
                        documentTypeId = documentTypeId,
                        correspondentId = correspondentId,
                        customFields = customFields
                    )
                }
            }

            // Trigger immediate upload processing
            uploadWorkManager.scheduleImmediateUpload()

            analyticsService.trackEvent(AnalyticsEvent.UploadQueued(isOffline = false))
            emit(UploadResult.Queued)
        } catch (e: Exception) {
            Log.e(TAG, "Error queueing multi-page upload", e)
            analyticsService.trackEvent(AnalyticsEvent.UploadFailed(errorType = "queue_error"))
            emit(UploadResult.Failed(
                userMessage = context.getString(R.string.error_adding_to_queue),
                technicalDetails = e.message ?: context.getString(R.string.error_unknown_short),
                isRetryable = false
            ))
        }
    }.flowOn(dispatchers.io)

    /**
     * Upload pages with per-page metadata.
     * Groups pages by identical metadata and creates separate uploads for each group.
     */
    fun uploadPagesWithMetadata(pages: List<ScannedPage>): Flow<UploadResult> = flow {
        val pageCount = pages.size

        // CRITICAL: Warn for large batches (50+ files can cause performance issues)
        if (pageCount > 50) {
            Log.w(TAG, "Large batch upload detected: $pageCount pages. This may take several minutes.")
        }

        analyticsService.trackEvent(AnalyticsEvent.UploadStarted(pageCount = pageCount, isMultiPage = true))

        val uris = pages.map { it.uri }

        // Check storage BEFORE queueing
        checkStorage(uris)?.let { error ->
            emit(error)
            return@flow
        }

        emit(UploadResult.Queuing)

        try {
            // Copy files to local storage
            // For large batches, this may take several minutes - process sequentially to avoid OOM
            val localPages = pages.mapIndexedNotNull { index, page ->
                // Log progress every 10 files for large batches
                if (pageCount > 50 && (index + 1) % 10 == 0) {
                    Log.d(TAG, "Copying progress: ${index + 1}/$pageCount pages")
                }

                val localUri = if (FileUtils.isLocalFileUri(page.uri)) {
                    page.uri
                } else {
                    val copiedUri = FileUtils.copyToLocalStorage(context, page.uri)
                    if (copiedUri == null) {
                        Log.e(TAG, "Page ${page.pageNumber}: Failed to copy: ${page.uri}")
                    }
                    copiedUri
                }

                if (localUri != null) {
                    page.copy(uri = localUri)
                } else {
                    null
                }
            }

            if (localPages.isEmpty()) {
                Log.e(TAG, "Failed to copy any files for queue (0/${pages.size} succeeded)")
                emit(UploadResult.Failed(
                    userMessage = context.getString(R.string.error_saving_file),
                    technicalDetails = context.getString(R.string.error_queue_add),
                    isRetryable = false
                ))
                return@flow
            }

            // Verify all files exist before queueing
            val missingFiles = localPages.filterNot { FileUtils.fileExists(it.uri) }
            if (missingFiles.isNotEmpty()) {
                Log.e(TAG, "File validation failed: ${missingFiles.size}/${localPages.size} files not accessible")
                emit(UploadResult.Failed(
                    userMessage = context.getString(R.string.error_files_not_saved),
                    technicalDetails = context.getString(R.string.error_files_not_accessible, missingFiles.size, localPages.size),
                    isRetryable = false
                ))
                return@flow
            }

            // Group pages by metadata for organization
            val groups = localPages.groupBy { it.customMetadata }

            // CRITICAL: "Individuell bearbeiten" workflow ALWAYS uploads individual documents
            // Each image becomes a separate document in Paperless, even if they share metadata
            Log.i(TAG, "Queueing ${localPages.size} pages as individual documents (${groups.size} metadata groups)")

            // Create individual uploads for each page
            groups.forEach { (metadata, groupPages) ->
                val title = metadata?.title
                val tagIds = metadata?.tags ?: emptyList()
                val documentTypeId = metadata?.documentType
                val correspondentId = metadata?.correspondent

                // Upload each page individually with automatic numbering
                groupPages.forEachIndexed { index, page ->
                    val individualTitle = if (groupPages.size == 1) {
                        // Single page in group - use title as-is
                        title
                    } else {
                        // Multiple pages in group - add numbering
                        if (title.isNullOrBlank()) {
                            context.getString(R.string.document_numbered, index + 1, groupPages.size)
                        } else {
                            "$title (${index + 1}/${groupPages.size})"
                        }
                    }

                    uploadQueueRepository.queueUpload(
                        uri = page.uri,
                        title = individualTitle,
                        tagIds = tagIds,
                        documentTypeId = documentTypeId,
                        correspondentId = correspondentId
                    )
                }
            }

            // Trigger immediate upload processing
            uploadWorkManager.scheduleImmediateUpload()

            analyticsService.trackEvent(AnalyticsEvent.UploadQueued(isOffline = false))
            emit(UploadResult.Queued)
        } catch (e: Exception) {
            Log.e(TAG, "Error queueing pages with metadata", e)
            analyticsService.trackEvent(AnalyticsEvent.UploadFailed(errorType = "queue_error"))
            emit(UploadResult.Failed(
                userMessage = context.getString(R.string.error_adding_to_queue),
                technicalDetails = e.message ?: context.getString(R.string.error_unknown_short),
                isRetryable = false
            ))
        }
    }.flowOn(dispatchers.io)

    /**
     * Checks storage space before upload and returns a failure result if insufficient.
     *
     * @return null if storage check passed, [UploadResult.Failed] otherwise
     */
    private fun checkStorage(uris: List<Uri>): UploadResult.Failed? {
        val storageCheck = StorageUtil.checkStorageForUpload(context, uris)

        if (!storageCheck.hasEnoughSpace) {
            Log.w(TAG, "Storage check failed: ${storageCheck.message}")
            analyticsService.trackEvent(AnalyticsEvent.UploadFailed(errorType = "storage_insufficient"))

            return UploadResult.Failed(
                userMessage = context.getString(R.string.error_not_enough_storage),
                technicalDetails = storageCheck.message,
                isRetryable = false
            )
        }

        // Check individual file sizes
        uris.forEach { uri ->
            StorageUtil.validateFileSize(context, uri).onFailure { e ->
                Log.w(TAG, "File size validation failed: ${e.message}")
                analyticsService.trackEvent(AnalyticsEvent.UploadFailed(errorType = "file_too_large"))

                return UploadResult.Failed(
                    userMessage = context.getString(R.string.error_file_too_large_short),
                    technicalDetails = e.message,
                    isRetryable = false
                )
            }
        }

        return null  // Storage check passed
    }

    companion object {
        private const val TAG = "PerformUploadUseCase"
    }
}
