package com.paperless.scanner.data.repository

import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.api.models.CreateDocumentTypeRequest
import com.paperless.scanner.data.api.models.UpdateDocumentTypeRequest
import com.paperless.scanner.data.api.safeApiCall
import com.paperless.scanner.data.database.dao.CachedDocumentTypeDao
import com.paperless.scanner.data.database.dao.PendingChangeDao
import com.paperless.scanner.data.database.mappers.toCachedEntity
import com.paperless.scanner.data.database.mappers.toDomain as toCachedDomain
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.domain.mapper.toDomain
import com.paperless.scanner.domain.model.Document
import com.paperless.scanner.domain.model.DocumentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

class DocumentTypeRepository @Inject constructor(
    private val api: PaperlessApi,
    private val cachedDocumentTypeDao: CachedDocumentTypeDao,
    private val pendingChangeDao: PendingChangeDao,
    private val networkMonitor: NetworkMonitor
) {
    /**
     * BEST PRACTICE: Reactive Flow for automatic UI updates.
     * Observes cached document types and automatically notifies when data changes.
     */
    fun observeDocumentTypes(): Flow<List<DocumentType>> {
        return cachedDocumentTypeDao.observeDocumentTypes()
            .map { cachedList -> cachedList.map { it.toCachedDomain() } }
    }

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

    /**
     * Creates a new document type.
     * Updates cache immediately to trigger reactive Flow.
     */
    suspend fun createDocumentType(name: String): Result<DocumentType> {
        return try {
            val response = api.createDocumentType(CreateDocumentTypeRequest(name = name))
            val domainDocumentType = response.toDomain()

            // Insert into cache to trigger reactive Flow update immediately
            cachedDocumentTypeDao.insert(response.toCachedEntity())

            Result.success(domainDocumentType)
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
            val response = api.updateDocumentType(id, UpdateDocumentTypeRequest(name = name))
            val domainDocumentType = response.toDomain()

            // Update cache to trigger reactive Flow update immediately
            cachedDocumentTypeDao.insert(response.toCachedEntity())

            Result.success(domainDocumentType)
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
            api.deleteDocumentType(id)

            // Delete from cache to trigger reactive Flow update immediately
            cachedDocumentTypeDao.softDelete(id)

            Result.success(Unit)
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
