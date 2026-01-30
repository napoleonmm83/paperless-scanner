package com.paperless.scanner.data.repository

import android.net.Uri
import com.paperless.scanner.data.database.PendingUpload
import com.paperless.scanner.data.database.PendingUploadDao
import com.paperless.scanner.data.database.UploadStatus
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UploadQueueRepository @Inject constructor(
    private val pendingUploadDao: PendingUploadDao
) {
    val allPendingUploads: Flow<List<PendingUpload>> = pendingUploadDao.getAllPendingUploads()

    val pendingCount: Flow<Int> = pendingUploadDao.getPendingCount()

    fun getUploadsByStatus(status: UploadStatus): Flow<List<PendingUpload>> {
        return pendingUploadDao.getUploadsByStatus(status)
    }

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

    suspend fun getNextPendingUpload(): PendingUpload? {
        return pendingUploadDao.getPendingAndFailedUploads().firstOrNull()
    }

    suspend fun getPendingUploadCount(): Int {
        return pendingUploadDao.getPendingUploadCountSync()
    }

    suspend fun getUploadById(id: Long): PendingUpload? {
        return pendingUploadDao.getUploadById(id)
    }

    suspend fun markAsUploading(id: Long) {
        pendingUploadDao.updateStatus(id, UploadStatus.UPLOADING)
    }

    suspend fun markAsCompleted(id: Long) {
        pendingUploadDao.deleteById(id)
    }

    suspend fun markAsFailed(id: Long, errorMessage: String?) {
        pendingUploadDao.markAsFailed(id, errorMessage = errorMessage)
    }

    suspend fun retryFailedUploads(maxRetries: Int = 3) {
        pendingUploadDao.resetFailedForRetry(maxRetries)
    }

    suspend fun deleteUpload(id: Long) {
        pendingUploadDao.deleteById(id)
    }

    suspend fun clearCompletedUploads() {
        pendingUploadDao.deleteByStatus(UploadStatus.COMPLETED)
    }

    suspend fun clearAllUploads() {
        pendingUploadDao.deleteByStatus(UploadStatus.PENDING)
        pendingUploadDao.deleteByStatus(UploadStatus.FAILED)
        pendingUploadDao.deleteByStatus(UploadStatus.UPLOADING)
    }

    fun getAllUris(upload: PendingUpload): List<Uri> {
        val uris = mutableListOf(Uri.parse(upload.uri))
        upload.additionalUris.forEach { uriString ->
            uris.add(Uri.parse(uriString))
        }
        return uris
    }
}
