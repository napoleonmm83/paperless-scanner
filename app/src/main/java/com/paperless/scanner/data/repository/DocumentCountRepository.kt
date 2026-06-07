package com.paperless.scanner.data.repository

import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.domain.error.PaperlessException
import com.paperless.scanner.data.api.safeApiCall
import com.paperless.scanner.data.database.DocumentFilterQueryBuilder
import com.paperless.scanner.data.database.dao.CachedDocumentDao
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.domain.model.DocumentFilter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * Phase 2.2 of #51 — extracted from DocumentRepository.
 *
 * Owns the four document-count methods. Two reactive Flow methods are unified
 * internally via a private CountFilter sealed class; the two suspend methods
 * keep their distinct cache/API semantics.
 *
 * **CACHE & REFRESH POLICY:**
 * - **Backing store:** Room `CachedDocumentDao` count projections over the cached-documents table; `getUntaggedCount()` is API-only (never reads cache).
 * - **Staleness / TTL:** No time-based TTL; freshness gated only by `getDocumentCount`'s `forceRefresh` flag + non-empty (`count > 0`) cache heuristic.
 * - **Refresh trigger:** Flow methods auto-emit on Room change (reactive); `getDocumentCount` pulls from API (`getDocuments(page=1,pageSize=1).count`) when forced or cache empty+online; `getUntaggedCount` always hits API.
 * - **Soft-delete:** N/A — read-only repo, never deletes.
 * - **Offline / pending changes:** No PendingChange/SyncManager; `getDocumentCount` falls back to cached count when `NetworkMonitor` reports offline (this repo only reads counts, never upserts the cache).
 */
@Singleton
class DocumentCountRepository @Inject constructor(
    private val api: PaperlessApi,
    private val cachedDocumentDao: CachedDocumentDao,
    private val networkMonitor: NetworkMonitor,
) {

    private sealed class CountFilter {
        data class WithFilter(
            val searchQuery: String?,
            val filter: DocumentFilter,
        ) : CountFilter()
        data object Untagged : CountFilter()
    }

    private fun observeCountInternal(countFilter: CountFilter): Flow<Int> = when (countFilter) {
        is CountFilter.WithFilter -> {
            val query = DocumentFilterQueryBuilder.buildCountQuery(
                searchQuery = countFilter.searchQuery,
                filter = countFilter.filter,
            )
            cachedDocumentDao.getCountWithFilter(query)
        }
        is CountFilter.Untagged -> cachedDocumentDao.observeUntaggedCount()
    }

    fun observeCountWithFilter(
        searchQuery: String? = null,
        filter: DocumentFilter = DocumentFilter.empty(),
    ): Flow<Int> = observeCountInternal(CountFilter.WithFilter(searchQuery, filter))

    fun observeUntaggedDocumentsCount(): Flow<Int> =
        observeCountInternal(CountFilter.Untagged)

    suspend fun getDocumentCount(forceRefresh: Boolean = false): Result<Int> {
        return try {
            if (!forceRefresh) {
                val count = cachedDocumentDao.getCount()
                if (count > 0 || !networkMonitor.checkOnlineStatus()) {
                    return Result.success(count)
                }
            }
            if (networkMonitor.checkOnlineStatus()) {
                safeApiCall {
                    api.getDocuments(page = 1, pageSize = 1).count
                }
            } else {
                Result.success(cachedDocumentDao.getCount())
            }
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    suspend fun getUntaggedCount(): Result<Int> = safeApiCall {
        api.getDocuments(
            page = 1,
            pageSize = 1,
            tagsIsNull = true,
        ).count
    }
}
