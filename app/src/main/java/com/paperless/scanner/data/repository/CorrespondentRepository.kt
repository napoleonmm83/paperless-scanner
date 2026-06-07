package com.paperless.scanner.data.repository

import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.api.models.CreateCorrespondentRequest
import com.paperless.scanner.data.api.models.UpdateCorrespondentRequest
import com.paperless.scanner.data.api.fetchAllPages
import com.paperless.scanner.data.api.withReadTimeout
import com.paperless.scanner.data.api.safeApiCall
import com.paperless.scanner.data.database.dao.CachedCorrespondentDao
import com.paperless.scanner.data.database.dao.PendingChangeDao
import com.paperless.scanner.data.database.mappers.toCachedEntity
import com.paperless.scanner.data.database.mappers.toDomain as toCachedDomain
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.domain.mapper.toDomain
import com.paperless.scanner.domain.model.Correspondent
import com.paperless.scanner.domain.model.Document
import com.paperless.scanner.util.NetworkConfig
import com.paperless.scanner.util.withRetry
import com.paperless.scanner.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

/**
 * CorrespondentRepository - Repository for correspondent management with offline-first architecture.
 *
 * **ARCHITECTURE:**
 * Implements offline-first pattern with Room cache as single source of truth.
 * Uses reactive Flows for automatic UI updates when correspondent data changes.
 *
 * **DATA FLOW:**
 * ```
 * UI ← Flow ← Room Cache ← Repository ← API
 *                ↑_____________________________|
 *                         (sync on refresh)
 * ```
 *
 * **FEATURES:**
 * - Reactive [observeCorrespondents] Flow for automatic UI updates
 * - Offline-first with network sync on demand
 * - Soft delete support for cache management
 * - Used in upload metadata selection and Labels screen
 *
 * **USAGE:**
 * ```kotlin
 * // Reactive observation (preferred)
 * correspondentRepository.observeCorrespondents().collect { correspondents ->
 *     updateUI(correspondents)
 * }
 *
 * // One-shot fetch with optional refresh
 * val correspondents = correspondentRepository.getCorrespondents(forceRefresh = true)
 * ```
 *
 * @property api Paperless-ngx REST API interface
 * @property cachedCorrespondentDao Room DAO for correspondent cache operations
 * @property networkMonitor Network connectivity checker
 *
 * @see PaperlessApi.getCorrespondents For API endpoint
 * @see CachedCorrespondentDao For cache operations
 * @see Correspondent Domain model for correspondents
 *
 * **CACHE & REFRESH POLICY:**
 * - **Backing store:** Room `cached_correspondents` table via [CachedCorrespondentDao]; write-through cache of the `/api/correspondents/` API resource.
 * - **Staleness / TTL:** None — no time-based window; cache persists until an explicit `forceRefresh` or a mutation upserts it.
 * - **Refresh trigger:** Reactive Room Flow ([observeCorrespondents]) auto-emits on any cache change; [getCorrespondents] with `forceRefresh=true` pulls all pages from the API and upserts via `insertAll`; create/update/delete write through immediately.
 * - **Soft-delete:** Yes — [deleteCorrespondent] calls `softDelete` (sets `isDeleted=1`); read queries filter `isDeleted=0`. Hard delete (`deleteByIds`) only during sync orphan pruning.
 * - **Offline / pending changes:** N/A — `pendingChangeDao` injected but unused; no PendingChange/SyncManager wiring. `NetworkMonitor` only gates fetch (offline returns cache or empty list).
 */
class CorrespondentRepository @Inject constructor(
    private val api: PaperlessApi,
    private val cachedCorrespondentDao: CachedCorrespondentDao,
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
    private val correspondentsFlow: Flow<List<Correspondent>> =
        cachedCorrespondentDao.observeCorrespondents()
            .map { cachedList -> cachedList.map { it.toCachedDomain() } }
            .shareIn(
                applicationScope,
                SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000, replayExpirationMillis = 0),
                replay = 1,
            )

    /**
     * Observe all correspondents reactively.
     *
     * **BEST PRACTICE:** Reactive Flow for automatic UI updates; shared across collectors (#50).
     * Emits new list whenever correspondents are added, modified, or deleted.
     *
     * @return [Flow] emitting list of [Correspondent] objects on any cache change
     * @see CachedCorrespondentDao.observeCorrespondents For underlying Room query
     */
    fun observeCorrespondents(): Flow<List<Correspondent>> = correspondentsFlow

    /**
     * One-shot snapshot of the currently-cached correspondents (#50).
     *
     * Reads the DAO directly instead of `observeCorrespondents().first()`, which — now that the
     * observe flow is shared/replayed — could hand back a slightly-stale replayed value. Use this
     * for snapshot consumers (e.g. the suggestion services); use [observeCorrespondents] for
     * reactive streaming.
     */
    suspend fun getCachedCorrespondents(): List<Correspondent> =
        cachedCorrespondentDao.getAllCorrespondents().map { it.toCachedDomain() }

    /**
     * Get all correspondents with optional network refresh.
     *
     * **OFFLINE-FIRST STRATEGY:**
     * 1. If not forceRefresh: Return cached correspondents immediately
     * 2. If forceRefresh and online: Fetch from API, update cache, return fresh data
     * 3. If offline: Return cached data (or empty list if no cache)
     *
     * @param forceRefresh If true, fetches fresh data from server (when online)
     * @return [Result] containing list of [Correspondent] objects, or failure with [PaperlessException]
     * @see observeCorrespondents For reactive alternative (preferred for UI)
     */
    suspend fun getCorrespondents(forceRefresh: Boolean = false): Result<List<Correspondent>> {
        return try {
            // Offline-First: Try cache first unless forceRefresh
            if (!forceRefresh || !networkMonitor.checkOnlineStatus()) {
                val cached = cachedCorrespondentDao.getAllCorrespondents()
                if (cached.isNotEmpty()) {
                    return Result.success(cached.map { it.toCachedDomain() })
                }
            }

            // Network fetch (if online and forceRefresh or cache empty).
            // Walk ALL pages — fetching only page 1 silently truncates correspondent
            // lists longer than the page size (Issue #126).
            if (networkMonitor.checkOnlineStatus()) {
                val correspondents = fetchAllPages { page ->
                    withReadTimeout { api.getCorrespondents(page = page, pageSize = NetworkConfig.DEFAULT_PAGE_SIZE) }
                }
                // Update cache
                val cachedEntities = correspondents.map { it.toCachedEntity() }
                cachedCorrespondentDao.insertAll(cachedEntities)
                Result.success(correspondents.toDomain())
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
     * Create a new correspondent on the server and cache.
     *
     * Immediately updates local cache after successful creation,
     * triggering reactive Flow updates for connected UI components.
     *
     * @param name Correspondent name (e.g., company name, person name)
     * @return [Result] containing created [Correspondent] with server-assigned ID, or failure
     * @see PaperlessApi.createCorrespondent For API endpoint
     */
    suspend fun createCorrespondent(name: String): Result<Correspondent> {
        return try {
            // POST: non-idempotent — no withRetry, would risk duplicate correspondent on 5xx.
            val response = api.createCorrespondent(CreateCorrespondentRequest(name = name))
            val domainCorrespondent = response.toDomain()

            // Insert into cache to trigger reactive Flow update immediately
            cachedCorrespondentDao.insert(response.toCachedEntity())

            Result.success(domainCorrespondent)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    /**
     * Update an existing correspondent on the server and cache.
     *
     * Immediately updates local cache after successful update,
     * triggering reactive Flow updates for connected UI components.
     *
     * @param id Correspondent ID to update
     * @param name New correspondent name
     * @return [Result] containing updated [Correspondent], or failure with [PaperlessException]
     * @see PaperlessApi.updateCorrespondent For API endpoint
     */
    suspend fun updateCorrespondent(id: Int, name: String): Result<Correspondent> {
        return try {
            val response = withRetry { api.updateCorrespondent(id, UpdateCorrespondentRequest(name = name)) }
            val domainCorrespondent = response.toDomain()

            // Update cache to trigger reactive Flow update immediately
            cachedCorrespondentDao.insert(response.toCachedEntity())

            Result.success(domainCorrespondent)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    /**
     * Delete a correspondent from the server and cache.
     *
     * Uses soft delete in cache to trigger reactive Flow updates
     * while maintaining data integrity for pending operations.
     *
     * @param id Correspondent ID to delete
     * @return [Result] with Unit on success, or failure with [PaperlessException]
     * @see PaperlessApi.deleteCorrespondent For API endpoint
     * @see CachedCorrespondentDao.softDelete For cache soft delete
     */
    suspend fun deleteCorrespondent(id: Int): Result<Unit> {
        return try {
            withRetry { api.deleteCorrespondent(id) }

            // Soft delete from cache to trigger reactive Flow update immediately
            cachedCorrespondentDao.softDelete(id)

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    /**
     * Get all documents assigned to a specific correspondent.
     *
     * Fetches directly from server for up-to-date document list.
     * Used in Labels screen detail view.
     *
     * @param correspondentId Correspondent ID to filter documents by
     * @return [Result] containing list of [Document] objects with this correspondent
     * @see PaperlessApi.getDocuments For underlying API call
     */
    suspend fun getDocumentsForCorrespondent(correspondentId: Int): Result<List<Document>> = safeApiCall {
        api.getDocuments(
            correspondentId = correspondentId,
            pageSize = 100
        ).results.toDomain()
    }
}
