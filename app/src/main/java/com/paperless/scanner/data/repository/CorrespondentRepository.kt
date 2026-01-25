package com.paperless.scanner.data.repository

import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.api.models.CreateCorrespondentRequest
import com.paperless.scanner.data.api.models.UpdateCorrespondentRequest
import com.paperless.scanner.data.api.safeApiCall
import com.paperless.scanner.data.database.dao.CachedCorrespondentDao
import com.paperless.scanner.data.database.dao.PendingChangeDao
import com.paperless.scanner.data.database.mappers.toCachedEntity
import com.paperless.scanner.data.database.mappers.toDomain as toCachedDomain
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.domain.mapper.toDomain
import com.paperless.scanner.domain.model.Correspondent
import com.paperless.scanner.domain.model.Document
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class CorrespondentRepository @Inject constructor(
    private val api: PaperlessApi,
    private val cachedCorrespondentDao: CachedCorrespondentDao,
    private val pendingChangeDao: PendingChangeDao,
    private val networkMonitor: NetworkMonitor
) {
    /**
     * BEST PRACTICE: Reactive Flow for automatic UI updates.
     * Observes cached correspondents and automatically notifies when data changes.
     */
    fun observeCorrespondents(): Flow<List<Correspondent>> {
        return cachedCorrespondentDao.observeCorrespondents()
            .map { cachedList -> cachedList.map { it.toCachedDomain() } }
    }

    suspend fun getCorrespondents(forceRefresh: Boolean = false): Result<List<Correspondent>> {
        return try {
            // Offline-First: Try cache first unless forceRefresh
            if (!forceRefresh || !networkMonitor.checkOnlineStatus()) {
                val cached = cachedCorrespondentDao.getAllCorrespondents()
                if (cached.isNotEmpty()) {
                    return Result.success(cached.map { it.toCachedDomain() })
                }
            }

            // Network fetch (if online and forceRefresh or cache empty)
            if (networkMonitor.checkOnlineStatus()) {
                val response = api.getCorrespondents(page = 1, pageSize = 100)
                // Update cache
                val cachedEntities = response.results.map { it.toCachedEntity() }
                cachedCorrespondentDao.insertAll(cachedEntities)
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
     * Creates a new correspondent.
     * Updates cache immediately to trigger reactive Flow.
     */
    suspend fun createCorrespondent(name: String): Result<Correspondent> {
        return try {
            val response = api.createCorrespondent(CreateCorrespondentRequest(name = name))
            val domainCorrespondent = response.toDomain()

            // Insert into cache to trigger reactive Flow update immediately
            cachedCorrespondentDao.insert(response.toCachedEntity())

            Result.success(domainCorrespondent)
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    /**
     * Updates an existing correspondent.
     * Updates cache immediately to trigger reactive Flow.
     */
    suspend fun updateCorrespondent(id: Int, name: String): Result<Correspondent> {
        return try {
            val response = api.updateCorrespondent(id, UpdateCorrespondentRequest(name = name))
            val domainCorrespondent = response.toDomain()

            // Update cache to trigger reactive Flow update immediately
            cachedCorrespondentDao.insert(response.toCachedEntity())

            Result.success(domainCorrespondent)
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    /**
     * Deletes a correspondent.
     * Removes from cache to trigger reactive Flow.
     */
    suspend fun deleteCorrespondent(id: Int): Result<Unit> {
        return try {
            api.deleteCorrespondent(id)

            // Soft delete from cache to trigger reactive Flow update immediately
            cachedCorrespondentDao.softDelete(id)

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    /**
     * Gets all documents for a specific correspondent.
     * Used for detail view in Labels Screen.
     */
    suspend fun getDocumentsForCorrespondent(correspondentId: Int): Result<List<Document>> = safeApiCall {
        api.getDocuments(
            correspondentId = correspondentId,
            pageSize = 100
        ).results.toDomain()
    }
}
