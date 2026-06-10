package com.paperless.scanner.testing.fakes

import android.net.Uri
import com.paperless.scanner.data.database.PendingUpload
import com.paperless.scanner.data.repository.UploadQueueRepositoryContract
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Typed fake for [UploadQueueRepositoryContract] (#239/#321). [pendingCountFlow] is
 * replaceable so tests can inject a throwing flow for error-path coverage; queue
 * mutations are recorded in [recordedCalls] for behavior assertions.
 */
class FakeUploadQueueRepository : UploadQueueRepositoryContract {
    /** Replace with a throwing flow to drive upstream-error paths. */
    var pendingCountFlow: Flow<Int> = MutableStateFlow(0)

    override val pendingCount: Flow<Int> get() = pendingCountFlow

    val uploads = ArrayDeque<PendingUpload>()
    val recordedCalls = mutableListOf<String>()

    override suspend fun getNextPendingUpload(): PendingUpload? {
        recordedCalls += "getNextPendingUpload"
        return uploads.firstOrNull()
    }

    override suspend fun getPendingUploadCount(): Int = uploads.size

    override suspend fun markAsUploading(id: Long) {
        recordedCalls += "markAsUploading($id)"
    }

    override suspend fun resetToPending(id: Long) {
        recordedCalls += "resetToPending($id)"
    }

    override suspend fun markAsCompleted(id: Long) {
        recordedCalls += "markAsCompleted($id)"
        uploads.removeAll { it.id == id }
    }

    override suspend fun markAsFailed(id: Long, errorMessage: String?) {
        recordedCalls += "markAsFailed($id, $errorMessage)"
        uploads.removeAll { it.id == id }
    }

    override fun getAllUris(upload: PendingUpload): List<Uri> {
        val uris = mutableListOf(Uri.parse(upload.uri))
        upload.additionalUris.forEach { uris.add(Uri.parse(it)) }
        return uris
    }
}
