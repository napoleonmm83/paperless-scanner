package com.paperless.scanner.data.repository

import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.api.models.CreateTagRequest
import com.paperless.scanner.data.api.models.UpdateTagRequest
import com.paperless.scanner.data.api.safeApiCall
import com.paperless.scanner.data.api.safeApiResponse
import com.paperless.scanner.data.database.dao.CachedTagDao
import com.paperless.scanner.data.database.dao.PendingChangeDao
import com.paperless.scanner.data.database.mappers.toCachedEntity
import com.paperless.scanner.data.database.mappers.toDomain as toCachedDomain
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.domain.mapper.toDomain
import com.paperless.scanner.domain.model.Document
import com.paperless.scanner.domain.model.Tag
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class TagRepository @Inject constructor(
    private val api: PaperlessApi,
    private val cachedTagDao: CachedTagDao,
    private val pendingChangeDao: PendingChangeDao,
    private val networkMonitor: NetworkMonitor
) {
    /**
     * BEST PRACTICE: Reactive Flow for automatic UI updates.
     * Observes cached tags and automatically notifies when tags are added/modified/deleted.
     */
    fun observeTags(): Flow<List<Tag>> {
        return cachedTagDao.observeTags()
            .map { cachedList -> cachedList.map { it.toCachedDomain() } }
    }

    suspend fun getTags(forceRefresh: Boolean = false): Result<List<Tag>> {
        return try {
            // Offline-First: Try cache first unless forceRefresh
            if (!forceRefresh || !networkMonitor.checkOnlineStatus()) {
                val cachedTags = cachedTagDao.getAllTags()
                if (cachedTags.isNotEmpty()) {
                    return Result.success(cachedTags.map { it.toCachedDomain() })
                }
            }

            // Network fetch (if online and forceRefresh or cache empty)
            if (networkMonitor.checkOnlineStatus()) {
                val response = api.getTags(page = 1, pageSize = 100)
                // Update cache
                val cachedEntities = response.results.map { it.toCachedEntity() }
                cachedTagDao.insertAll(cachedEntities)
                Result.success(response.results.toDomain())
            } else {
                // Offline, no cache
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    suspend fun createTag(name: String, color: String? = null): Result<Tag> = safeApiCall {
        api.createTag(CreateTagRequest(name = name, color = color)).toDomain()
    }

    suspend fun updateTag(id: Int, name: String, color: String? = null): Result<Tag> = safeApiCall {
        api.updateTag(id, UpdateTagRequest(name = name, color = color)).toDomain()
    }

    suspend fun deleteTag(id: Int): Result<Unit> = safeApiResponse {
        api.deleteTag(id)
    }

    suspend fun getDocumentsForTag(tagId: Int): Result<List<Document>> = safeApiCall {
        api.getDocuments(
            tagIds = tagId.toString(),
            pageSize = 100
        ).results.toDomain()
    }
}
