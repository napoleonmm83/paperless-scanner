package com.paperless.scanner.testing.fakes

import android.net.Uri
import com.paperless.scanner.data.database.PendingUpload
import com.paperless.scanner.data.repository.UploadQueueRepositoryContract
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Typed fake for [UploadQueueRepositoryContract] (#239/#321). The queue is encapsulated
 * (seed via [enqueue]) so the default [pendingCount] flow and [getPendingUploadCount]
 * can never diverge; [pendingCountFlow] stays replaceable so tests can inject a
 * throwing flow for error-path coverage. Mutations are recorded in [recordedCalls].
 */
class FakeUploadQueueRepository : UploadQueueRepositoryContract {
    private val queue = ArrayDeque<PendingUpload>()
    private val queueCount = MutableStateFlow(0)

    /** Replace with a throwing flow to drive upstream-error paths; defaults to the live queue count. */
    var pendingCountFlow: Flow<Int> = queueCount

    override val pendingCount: Flow<Int> get() = pendingCountFlow

    val recordedCalls = mutableListOf<String>()

    /** Seed the queue; keeps the default [pendingCount] flow in sync. */
    fun enqueue(upload: PendingUpload) {
        queue.addLast(upload)
        queueCount.value = queue.size
    }

    val queued: List<PendingUpload> get() = queue.toList()

    override suspend fun getNextPendingUpload(): PendingUpload? {
        recordedCalls += "getNextPendingUpload"
        return queue.firstOrNull()
    }

    override suspend fun getPendingUploadCount(): Int = queue.size

    override suspend fun markAsUploading(id: Long) {
        recordedCalls += "markAsUploading($id)"
    }

    override suspend fun resetToPending(id: Long) {
        recordedCalls += "resetToPending($id)"
    }

    override suspend fun markAsCompleted(id: Long) {
        recordedCalls += "markAsCompleted($id)"
        queue.removeAll { it.id == id }
        queueCount.value = queue.size
    }

    override suspend fun markAsFailed(id: Long, errorMessage: String?) {
        recordedCalls += "markAsFailed($id, $errorMessage)"
        queue.removeAll { it.id == id }
        queueCount.value = queue.size
    }

    override fun getAllUris(upload: PendingUpload): List<Uri> {
        val uris = mutableListOf(Uri.parse(upload.uri))
        upload.additionalUris.forEach { uris.add(Uri.parse(it)) }
        return uris
    }
}
