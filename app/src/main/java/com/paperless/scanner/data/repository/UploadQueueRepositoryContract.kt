package com.paperless.scanner.data.repository

import android.net.Uri
import com.paperless.scanner.data.database.PendingUpload
import kotlinx.coroutines.flow.Flow

/**
 * Test-double seam for [UploadQueueRepository] (#321): the queue surface consumed by
 * UploadWorker, WidgetUpdateWorker and ServerHealthViewModel. Other production code
 * keeps injecting the concrete class.
 */
interface UploadQueueRepositoryContract {
    val pendingCount: Flow<Int>
    suspend fun getNextPendingUpload(): PendingUpload?
    suspend fun getPendingUploadCount(): Int
    suspend fun markAsUploading(id: Long)
    suspend fun resetToPending(id: Long)
    suspend fun markAsCompleted(id: Long)
    suspend fun markAsFailed(id: Long, errorMessage: String?)
    fun getAllUris(upload: PendingUpload): List<Uri>
}
