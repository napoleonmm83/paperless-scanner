package com.paperless.scanner.data.repository

import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.api.safeApiCall
import com.paperless.scanner.data.database.dao.CachedCorrespondentDao
import com.paperless.scanner.data.database.dao.PendingChangeDao
import com.paperless.scanner.data.database.mappers.toCachedEntity
import com.paperless.scanner.data.database.mappers.toDomain as toCachedDomain
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.domain.mapper.toDomain
import com.paperless.scanner.domain.model.Correspondent
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
}
