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
import com.paperless.scanner.data.database.dao.PendingChangeDao
import com.paperless.scanner.data.database.entities.PendingChange
import com.paperless.scanner.data.database.mappers.toCachedEntity
import com.paperless.scanner.data.database.mappers.toDomain as toCachedDomain
import com.paperless.scanner.data.health.ServerHealthMonitor
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
 * PHASE-3.3 DEBT: updateDocument's offline-queue branch (PendingChange) belongs
 * to the future DocumentSyncRepository (#169). It is moved here together with
 * updateDocument for now; pendingChangeDao + serverHealthMonitor become unused
 * the moment Phase 3.3 introduces DocumentSyncRepository.executeOrQueue { ... }.
 */
@Singleton
class DocumentMetadataRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: PaperlessApi,
    private val cachedDocumentDao: CachedDocumentDao,
    private val cachedTagDao: CachedTagDao,
    private val pendingChangeDao: PendingChangeDao,           // PHASE-3.3 debt
    private val networkMonitor: NetworkMonitor,
    private val serverHealthMonitor: ServerHealthMonitor,     // PHASE-3.3 debt
    private val serializer: DocumentSerializer,
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
    ): Result<Document> {
        return try {
            if (serverHealthMonitor.isServerReachable.value) {
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
                Result.success(updatedDocument.toDomain())
            } else {
                // PHASE-3.3: extract via DocumentSyncRepository.executeOrQueue { ... }
                val changeData = buildString {
                    append("{")
                    title?.let { append("\"title\":\"$it\",") }
                    tags?.let { append("\"tags\":$it,") }
                    correspondent?.let { append("\"correspondent\":$it,") }
                    documentType?.let { append("\"documentType\":$it,") }
                    archiveSerialNumber?.let { append("\"archiveSerialNumber\":$it,") }
                    created?.let { append("\"created\":\"$it\",") }
                    if (endsWith(",")) deleteCharAt(length - 1)
                    append("}")
                }
                val pendingChange = PendingChange(
                    entityType = "document",
                    entityId = documentId,
                    changeType = "update",
                    changeData = changeData,
                )
                pendingChangeDao.insert(pendingChange)
                val cached = cachedDocumentDao.getDocument(documentId)
                if (cached != null) Result.success(cached.toCachedDomain())
                else Result.failure(
                    PaperlessException.ClientError(404, context.getString(R.string.error_document_not_cached))
                )
            }
        } catch (e: retrofit2.HttpException) {
            Result.failure(PaperlessException.fromHttpCode(e.code(), e.message()))
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

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
