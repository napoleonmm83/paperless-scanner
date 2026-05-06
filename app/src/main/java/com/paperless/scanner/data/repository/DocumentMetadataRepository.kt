package com.paperless.scanner.data.repository

import android.content.Context
import com.paperless.scanner.R
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.api.models.PermissionSet
import com.paperless.scanner.data.api.models.SetPermissionsRequest
import com.paperless.scanner.data.api.models.UpdateDocumentRequest
import com.paperless.scanner.data.api.models.UpdateDocumentWithPermissionsRequest
import com.paperless.scanner.data.database.dao.CachedDocumentDao
import com.paperless.scanner.data.database.dao.CachedTagDao
import com.paperless.scanner.data.database.mappers.toCachedEntity
import com.paperless.scanner.data.database.mappers.toDomain as toCachedDomain
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.data.service.DocumentSerializer
import com.paperless.scanner.domain.mapper.toDomain
import com.paperless.scanner.domain.model.Document
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Phase 2.3 of #51 — extracted from DocumentRepository.
 *
 * Owns single-document read + write operations: observeDocument, getDocument,
 * updateDocument, updateDocumentPermissions.
 *
 * Phase 3.3 (#169): updateDocument's offline-queue branch is delegated to
 * [DocumentSyncRepository.executeOrQueue], which centralizes the
 * serverHealth-snapshot + IOException-fallback pattern and uses gson-based
 * payload serialization.
 */
@Singleton
class DocumentMetadataRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: PaperlessApi,
    private val cachedDocumentDao: CachedDocumentDao,
    private val cachedTagDao: CachedTagDao,
    private val networkMonitor: NetworkMonitor,
    private val serializer: DocumentSerializer,
    private val sync: DocumentSyncRepository,
) {

    fun observeDocument(id: Int): Flow<Document?> {
        return cachedDocumentDao.observeDocument(id).map { it?.toCachedDomain() }
    }

    suspend fun getDocument(id: Int, forceRefresh: Boolean = false): Result<Document> {
        return try {
            if (forceRefresh || networkMonitor.checkOnlineStatus()) {
                return try {
                    val doc = api.getDocument(id)
                    cachedDocumentDao.insert(doc.toCachedEntity())
                    Result.success(doc.toDomain())
                } catch (e: retrofit2.HttpException) {
                    val cached = cachedDocumentDao.getDocument(id)
                    if (cached != null) Result.success(cached.toCachedDomain())
                    else Result.failure(PaperlessException.fromHttpCode(e.code(), e.message()))
                } catch (e: Exception) {
                    val cached = cachedDocumentDao.getDocument(id)
                    if (cached != null) Result.success(cached.toCachedDomain())
                    else Result.failure(PaperlessException.from(e))
                }
            }
            val cached = cachedDocumentDao.getDocument(id)
            if (cached != null) Result.success(cached.toCachedDomain())
            else Result.failure(
                PaperlessException.ClientError(404, context.getString(R.string.error_document_not_cached))
            )
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    suspend fun updateDocument(
        documentId: Int,
        title: String? = null,
        tags: List<Int>? = null,
        correspondent: Int? = null,
        documentType: Int? = null,
        archiveSerialNumber: Int? = null,
        created: String? = null,
    ): Result<Document> = sync.executeOrQueue(
        online = {
            val oldTagIds = if (tags != null) getOldTagIds(documentId) else null
            val request = UpdateDocumentRequest(
                title = title,
                tags = tags,
                correspondent = correspondent,
                documentType = documentType,
                archiveSerialNumber = archiveSerialNumber,
                created = created,
            )
            val updatedDocument = api.updateDocument(documentId, request)
            cachedDocumentDao.insert(updatedDocument.toCachedEntity())
            if (tags != null && oldTagIds != null) {
                updateTagDocumentCounts(oldTagIds, tags)
            }
            updatedDocument.toDomain()
        },
        offlineQueueAndOptimistic = {
            sync.queueDocumentUpdate(
                documentId = documentId,
                payload = DocumentSyncRepository.DocumentUpdatePayload(
                    title = title,
                    tags = tags,
                    correspondent = correspondent,
                    documentType = documentType,
                    archiveSerialNumber = archiveSerialNumber,
                    created = created,
                ),
            )
            val cached = cachedDocumentDao.getDocument(documentId)
            cached?.toCachedDomain()
                ?: throw PaperlessException.ClientError(
                    404,
                    context.getString(R.string.error_document_not_cached),
                )
        },
    )

    suspend fun updateDocumentPermissions(
        documentId: Int,
        owner: Int?,
        viewUsers: List<Int>,
        viewGroups: List<Int>,
        changeUsers: List<Int>,
        changeGroups: List<Int>,
    ): Result<Document> {
        return try {
            if (networkMonitor.checkOnlineStatus()) {
                val request = UpdateDocumentWithPermissionsRequest(
                    owner = owner,
                    setPermissions = SetPermissionsRequest(
                        view = PermissionSet(users = viewUsers, groups = viewGroups),
                        change = PermissionSet(users = changeUsers, groups = changeGroups),
                    ),
                )
                val updatedDocument = api.updateDocumentPermissions(documentId, request)
                cachedDocumentDao.insert(updatedDocument.toCachedEntity())
                Result.success(updatedDocument.toDomain())
            } else {
                Result.failure(
                    PaperlessException.NetworkError(IOException(context.getString(R.string.error_offline)))
                )
            }
        } catch (e: retrofit2.HttpException) {
            Result.failure(PaperlessException.fromHttpCode(e.code(), e.message()))
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    private suspend fun getOldTagIds(documentId: Int): List<Int> {
        return try {
            val cached = cachedDocumentDao.getDocument(documentId)
            serializer.deserializeCachedTagIds(cached?.tags)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun updateTagDocumentCounts(oldTagIds: List<Int>, newTagIds: List<Int>) {
        try {
            val oldSet = oldTagIds.toSet()
            val newSet = newTagIds.toSet()
            (oldSet - newSet).forEach { tagId -> cachedTagDao.updateDocumentCount(tagId, -1) }
            (newSet - oldSet).forEach { tagId -> cachedTagDao.updateDocumentCount(tagId, 1) }
        } catch (_: Exception) {
            // best effort — server is in sync, cache will reconcile on next full sync
        }
    }
}
