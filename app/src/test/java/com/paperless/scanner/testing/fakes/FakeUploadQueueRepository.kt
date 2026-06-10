package com.paperless.scanner.testing.fakes

import android.net.Uri
import com.paperless.scanner.data.database.PendingUpload
import com.paperless.scanner.data.database.UploadStatus
import com.paperless.scanner.data.repository.UploadQueueRepositoryContract
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Typed fake for [UploadQueueRepositoryContract] (#239/#321/#202). The queue is
 * encapsulated (seed via [enqueue]) so the default [pendingCount] flow and
 * [getPendingUploadCount] can never diverge; [pendingCountFlow] stays replaceable so
 * tests can inject a throwing flow for error-path coverage. Mutations are recorded in
 * [recordedCalls].
 *
 * Status semantics: rows carry an [UploadStatus]; [getNextPendingUpload] returns the
 * first PENDING row. Deliberate deviation from the production DAO (which also
 * re-returns FAILED rows, relying on the worker's processedIds guard to break the
 * drain loop): the worker tests encode a one-pass drain order; the guard itself is
 * covered explicitly via [stickyNextUpload].
 */
class FakeUploadQueueRepository : UploadQueueRepositoryContract {
    private class Row(val upload: PendingUpload, var status: UploadStatus)

    private val rows = mutableListOf<Row>()
    private val queueCount = MutableStateFlow(0)

    /** Replace with a throwing flow to drive upstream-error paths; defaults to the live queue count. */
    var pendingCountFlow: Flow<Int> = queueCount

    override val pendingCount: Flow<Int> get() = pendingCountFlow

    val recordedCalls = mutableListOf<String>()

    /**
     * Simulates a buggy queue: [getNextPendingUpload] keeps returning the first row
     * regardless of status and [markAsCompleted] does NOT remove it — exercises the
     * worker's processedIds loop-break guard.
     */
    var stickyNextUpload = false

    /** One-shot failures: each [markAsCompleted] call throws the next entry (FIFO) before mutating. */
    val markAsCompletedFailures = ArrayDeque<Throwable>()

    /** Set to make [getPendingUploadCount] throw (e.g. simulate a closed DB). */
    var getPendingUploadCountException: Throwable? = null

    /** Seed the queue; keeps the default [pendingCount] flow in sync. */
    fun enqueue(upload: PendingUpload) {
        rows += Row(upload, upload.status)
        syncCount()
    }

    val queued: List<PendingUpload> get() = rows.map { it.upload }

    fun statusOf(id: Long): UploadStatus? = rows.find { it.upload.id == id }?.status

    private fun syncCount() {
        queueCount.value = rows.count { it.status == UploadStatus.PENDING || it.status == UploadStatus.FAILED }
    }

    override suspend fun getNextPendingUpload(): PendingUpload? {
        recordedCalls += "getNextPendingUpload"
        if (stickyNextUpload) return rows.firstOrNull()?.upload
        return rows.firstOrNull { it.status == UploadStatus.PENDING }?.upload
    }

    override suspend fun getPendingUploadCount(): Int {
        recordedCalls += "getPendingUploadCount"
        getPendingUploadCountException?.let { throw it }
        return rows.count { it.status == UploadStatus.PENDING || it.status == UploadStatus.FAILED }
    }

    override suspend fun markAsUploading(id: Long) {
        recordedCalls += "markAsUploading($id)"
        rows.find { it.upload.id == id }?.status = UploadStatus.UPLOADING
        syncCount()
    }

    override suspend fun resetToPending(id: Long) {
        recordedCalls += "resetToPending($id)"
        rows.find { it.upload.id == id }?.status = UploadStatus.PENDING
        syncCount()
    }

    override suspend fun markAsCompleted(id: Long) {
        recordedCalls += "markAsCompleted($id)"
        markAsCompletedFailures.removeFirstOrNull()?.let { throw it }
        if (!stickyNextUpload) {
            rows.removeAll { it.upload.id == id }
        }
        syncCount()
    }

    override suspend fun markAsFailed(id: Long, errorMessage: String?) {
        recordedCalls += "markAsFailed($id, $errorMessage)"
        rows.find { it.upload.id == id }?.status = UploadStatus.FAILED
        syncCount()
    }

    override fun getAllUris(upload: PendingUpload): List<Uri> {
        val uris = mutableListOf(Uri.parse(upload.uri))
        upload.additionalUris.forEach { uris.add(Uri.parse(it)) }
        return uris
    }
}
