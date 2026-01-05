package com.paperless.scanner.data.repository

import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.api.safeApiCall
import com.paperless.scanner.data.database.dao.CachedDocumentTypeDao
import com.paperless.scanner.data.database.dao.PendingChangeDao
import com.paperless.scanner.data.database.mappers.toCachedEntity
import com.paperless.scanner.data.database.mappers.toDomain as toCachedDomain
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.domain.mapper.toDomain
import com.paperless.scanner.domain.model.DocumentType
import javax.inject.Inject

class DocumentTypeRepository @Inject constructor(
    private val api: PaperlessApi,
    private val cachedDocumentTypeDao: CachedDocumentTypeDao,
    private val pendingChangeDao: PendingChangeDao,
    private val networkMonitor: NetworkMonitor
) {
    suspend fun getDocumentTypes(forceRefresh: Boolean = false): Result<List<DocumentType>> {
        return try {
            // Offline-First: Try cache first unless forceRefresh
            if (!forceRefresh || !networkMonitor.checkOnlineStatus()) {
                val cached = cachedDocumentTypeDao.getAllDocumentTypes()
                if (cached.isNotEmpty()) {
                    return Result.success(cached.map { it.toCachedDomain() })
                }
            }

            // Network fetch (if online and forceRefresh or cache empty)
            if (networkMonitor.checkOnlineStatus()) {
                val response = api.getDocumentTypes(page = 1, pageSize = 100)
                // Update cache
                val cachedEntities = response.results.map { it.toCachedEntity() }
                cachedDocumentTypeDao.insertAll(cachedEntities)
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
