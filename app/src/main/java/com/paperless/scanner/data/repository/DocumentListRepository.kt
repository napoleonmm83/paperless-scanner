package com.paperless.scanner.data.repository

import android.content.Context
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.paperless.scanner.R
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.domain.error.PaperlessException
import com.paperless.scanner.data.database.DocumentFilterQueryBuilder
import com.paperless.scanner.data.database.dao.CachedDocumentDao
import com.paperless.scanner.data.database.mappers.toCachedEntity
import com.paperless.scanner.data.database.mappers.toDomain as toCachedDomain
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.domain.mapper.toDomain
import com.paperless.scanner.domain.model.Document
import com.paperless.scanner.domain.model.DocumentFilter
import com.paperless.scanner.domain.model.DocumentsResponse
import com.paperless.scanner.util.withRetry
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Phase 2.1 of #51 — extracted from DocumentRepository.
 *
 * Owns list / paging operations: observeDocuments, getDocumentsPaged,
 * getDocuments, getUntaggedDocuments.
 *
 * **CACHE & REFRESH POLICY:**
 * - **Backing store:** Room [CachedDocumentDao] (cached_documents) — write-through cache of the `/api/documents/` list resource.
 * - **Staleness / TTL:** No TTL — cached rows are served regardless of age whenever `!forceRefresh` or offline.
 * - **Refresh trigger:** Reactive Room Flow auto-emits on DB change (observeDocuments/getDocumentsPaged); explicit getDocuments(forceRefresh=true) pulls API (withRetry) and insertAll-upserts (online only).
 * - **Soft-delete:** N/A — repo never deletes.
 * - **Offline / pending changes:** No PendingChange/SyncManager; only gates on networkMonitor (serves cache offline, else NetworkError when cache empty).
 */
@Singleton
class DocumentListRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: PaperlessApi,
    private val cachedDocumentDao: CachedDocumentDao,
    private val networkMonitor: NetworkMonitor,
) {

    fun observeDocuments(page: Int = 1, pageSize: Int = 25): Flow<List<Document>> {
        return cachedDocumentDao.observeDocuments(
            limit = pageSize,
            offset = (page - 1) * pageSize,
        ).map { cachedList -> cachedList.map { it.toCachedDomain() } }
    }

    suspend fun getUntaggedDocuments(): Result<List<Document>> {
        return try {
            val cachedDocs = cachedDocumentDao.getUntaggedDocuments()
            Result.success(cachedDocs.map { it.toCachedDomain() })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getDocumentsPaged(
        searchQuery: String? = null,
        filter: DocumentFilter = DocumentFilter.empty(),
    ): Flow<PagingData<Document>> {
        return Pager(
            config = PagingConfig(
                pageSize = 100,
                maxSize = 500,
                enablePlaceholders = false,
            ),
            pagingSourceFactory = {
                val query = DocumentFilterQueryBuilder.buildPagingQuery(
                    searchQuery = searchQuery,
                    filter = filter,
                )
                cachedDocumentDao.getDocumentsPagingSource(query)
            },
        ).flow.map { pagingData -> pagingData.map { it.toCachedDomain() } }
    }

    suspend fun getDocuments(
        page: Int = 1,
        pageSize: Int = 25,
        query: String? = null,
        tagIds: List<Int>? = null,
        correspondentId: Int? = null,
        documentTypeId: Int? = null,
        ordering: String = "-created",
        forceRefresh: Boolean = false,
    ): Result<DocumentsResponse> {
        return try {
            // Cache path only serves unfiltered requests — the DAO call below ignores
            // query/tagIds/correspondent/documentType, so a filtered request must
            // bypass it (otherwise the user gets unrelated rows + an unfiltered count).
            val isUnfilteredRequest = query == null && tagIds.isNullOrEmpty() &&
                correspondentId == null && documentTypeId == null
            if (isUnfilteredRequest && (!forceRefresh || !networkMonitor.checkOnlineStatus())) {
                val cachedDocs = cachedDocumentDao.getDocuments(
                    limit = pageSize,
                    offset = (page - 1) * pageSize,
                )
                if (cachedDocs.isNotEmpty()) {
                    val totalCount = cachedDocumentDao.getCount()
                    val domainDocs = cachedDocs.map { it.toCachedDomain() }
                    return Result.success(
                        DocumentsResponse(
                            count = totalCount,
                            next = if ((page * pageSize) < totalCount) "next" else null,
                            previous = if (page > 1) "prev" else null,
                            results = domainDocs,
                        )
                    )
                }
            }
            if (networkMonitor.checkOnlineStatus()) {
                val tagIdsString = tagIds?.takeIf { it.isNotEmpty() }?.joinToString(",")
                val response = withRetry {
                    api.getDocuments(
                        page = page,
                        pageSize = pageSize,
                        query = query,
                        tagIds = tagIdsString,
                        correspondentId = correspondentId,
                        documentTypeId = documentTypeId,
                        ordering = ordering,
                    )
                }
                val cachedEntities = response.results.map { it.toCachedEntity() }
                cachedDocumentDao.insertAll(cachedEntities)
                Result.success(response.toDomain())
            } else {
                Result.failure(
                    PaperlessException.NetworkError(
                        IOException(context.getString(R.string.error_offline_no_cache))
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }
}
