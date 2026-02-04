package com.paperless.scanner.data.repository

import android.net.Uri
import com.paperless.scanner.data.database.PendingUpload
import com.paperless.scanner.data.database.PendingUploadDao
import com.paperless.scanner.data.database.UploadStatus
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UploadQueueRepository - Repository for managing document upload queue.
 *
 * **QUEUE-ONLY ARCHITECTURE:**
 * This repository implements the "queue-only" upload pattern where:
 * 1. Documents are immediately added to a persistent queue (Room database)
 * 2. A background WorkManager processes the queue asynchronously
 * 3. Users get immediate feedback while uploads happen in background
 *
 * **BENEFITS:**
 * - Instant UI responsiveness (no waiting for upload)
 * - Automatic retry on network failures
 * - Survives app restarts and process death
 * - Supports multi-page document uploads
 * - Shows upload progress in queue screen
 *
 * **UPLOAD STATUS LIFECYCLE:**
 * ```
 * PENDING → UPLOADING → COMPLETED (deleted)
 *              ↓
 *           FAILED → (retry) → PENDING
 * ```
 *
 * **USAGE:**
 * ```kotlin
 * // Queue a document for upload
 * val queueId = uploadQueueRepository.queueUpload(
 *     uri = documentUri,
 *     title = "Invoice 2024",
 *     tagIds = listOf(1, 2),
 *     documentTypeId = 5
 * )
 *
 * // Observe queue status
 * uploadQueueRepository.allPendingUploads.collect { uploads ->
 *     updateQueueUI(uploads)
 * }
 * ```
 *
 * @property pendingUploadDao Room DAO for queue persistence
 *
 * @see PendingUpload Entity representing a queued upload
 * @see PendingUploadDao For underlying database operations
 * @see UploadWorker WorkManager worker that processes the queue
 * @see UploadStatus Upload status enum
 */
@Singleton
class UploadQueueRepository @Inject constructor(
    private val pendingUploadDao: PendingUploadDao
) {
    /**
     * Reactive Flow of all pending uploads.
     * Emits updated list whenever queue changes (add, remove, status change).
     */
    val allPendingUploads: Flow<List<PendingUpload>> = pendingUploadDao.getAllPendingUploads()

    /**
     * Reactive Flow of pending upload count.
     * Useful for badge/notification display.
     */
    val pendingCount: Flow<Int> = pendingUploadDao.getPendingCount()

    /**
     * Get uploads filtered by status as a reactive Flow.
     *
     * @param status Filter by [UploadStatus] (PENDING, UPLOADING, FAILED, COMPLETED)
     * @return [Flow] emitting filtered list on changes
     */
    fun getUploadsByStatus(status: UploadStatus): Flow<List<PendingUpload>> {
        return pendingUploadDao.getUploadsByStatus(status)
    }

    /**
     * Add a single-page document to the upload queue.
     *
     * The document is persisted immediately and will be processed
     * by the background upload worker.
     *
     * @param uri Content URI of the document file
     * @param title Optional document title (auto-generated if null)
     * @param tagIds List of tag IDs to apply
     * @param documentTypeId Optional document type ID
     * @param correspondentId Optional correspondent ID
     * @param customFields Map of custom field ID to value
     * @return Database row ID of the queued upload
     * @see queueMultiPageUpload For multi-page documents
     */
    suspend fun queueUpload(
        uri: Uri,
        title: String? = null,
        tagIds: List<Int> = emptyList(),
        documentTypeId: Int? = null,
        correspondentId: Int? = null,
        customFields: Map<Int, String> = emptyMap()
    ): Long {
        val pendingUpload = PendingUpload(
            uri = uri.toString(),
            title = title,
            tagIds = tagIds,
            documentTypeId = documentTypeId,
            correspondentId = correspondentId,
            isMultiPage = false,
            customFields = customFields
        )
        return pendingUploadDao.insert(pendingUpload)
    }

    /**
     * Add a multi-page document to the upload queue.
     *
     * Creates a single PDF from multiple page images before uploading.
     * The first URI is stored as primary, additional URIs in separate field.
     *
     * @param uris List of content URIs for each page (must not be empty)
     * @param title Optional document title
     * @param tagIds List of tag IDs to apply
     * @param documentTypeId Optional document type ID
     * @param correspondentId Optional correspondent ID
     * @param customFields Map of custom field ID to value
     * @return Database row ID of the queued upload
     * @throws IllegalArgumentException If URIs list is empty
     * @see queueUpload For single-page documents
     */
    suspend fun queueMultiPageUpload(
        uris: List<Uri>,
        title: String? = null,
        tagIds: List<Int> = emptyList(),
        documentTypeId: Int? = null,
        correspondentId: Int? = null,
        customFields: Map<Int, String> = emptyMap()
    ): Long {
        if (uris.isEmpty()) {
            throw IllegalArgumentException("URIs list cannot be empty")
        }

        val pendingUpload = PendingUpload(
            uri = uris.first().toString(),
            title = title,
            tagIds = tagIds,
            documentTypeId = documentTypeId,
            correspondentId = correspondentId,
            isMultiPage = true,
            additionalUris = uris.drop(1).map { it.toString() },
            customFields = customFields
        )
        return pendingUploadDao.insert(pendingUpload)
    }

    /**
     * Get the next upload ready for processing.
     *
     * @return First [PendingUpload] with PENDING or FAILED status, or null if queue empty
     */
    suspend fun getNextPendingUpload(): PendingUpload? {
        return pendingUploadDao.getPendingAndFailedUploads().firstOrNull()
    }

    /**
     * Get current count of pending uploads (synchronous).
     *
     * @return Number of uploads in PENDING or FAILED status
     */
    suspend fun getPendingUploadCount(): Int {
        return pendingUploadDao.getPendingUploadCountSync()
    }

    /**
     * Get a specific upload by ID.
     *
     * @param id Upload database ID
     * @return [PendingUpload] or null if not found
     */
    suspend fun getUploadById(id: Long): PendingUpload? {
        return pendingUploadDao.getUploadById(id)
    }

    /**
     * Mark an upload as currently in progress.
     *
     * @param id Upload database ID
     */
    suspend fun markAsUploading(id: Long) {
        pendingUploadDao.updateStatus(id, UploadStatus.UPLOADING)
    }

    /**
     * Mark an upload as successfully completed (deletes from queue).
     *
     * @param id Upload database ID
     */
    suspend fun markAsCompleted(id: Long) {
        pendingUploadDao.deleteById(id)
    }

    /**
     * Mark an upload as failed with error message.
     *
     * @param id Upload database ID
     * @param errorMessage Optional error message for display
     */
    suspend fun markAsFailed(id: Long, errorMessage: String?) {
        pendingUploadDao.markAsFailed(id, errorMessage = errorMessage)
    }

    /**
     * Reset failed uploads for retry up to max attempts.
     *
     * @param maxRetries Maximum retry attempts before giving up (default: 3)
     */
    suspend fun retryFailedUploads(maxRetries: Int = 3) {
        pendingUploadDao.resetFailedForRetry(maxRetries)
    }

    /**
     * Remove an upload from the queue.
     *
     * @param id Upload database ID
     */
    suspend fun deleteUpload(id: Long) {
        pendingUploadDao.deleteById(id)
    }

    /**
     * Clear all completed uploads from the queue.
     */
    suspend fun clearCompletedUploads() {
        pendingUploadDao.deleteByStatus(UploadStatus.COMPLETED)
    }

    /**
     * Clear entire upload queue (all statuses).
     * Use with caution - removes pending uploads that haven't been processed.
     */
    suspend fun clearAllUploads() {
        pendingUploadDao.deleteByStatus(UploadStatus.PENDING)
        pendingUploadDao.deleteByStatus(UploadStatus.FAILED)
        pendingUploadDao.deleteByStatus(UploadStatus.UPLOADING)
    }

    /**
     * Get all URIs for a multi-page upload.
     *
     * @param upload The [PendingUpload] to get URIs from
     * @return List of all page URIs (primary + additional)
     */
    fun getAllUris(upload: PendingUpload): List<Uri> {
        val uris = mutableListOf(Uri.parse(upload.uri))
        upload.additionalUris.forEach { uriString ->
            uris.add(Uri.parse(uriString))
        }
        return uris
    }
}
