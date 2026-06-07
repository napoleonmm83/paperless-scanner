package com.paperless.scanner.data.repository

import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.api.models.CreateDocumentTypeRequest
import com.paperless.scanner.data.api.models.UpdateDocumentTypeRequest
import com.paperless.scanner.data.api.fetchAllPages
import com.paperless.scanner.data.api.withReadTimeout
import com.paperless.scanner.data.api.safeApiCall
import com.paperless.scanner.data.database.dao.CachedDocumentTypeDao
import com.paperless.scanner.data.database.dao.PendingChangeDao
import com.paperless.scanner.data.database.mappers.toCachedEntity
import com.paperless.scanner.data.database.mappers.toDomain as toCachedDomain
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.domain.mapper.toDomain
import com.paperless.scanner.domain.model.Document
import com.paperless.scanner.domain.model.DocumentType
import com.paperless.scanner.util.NetworkConfig
import com.paperless.scanner.util.withRetry
import kotlin.coroutines.cancellation.CancellationException
import com.paperless.scanner.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject

/**
 * Repository for Paperless-ngx document types: offline-first reads plus create/update/delete,
 * backed by a Room write-through cache of the `/api/document_types/` resource.
 *
 * **CACHE & REFRESH POLICY:**
 * - **Backing store:** Room `cached_document_types` table via [CachedDocumentTypeDao]; write-through cache of the `/api/document_types/` API resource.
 * - **Staleness / TTL:** N/A — no TTL; offline-first + reactive (cache served unless `forceRefresh` and online).
 * - **Refresh trigger:** Reactive Room Flow ([observeDocumentTypes]) auto-emits on DB change; `getDocumentTypes(forceRefresh=true)` pulls all pages via `fetchAllPages` and upserts; create/update/delete mutate cache directly.
 * - **Soft-delete:** Yes — `deleteDocumentType` calls `softDelete` (isDeleted=1, filtered from reads); hard delete only for sync orphan cleanup.
 * - **Offline / pending changes:** N/A — `pendingChangeDao` injected but unused; no PendingChange/delta tracking here.
 */
class DocumentTypeRepository @Inject constructor(
    private val api: PaperlessApi,
    private val cachedDocumentTypeDao: CachedDocumentTypeDao,
    private val pendingChangeDao: PendingChangeDao,
    private val networkMonitor: NetworkMonitor,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {
    // #50: share the cold Room flow across collectors so N observers trigger ONE upstream
    // query instead of N. Created once (per singleton) and reused. WhileSubscribed tears the
    // upstream down 5s after the last collector unsubscribes (no leak); replay = 1 gives a new
    // *concurrent* collector the latest list immediately. replayExpirationMillis = 0 clears the
    // replay cache the moment the upstream stops, so a one-shot first() caller (e.g. the
    // suggestion services) after a no-subscriber gap never gets a stale snapshot — it restarts
    // the upstream and reads the current Room state.
    private val documentTypesFlow: Flow<List<DocumentType>> =
        cachedDocumentTypeDao.observeDocumentTypes()
            .map { cachedList -> cachedList.map { it.toCachedDomain() } }
            .shareIn(
                applicationScope,
                SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
                replay = 1,
            )

    /**
     * BEST PRACTICE: Reactive Flow for automatic UI updates; shared across collectors (#50).
     * Observes cached document types and automatically notifies when data changes.
     */
    fun observeDocumentTypes(): Flow<List<DocumentType>> = documentTypesFlow

    /**
     * One-shot snapshot of the currently-cached document types (#50).
     *
     * Reads the DAO directly instead of `observeDocumentTypes().first()`, which — now that the
     * observe flow is shared/replayed — could hand back a slightly-stale replayed value. Use this
     * for snapshot consumers (e.g. the suggestion services); use [observeDocumentTypes] for
     * reactive streaming.
     */
    suspend fun getCachedDocumentTypes(): List<DocumentType> =
        cachedDocumentTypeDao.getAllDocumentTypes().map { it.toCachedDomain() }

    suspend fun getDocumentTypes(forceRefresh: Boolean = false): Result<List<DocumentType>> {
        return try {
            // Offline-First: Try cache first unless forceRefresh
            if (!forceRefresh || !networkMonitor.checkOnlineStatus()) {
                val cached = cachedDocumentTypeDao.getAllDocumentTypes()
                if (cached.isNotEmpty()) {
                    return Result.success(cached.map { it.toCachedDomain() })
                }
            }

            // Network fetch (if online and forceRefresh or cache empty).
            // Walk ALL pages — fetching only page 1 silently truncates document-type
            // lists longer than the page size (Issue #126).
            if (networkMonitor.checkOnlineStatus()) {
                val types = fetchAllPages { page ->
                    withReadTimeout { api.getDocumentTypes(page = page, pageSize = NetworkConfig.DEFAULT_PAGE_SIZE) }
                }
                // Update cache
                val cachedEntities = types.map { it.toCachedEntity() }
                cachedDocumentTypeDao.insertAll(cachedEntities)
                Result.success(types.toDomain())
            } else {
                // Offline, no cache
                Result.success(emptyList())
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    /**
     * Creates a new document type.
     * Updates cache immediately to trigger reactive Flow.
     */
    suspend fun createDocumentType(name: String): Result<DocumentType> {
        return try {
            // POST: non-idempotent — no withRetry, would risk duplicate document type on 5xx.
            val response = api.createDocumentType(CreateDocumentTypeRequest(name = name))
            val domainDocumentType = response.toDomain()

            // Insert into cache to trigger reactive Flow update immediately
            cachedDocumentTypeDao.insert(response.toCachedEntity())

            Result.success(domainDocumentType)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    /**
     * Updates an existing document type.
     * Updates cache immediately to trigger reactive Flow.
     */
    suspend fun updateDocumentType(id: Int, name: String): Result<DocumentType> {
        return try {
            val response = withRetry { api.updateDocumentType(id, UpdateDocumentTypeRequest(name = name)) }
            val domainDocumentType = response.toDomain()

            // Update cache to trigger reactive Flow update immediately
            cachedDocumentTypeDao.insert(response.toCachedEntity())

            Result.success(domainDocumentType)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    /**
     * Deletes a document type.
     * Removes from cache to trigger reactive Flow.
     */
    suspend fun deleteDocumentType(id: Int): Result<Unit> {
        return try {
            withRetry { api.deleteDocumentType(id) }

            // Delete from cache to trigger reactive Flow update immediately
            cachedDocumentTypeDao.softDelete(id)

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    /**
     * Gets all documents for a specific document type.
     * Used for detail view in Labels Screen.
     */
    suspend fun getDocumentsForDocumentType(documentTypeId: Int): Result<List<Document>> = safeApiCall {
        api.getDocuments(
            documentTypeId = documentTypeId,
            pageSize = 100
        ).results.toDomain()
    }
}
