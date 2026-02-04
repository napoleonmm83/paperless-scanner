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

/**
 * TagRepository - Repository for tag management with offline-first architecture.
 *
 * **ARCHITECTURE:**
 * Implements offline-first pattern with Room cache as single source of truth.
 * Uses reactive Flows for automatic UI updates when tag data changes.
 *
 * **DATA FLOW:**
 * ```
 * UI ← Flow ← Room Cache ← Repository ← API
 *                ↑_____________________________|
 *                         (sync on refresh)
 * ```
 *
 * **FEATURES:**
 * - Reactive [observeTags] Flow for automatic UI updates
 * - Offline-first with network sync on demand
 * - Cache invalidation after CRUD operations
 * - Cascading tag removal from cached documents on delete
 *
 * **USAGE:**
 * ```kotlin
 * // Reactive observation (preferred)
 * tagRepository.observeTags().collect { tags ->
 *     updateUI(tags)
 * }
 *
 * // One-shot fetch with optional refresh
 * val tags = tagRepository.getTags(forceRefresh = true)
 * ```
 *
 * @property api Paperless-ngx REST API interface
 * @property cachedTagDao Room DAO for tag cache operations
 * @property cachedDocumentDao Room DAO for document cache (for cascading updates)
 * @property networkMonitor Network connectivity checker
 * @property gson JSON serializer for tag ID lists
 *
 * @see PaperlessApi.getTags For API endpoint
 * @see CachedTagDao For cache operations
 * @see Tag Domain model for tags
 */
class TagRepository @Inject constructor(
    private val api: PaperlessApi,
    private val cachedTagDao: CachedTagDao,
    private val cachedDocumentDao: CachedDocumentDao,
    private val pendingChangeDao: PendingChangeDao,
    private val networkMonitor: NetworkMonitor,
    private val gson: Gson
) {
    /**
     * Observe all tags reactively.
     *
     * **BEST PRACTICE:** Reactive Flow for automatic UI updates.
     * Emits new list whenever tags are added, modified, or deleted.
     *
     * @return [Flow] emitting list of [Tag] objects on any cache change
     * @see CachedTagDao.observeTags For underlying Room query
     */
    fun observeTags(): Flow<List<Tag>> {
        return cachedTagDao.observeTags()
            .map { cachedList -> cachedList.map { it.toCachedDomain() } }
    }

    /**
     * Get all tags with optional network refresh.
     *
     * **OFFLINE-FIRST STRATEGY:**
     * 1. If not forceRefresh: Return cached tags immediately
     * 2. If forceRefresh and online: Fetch from API, update cache, return fresh data
     * 3. If offline: Return cached data (or empty list if no cache)
     *
     * @param forceRefresh If true, fetches fresh data from server (when online)
     * @return [Result] containing list of [Tag] objects, or failure with [PaperlessException]
     * @see observeTags For reactive alternative (preferred for UI)
     */
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

    /**
     * Create a new tag on the server and cache.
     *
     * Immediately updates local cache after successful creation,
     * triggering reactive Flow updates for connected UI components.
     *
     * @param name Tag name (must be unique)
     * @param color Optional hex color code (e.g., "#FF5733")
     * @return [Result] containing created [Tag] with server-assigned ID, or failure
     * @see PaperlessApi.createTag For API endpoint
     */
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

    /**
     * Update an existing tag on the server and cache.
     *
     * Immediately updates local cache after successful update,
     * triggering reactive Flow updates for connected UI components.
     *
     * @param id Tag ID to update
     * @param name New tag name
     * @param color Optional new hex color code
     * @return [Result] containing updated [Tag], or failure with [PaperlessException]
     * @see PaperlessApi.updateTag For API endpoint
     */
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

    /**
     * Delete a tag from the server and cache.
     *
     * **CASCADE BEHAVIOR:**
     * After deletion, removes the tag ID from all cached documents
     * to ensure UI consistency without requiring a full sync.
     *
     * @param id Tag ID to delete
     * @return [Result] with Unit on success, or failure with [PaperlessException]
     * @see PaperlessApi.deleteTag For API endpoint
     * @see removeTagFromCachedDocuments For cascade logic
     */
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

    /**
     * Get all documents that have a specific tag.
     *
     * Fetches directly from server (no caching) to get up-to-date
     * document list filtered by tag.
     *
     * @param tagId Tag ID to filter documents by
     * @return [Result] containing list of [Document] objects with this tag
     * @see PaperlessApi.getDocuments For underlying API call
     */
    suspend fun getDocumentsForTag(tagId: Int): Result<List<Document>> = safeApiCall {
        api.getDocuments(
            tagIds = tagId.toString(),
            pageSize = 100
        ).results.toDomain()
    }
}
