package com.paperless.scanner.data.repository

import com.google.gson.Gson
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.database.dao.PendingChangeDao
import com.paperless.scanner.data.database.entities.PendingChange
import com.paperless.scanner.data.health.ServerHealthMonitor
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Phase 3.3 of #51 — centralizes the offline-queue + serverHealth pattern that
 * previously lived inline in DocumentMetadataRepository.updateDocument and three
 * TrashRepository methods (deleteDocument, restoreDocuments, permanentlyDeleteDocuments).
 *
 * Fixes two production bugs as part of the centralization:
 * 1. JSON injection in updateDocument PendingChange payload — buildString
 *    with raw "$it" interpolation produced invalid JSON for titles containing
 *    embedded `"`. queueDocumentUpdate uses gson.toJson instead.
 * 2. TOCTOU race on serverHealthMonitor.isServerReachable — was read inline
 *    without IOException fallback; if state flipped between check and API call,
 *    the user's edit was lost rather than queued. executeOrQueue snapshots
 *    once and recovers via IOException-fallback.
 *
 * **CACHE & REFRESH POLICY:**
 * - **Backing store:** Room `PendingChangeDao` (`PendingChange` entity) — a write-only offline mutation queue, not a read cache.
 * - **Staleness / TTL:** N/A — no TTL; online/offline decided per-call from `serverHealthMonitor.isServerReachable.value` snapshot.
 * - **Refresh trigger:** N/A — write-only; queued `PendingChange` rows are drained later by SyncManager (no read/fetch path here).
 * - **Soft-delete:** Soft — `queueDocumentDelete` queues document→trash; `queueTrashAction` queues RESTORE/PERMANENT_DELETE.
 * - **Offline / pending changes:** Core purpose — `executeOrQueue` runs online or, on offline/IOException, inserts optimistic `PendingChange`s.
 */
@Singleton
class DocumentSyncRepository @Inject constructor(
    private val pendingChangeDao: PendingChangeDao,
    private val serverHealthMonitor: ServerHealthMonitor,
    private val gson: Gson,
) {

    /**
     * Snapshot serverHealth once, run [online]; on IOException fall back to
     * [offlineQueueAndOptimistic]. HttpException (4xx/5xx) does NOT trigger
     * fallback — the server responded, queueing would just re-fail.
     */
    suspend fun <T> executeOrQueue(
        online: suspend () -> T,
        offlineQueueAndOptimistic: suspend () -> T,
    ): Result<T> {
        val isOnline = serverHealthMonitor.isServerReachable.value
        return try {
            if (isOnline) {
                try {
                    Result.success(online())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: IOException) {
                    Result.success(offlineQueueAndOptimistic())
                }
            } else {
                Result.success(offlineQueueAndOptimistic())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // PaperlessException.from(e) handles HttpException with errorBody extraction,
            // preserving the pre-Phase-3.3 errorBody-fidelity for ClientError messages.
            Result.failure(PaperlessException.from(e))
        }
    }

    /**
     * Queue a document-update for offline sync. Uses gson to serialize the
     * payload, fixing the JSON-injection bug that affected the previous
     * buildString implementation. Property names match SyncManager.pushDocumentChange's
     * expected keys (camelCase: title, tags, correspondent, documentType,
     * archiveSerialNumber, created). Null fields are omitted by Gson default.
     */
    suspend fun queueDocumentUpdate(documentId: Int, payload: DocumentUpdatePayload) {
        pendingChangeDao.insert(
            PendingChange(
                entityType = "document",
                entityId = documentId,
                changeType = "update",
                changeData = gson.toJson(payload),
            )
        )
    }

    /** Queue a soft-delete (document → trash). */
    suspend fun queueDocumentDelete(documentId: Int) {
        pendingChangeDao.insert(
            PendingChange(
                entityType = "document",
                entityId = documentId,
                changeType = "delete",
                changeData = "{}",
            )
        )
    }

    /** Queue a bulk trash action (RESTORE or PERMANENT_DELETE). One PendingChange per id. */
    suspend fun queueTrashAction(documentIds: List<Int>, action: TrashAction) {
        documentIds.forEach { docId ->
            pendingChangeDao.insert(
                PendingChange(
                    entityType = "trash",
                    entityId = docId,
                    changeType = action.changeType,
                    changeData = "{}",
                )
            )
        }
    }

    enum class TrashAction(val changeType: String) {
        RESTORE("restore"),
        PERMANENT_DELETE("delete"),
    }

    data class DocumentUpdatePayload(
        val title: String? = null,
        val tags: List<Int>? = null,
        val correspondent: Int? = null,
        val documentType: Int? = null,
        val archiveSerialNumber: Int? = null,
        val created: String? = null,
    )
}
