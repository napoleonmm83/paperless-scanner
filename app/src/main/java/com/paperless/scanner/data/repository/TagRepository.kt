package com.paperless.scanner.data.repository

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.api.models.CreateTagRequest
import com.paperless.scanner.data.api.models.UpdateTagRequest
import com.paperless.scanner.data.api.safeApiCall
import com.paperless.scanner.data.api.safeApiResponse
import com.paperless.scanner.data.database.dao.CachedDocumentDao
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
    private val cachedDocumentDao: CachedDocumentDao,
    private val pendingChangeDao: PendingChangeDao,
    private val networkMonitor: NetworkMonitor,
    private val gson: Gson
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

    suspend fun createTag(name: String, color: String? = null): Result<Tag> {
        return try {
            val response = api.createTag(CreateTagRequest(name = name, color = color))
            val domainTag = response.toDomain()

            // Insert into cache to trigger reactive Flow update immediately
            // This ensures the new tag appears in existingTags right away
            cachedTagDao.insert(response.toCachedEntity())

            Result.success(domainTag)
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    suspend fun updateTag(id: Int, name: String, color: String? = null): Result<Tag> {
        return try {
            val response = api.updateTag(id, UpdateTagRequest(name = name, color = color))
            val domainTag = response.toDomain()

            // Update cache to trigger reactive Flow update immediately
            cachedTagDao.insert(response.toCachedEntity())

            Result.success(domainTag)
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    suspend fun deleteTag(id: Int): Result<Unit> {
        return try {
            api.deleteTag(id)

            // Delete from cache to trigger reactive Flow update immediately
            cachedTagDao.deleteByIds(listOf(id))

            // BEST PRACTICE: Remove deleted tag ID from all cached documents
            // This ensures HomeScreen shows correct tag status immediately
            removeTagFromCachedDocuments(id)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    /**
     * Removes a deleted tag ID from all cached documents.
     * This ensures reactive Flows update correctly without requiring
     * a full server sync.
     */
    private suspend fun removeTagFromCachedDocuments(tagId: Int) {
        try {
            val listType = object : TypeToken<List<Int>>() {}.type

            // Get all cached documents
            val documents = cachedDocumentDao.getDocuments(limit = 1000, offset = 0)

            documents.forEach { doc ->
                val tagIds: List<Int> = try {
                    gson.fromJson(doc.tags, listType) ?: emptyList()
                } catch (e: Exception) {
                    emptyList()
                }

                if (tagIds.contains(tagId)) {
                    // Remove the deleted tag ID
                    val updatedTagIds = tagIds.filter { it != tagId }
                    val updatedTagsJson = gson.toJson(updatedTagIds)

                    // Update the document in cache
                    cachedDocumentDao.update(doc.copy(tags = updatedTagsJson))
                }
            }
        } catch (e: Exception) {
            // Log but don't fail - cache update is best effort
            // Server is already in sync, cache will update on next full sync
        }
    }

    suspend fun getDocumentsForTag(tagId: Int): Result<List<Document>> = safeApiCall {
        api.getDocuments(
            tagIds = tagId.toString(),
            pageSize = 100
        ).results.toDomain()
    }
}
