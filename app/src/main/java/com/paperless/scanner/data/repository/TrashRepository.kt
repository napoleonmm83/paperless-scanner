package com.paperless.scanner.data.repository

import android.content.Context
import android.util.Log
import com.paperless.scanner.R
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.api.models.AcknowledgeTasksRequest
import com.paperless.scanner.data.api.models.TrashBulkActionRequest
import com.paperless.scanner.data.database.dao.CachedDocumentDao
import com.paperless.scanner.data.database.dao.CachedTaskDao
import com.paperless.scanner.data.database.mappers.toCachedEntity
import com.paperless.scanner.data.database.mappers.toTrashedDocument
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.domain.mapper.toDomain
import com.paperless.scanner.domain.model.DocumentsResponse
import com.paperless.scanner.domain.model.TrashedDocument
import com.paperless.scanner.util.withResponseRetry
import com.paperless.scanner.util.withRetry
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody

/**
 * Phase 3.1 of #51 — extracted from DocumentRepository.
 *
 * Owns trash-related operations: soft-delete (deleteDocument), trash reads
 * (observe* + getTrashDocuments), restore + permanent-delete (single delegates
 * to bulk), and maintenance helpers.
 *
 * Phase 3.3 (#169): the three offline-queue branches (deleteDocument,
 * restoreDocuments, permanentlyDeleteDocuments) are delegated to
 * [DocumentSyncRepository.executeOrQueue], which centralizes the
 * serverHealth-snapshot + IOException-fallback pattern.
 */
@Singleton
class TrashRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: PaperlessApi,
    private val cachedDocumentDao: CachedDocumentDao,
    private val cachedTaskDao: CachedTaskDao,                  // for deleteDocument cascade only
    private val networkMonitor: NetworkMonitor,
    private val sync: DocumentSyncRepository,
) {

    companion object {
        private const val TAG = "TrashRepository"
        private const val ERROR_BODY_LOG_LIMIT = 200
    }

    /**
     * Read [Response.errorBody] safely and produce a log-friendly snippet:
     * truncated to [ERROR_BODY_LOG_LIMIT] chars and with newlines escaped so a
     * single log line stays grep-able. Server error responses can echo
     * user-submitted data, so we never dump them in full.
     */
    private fun safeErrorBodySnippet(response: retrofit2.Response<*>): String? {
        return try {
            val raw = response.errorBody()?.string() ?: return null
            raw.replace("\n", "\\n").take(ERROR_BODY_LOG_LIMIT)
        } catch (_: Exception) {
            null
        }
    }

    suspend fun deleteDocument(documentId: Int): Result<Unit> = sync.executeOrQueue(
        online = {
            // CASCADE STEP 1: collect unack'd task ids BEFORE optimistic delete
            val tasks = cachedTaskDao.getAllTasks()
                .filter { it.relatedDocument == documentId.toString() && !it.acknowledged }
            val taskIds = tasks.map { it.id }

            // OPTIMISTIC UI: softDelete BEFORE API call (Gmail-swipe animation)
            val deletedAt = System.currentTimeMillis()
            cachedDocumentDao.softDelete(documentId, deletedAt = deletedAt)
            cachedTaskDao.acknowledgeTasksForDocument(documentId.toString())

            try {
                val response = withResponseRetry { api.deleteDocument(documentId) }
                when {
                    response.isSuccessful -> {
                        if (taskIds.isNotEmpty()) {
                            try {
                                api.acknowledgeTasks(AcknowledgeTasksRequest(taskIds))
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to acknowledge tasks on server: ${e.message}")
                            }
                        }
                        Unit
                    }
                    response.code() == 404 -> {
                        // Already deleted on server — likely a previous attempt
                        // committed but the response was lost in transit. Local
                        // state matches reality; do NOT roll back.
                        Log.i(TAG, "deleteDocument returned 404 — treating as already-deleted, no rollback")
                        Unit
                    }
                    else -> {
                        // 4xx (other than 404): throw without rollback here —
                        // the catch (HttpException) below is the single rollback
                        // point so 5xx-after-withResponseRetry-exhaust gets the
                        // same treatment.
                        val errorBodySnippet = safeErrorBodySnippet(response)
                        Log.e(TAG, "deleteDocument failed: HTTP ${response.code()}, body: $errorBodySnippet")
                        // Re-read the full body for HttpException construction
                        // (callers like PaperlessException.from inspect it via
                        // response().errorBody()); the snippet above is just for
                        // the log line.
                        val errorBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
                        throw retrofit2.HttpException(
                            retrofit2.Response.error<Unit>(
                                response.code(),
                                (errorBody ?: "{}").toResponseBody("application/json".toMediaTypeOrNull()),
                            )
                        )
                    }
                }
            } catch (e: retrofit2.HttpException) {
                // Single rollback point: covers both 4xx thrown from the else
                // branch above and 5xx thrown by withResponseRetry after
                // exhausting retries. restoreDocument is idempotent (UPDATE
                // SET isDeleted = 0), so calling it for a doc already in the
                // restored state is a no-op.
                Log.w(TAG, "deleteDocument API failure (HTTP ${e.code()}), rolling back optimistic delete")
                cachedDocumentDao.restoreDocument(documentId)
                throw e
            } catch (e: java.io.IOException) {
                // Mid-flight network drop: do NOT roll back here. executeOrQueue
                // will catch this IOException and run the offline branch, which
                // re-applies softDelete + ack via queueDocumentDelete. Rolling back
                // would cause a brief UI flicker (deleted → restored → deleted)
                // as Flow observers see two opposing DB writes.
                throw e
            } catch (e: CancellationException) {
                // Coroutine cancelled mid-flight (e.g. ViewModel scope went
                // away). Propagate without rollback — the user did not ask for
                // an undo, and rollback could race with a re-issue from the
                // resumed scope.
                throw e
            } catch (e: Exception) {
                // ROLLBACK for non-network exceptions (timeout, etc.) where
                // executeOrQueue will NOT trigger offline fallback.
                Log.w(TAG, "deleteDocument API exception, rolling back: ${e.message}")
                cachedDocumentDao.restoreDocument(documentId)
                throw e
            }
        },
        offlineQueueAndOptimistic = {
            sync.queueDocumentDelete(documentId)
            cachedTaskDao.acknowledgeTasksForDocument(documentId.toString())
            cachedDocumentDao.softDelete(documentId, deletedAt = System.currentTimeMillis())
        },
    )

    fun observeTrashedDocuments(): Flow<List<TrashedDocument>> {
        return cachedDocumentDao.observeDeletedDocuments()
            .map { list -> list.map { it.toTrashedDocument() } }
    }

    fun observeTrashedDocumentsCount(): Flow<Int> {
        return cachedDocumentDao.observeDeletedCount()
    }

    fun observeOldestDeletedTimestamp(): Flow<Long?> {
        return cachedDocumentDao.getOldestDeletedTimestamp()
    }

    suspend fun getTrashDocuments(
        page: Int = 1,
        pageSize: Int = 25,
    ): Result<DocumentsResponse> {
        return try {
            if (networkMonitor.checkOnlineStatus()) {
                val response = withRetry { api.getTrash(page = page, pageSize = pageSize) }

                val cachedEntities = response.results.map { doc ->
                    val deletionTimestamp = try {
                        java.time.Instant.parse(doc.modified).toEpochMilli()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse modified date: ${doc.modified}", e)
                        System.currentTimeMillis()
                    }
                    doc.toCachedEntity().copy(
                        isDeleted = true,
                        deletedAt = deletionTimestamp,
                    )
                }
                cachedDocumentDao.insertAll(cachedEntities)

                Result.success(response.toDomain())
            } else {
                Result.failure(
                    PaperlessException.NetworkError(IOException(context.getString(R.string.error_offline)))
                )
            }
        } catch (e: retrofit2.HttpException) {
            Result.failure(PaperlessException.fromHttpCode(e.code(), e.message()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    suspend fun restoreDocument(documentId: Int): Result<Unit> =
        restoreDocuments(listOf(documentId))

    suspend fun restoreDocuments(documentIds: List<Int>): Result<Unit> = sync.executeOrQueue(
        online = {
            val request = TrashBulkActionRequest(documents = documentIds, action = "restore")
            val response = withResponseRetry { api.trashBulkAction(request) }
            if (response.isSuccessful) {
                cachedDocumentDao.restoreDocuments(documentIds)
                Unit
            } else {
                Log.e(TAG, "restoreDocuments failed: HTTP ${response.code()}, body: ${safeErrorBodySnippet(response)}")
                val errorBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
                throw retrofit2.HttpException(
                    retrofit2.Response.error<Unit>(
                        response.code(),
                        (errorBody ?: "{}").toResponseBody("application/json".toMediaTypeOrNull()),
                    )
                )
            }
        },
        offlineQueueAndOptimistic = {
            sync.queueTrashAction(documentIds, DocumentSyncRepository.TrashAction.RESTORE)
            cachedDocumentDao.restoreDocuments(documentIds)
        },
    )

    suspend fun permanentlyDeleteDocument(documentId: Int): Result<Unit> =
        permanentlyDeleteDocuments(listOf(documentId))

    suspend fun permanentlyDeleteDocuments(documentIds: List<Int>): Result<Unit> = sync.executeOrQueue(
        online = {
            val request = TrashBulkActionRequest(documents = documentIds, action = "empty")
            val response = withResponseRetry { api.trashBulkAction(request) }
            if (response.isSuccessful) {
                cachedDocumentDao.deleteByIds(documentIds)
                Unit
            } else {
                Log.e(TAG, "permanentlyDeleteDocuments failed: HTTP ${response.code()}, body: ${safeErrorBodySnippet(response)}")
                val errorBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
                throw retrofit2.HttpException(
                    retrofit2.Response.error<Unit>(
                        response.code(),
                        (errorBody ?: "{}").toResponseBody("application/json".toMediaTypeOrNull()),
                    )
                )
            }
        },
        offlineQueueAndOptimistic = {
            sync.queueTrashAction(documentIds, DocumentSyncRepository.TrashAction.PERMANENT_DELETE)
            cachedDocumentDao.deleteByIds(documentIds)
        },
    )

    suspend fun getOldDeletedDocumentIds(retentionDays: Int = 30): Result<List<Int>> {
        return try {
            val cutoffTime = System.currentTimeMillis() - (retentionDays * 24 * 60 * 60 * 1000L)
            val ids = cachedDocumentDao.getOldDeletedDocumentIds(cutoffTime)
            Result.success(ids)
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    suspend fun cleanupOrphanedTrashDocs(serverTrashIds: Set<Int>) {
        val localDeletedIds = cachedDocumentDao.getDeletedIds().toSet()
        val orphanedIds = localDeletedIds - serverTrashIds
        if (orphanedIds.isNotEmpty()) {
            cachedDocumentDao.deleteByIds(orphanedIds.toList())
            Log.d(TAG, "Cleaned up ${orphanedIds.size} orphaned trash docs: $orphanedIds")
        }
    }
}
