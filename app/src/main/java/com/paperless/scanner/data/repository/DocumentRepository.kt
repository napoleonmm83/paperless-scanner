package com.paperless.scanner.data.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.paging.PagingData
import com.google.gson.Gson
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.api.ProgressRequestBody
import com.paperless.scanner.data.database.dao.CachedDocumentDao
import com.paperless.scanner.data.database.dao.CachedTagDao
import com.paperless.scanner.data.database.dao.CachedTaskDao
import com.paperless.scanner.data.database.dao.PendingChangeDao
import com.paperless.scanner.data.health.ServerHealthMonitor
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.data.analytics.CrashlyticsHelper
import com.paperless.scanner.data.service.DocumentSerializer
import com.paperless.scanner.data.service.ImageProcessorService
import com.paperless.scanner.data.service.PdfGeneratorService
import com.paperless.scanner.R
import com.paperless.scanner.domain.model.AuditLogEntry
import com.paperless.scanner.domain.model.Document
import com.paperless.scanner.domain.model.DocumentsResponse
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import javax.inject.Inject

class DocumentRepository @Inject constructor(
    private val context: Context,
    private val api: PaperlessApi,
    private val cachedDocumentDao: CachedDocumentDao,
    private val cachedTagDao: CachedTagDao,
    private val cachedTaskDao: CachedTaskDao,
    private val pendingChangeDao: PendingChangeDao,
    private val networkMonitor: NetworkMonitor,
    private val serverHealthMonitor: ServerHealthMonitor,
    private val gson: Gson,
    private val crashlyticsHelper: CrashlyticsHelper,
    private val imageProcessor: ImageProcessorService,
    private val pdfGenerator: PdfGeneratorService,
    private val serializer: DocumentSerializer,
    private val count: DocumentCountRepository,
    private val metadata: DocumentMetadataRepository,
    private val list: DocumentListRepository,
    private val trash: TrashRepository,
    private val audit: AuditRepository,
) {
    companion object {
        private const val TAG = "DocumentRepository"
    }

    suspend fun uploadDocument(
        uri: Uri,
        title: String? = null,
        tagIds: List<Int> = emptyList(),
        documentTypeId: Int? = null,
        correspondentId: Int? = null,
        customFields: Map<Int, String> = emptyMap(),
        onProgress: (Float) -> Unit = {}
    ): Result<String> {
        crashlyticsHelper.logActionBreadcrumb("UPLOAD_START", "single-page")
        return try {
            val file = imageProcessor.getFileFromUri(uri)
            val requestFile = ProgressRequestBody(
                file = file,
                contentType = "image/jpeg".toMediaTypeOrNull(),
                onProgress = onProgress
            )
            val documentPart = MultipartBody.Part.createFormData(
                "document",
                file.name,
                requestFile
            )

            val titleBody = title?.toRequestBody("text/plain".toMediaTypeOrNull())

            // Create separate MultipartBody.Part for each tag ID
            // Paperless-ngx expects: tags=1, tags=2, tags=3 (not tags="1,2,3")
            val tagsParts = tagIds.map { tagId ->
                MultipartBody.Part.createFormData("tags", tagId.toString())
            }

            val documentTypeBody = documentTypeId?.toString()
                ?.toRequestBody("text/plain".toMediaTypeOrNull())

            val correspondentBody = correspondentId?.toString()
                ?.toRequestBody("text/plain".toMediaTypeOrNull())

            // Custom fields as JSON array: [{"field": 1, "value": "text"}, ...]
            val customFieldsBody = serializer.serializeCustomFieldsForUpload(customFields)

            val response = api.uploadDocument(
                document = documentPart,
                title = titleBody,
                tags = tagsParts,
                documentType = documentTypeBody,
                correspondent = correspondentBody,
                customFields = customFieldsBody
            )

            file.delete()

            val taskId = response.string().trim().removeSurrounding("\"")
            crashlyticsHelper.logActionBreadcrumb("UPLOAD_SUCCESS", "taskId=$taskId")
            Result.success(taskId)
        } catch (e: IOException) {
            crashlyticsHelper.logStateBreadcrumb("UPLOAD_ERROR", "NetworkError: ${e.message}")
            Result.failure(PaperlessException.NetworkError(e))
        } catch (e: retrofit2.HttpException) {
            val errorBody = try {
                e.response()?.errorBody()?.string()
            } catch (_: Exception) {
                null
            }
            crashlyticsHelper.logStateBreadcrumb("UPLOAD_ERROR", "HTTP ${e.code()}")
            android.util.Log.e("DocumentRepository", "Upload failed: HTTP ${e.code()}, body: $errorBody")
            Result.failure(PaperlessException.fromHttpCode(e.code(), errorBody ?: e.message()))
        } catch (e: IllegalArgumentException) {
            crashlyticsHelper.logStateBreadcrumb("UPLOAD_ERROR", "ContentError: ${e.message}")
            Result.failure(PaperlessException.ContentError(R.string.error_file_read_failed))
        } catch (e: Exception) {
            crashlyticsHelper.logStateBreadcrumb("UPLOAD_ERROR", "${e.javaClass.simpleName}: ${e.message}")
            Result.failure(PaperlessException.from(e))
        }
    }

    suspend fun uploadMultiPageDocument(
        uris: List<Uri>,
        title: String? = null,
        tagIds: List<Int> = emptyList(),
        documentTypeId: Int? = null,
        correspondentId: Int? = null,
        customFields: Map<Int, String> = emptyMap(),
        onProgress: (Float) -> Unit = {}
    ): Result<String> {
        crashlyticsHelper.logActionBreadcrumb("UPLOAD_START", "multi-page, ${uris.size} pages")
        return try {
            android.util.Log.d("DocumentRepository", "Creating PDF from ${uris.size} images...")
            val pdfFile = pdfGenerator.createPdfFromImages(uris)
            android.util.Log.d("DocumentRepository", "PDF created: ${pdfFile.length()} bytes")

            val requestFile = ProgressRequestBody(
                file = pdfFile,
                contentType = "application/pdf".toMediaTypeOrNull(),
                onProgress = onProgress
            )
            val documentPart = MultipartBody.Part.createFormData(
                "document",
                pdfFile.name,
                requestFile
            )

            val titleBody = title?.toRequestBody("text/plain".toMediaTypeOrNull())

            // Create separate MultipartBody.Part for each tag ID
            // Paperless-ngx expects: tags=1, tags=2, tags=3 (not tags="1,2,3")
            val tagsParts = tagIds.map { tagId ->
                MultipartBody.Part.createFormData("tags", tagId.toString())
            }

            val documentTypeBody = documentTypeId?.toString()
                ?.toRequestBody("text/plain".toMediaTypeOrNull())

            val correspondentBody = correspondentId?.toString()
                ?.toRequestBody("text/plain".toMediaTypeOrNull())

            // Custom fields as JSON array: [{"field": 1, "value": "text"}, ...]
            val customFieldsBody = serializer.serializeCustomFieldsForUpload(customFields)

            android.util.Log.d("DocumentRepository", "Starting upload...")
            val response = api.uploadDocument(
                document = documentPart,
                title = titleBody,
                tags = tagsParts,
                documentType = documentTypeBody,
                correspondent = correspondentBody,
                customFields = customFieldsBody
            )
            android.util.Log.d("DocumentRepository", "Upload complete, reading response...")

            pdfFile.delete()

            val taskId = response.string().trim().removeSurrounding("\"")
            android.util.Log.d("DocumentRepository", "Task ID received: $taskId")
            crashlyticsHelper.logActionBreadcrumb("UPLOAD_SUCCESS", "multi-page, taskId=$taskId")
            Result.success(taskId)
        } catch (e: IOException) {
            crashlyticsHelper.logStateBreadcrumb("UPLOAD_ERROR", "NetworkError: ${e.message}")
            Result.failure(PaperlessException.NetworkError(e))
        } catch (e: retrofit2.HttpException) {
            val errorBody = try {
                e.response()?.errorBody()?.string()
            } catch (_: Exception) {
                null
            }
            crashlyticsHelper.logStateBreadcrumb("UPLOAD_ERROR", "HTTP ${e.code()}")
            android.util.Log.e("DocumentRepository", "Multi-page upload failed: HTTP ${e.code()}, body: $errorBody")
            Result.failure(PaperlessException.fromHttpCode(e.code(), errorBody ?: e.message()))
        } catch (e: IllegalArgumentException) {
            // Safe error message extraction (prevent secondary exceptions)
            val safeMessage = e.message ?: context.getString(R.string.error_pdf_creation)
            crashlyticsHelper.logStateBreadcrumb("UPLOAD_ERROR", "PDF creation: $safeMessage")
            android.util.Log.e("DocumentRepository", "IllegalArgumentException during PDF creation: $safeMessage", e)
            Result.failure(PaperlessException.ContentError(R.string.error_pdf_creation))
        } catch (e: IllegalStateException) {
            // Safe error message extraction (prevent secondary exceptions)
            val safeMessage = e.message ?: context.getString(R.string.error_image_process_failed)
            crashlyticsHelper.logStateBreadcrumb("UPLOAD_ERROR", "Image processing: $safeMessage")
            android.util.Log.e("DocumentRepository", "IllegalStateException during PDF creation: $safeMessage", e)
            Result.failure(PaperlessException.ContentError(R.string.error_image_process_failed))
        } catch (e: Exception) {
            // Catch-all for any unexpected exceptions (including iText7 internal errors)
            val safeMessage = e.message?.takeIf { it.isNotBlank() } ?: context.getString(R.string.error_pdf_creation)
            crashlyticsHelper.logStateBreadcrumb("UPLOAD_ERROR", "${e.javaClass.simpleName}: $safeMessage")
            android.util.Log.e("DocumentRepository", "Unexpected exception during multi-page upload: ${e.javaClass.simpleName} - $safeMessage", e)
            Result.failure(PaperlessException.ContentError(R.string.error_pdf_creation))
        }
    }

    // Document fetching methods - Offline-First Pattern

    /**
     * Reactive Flow that observes document changes from local cache.
     * BEST PRACTICE: Automatically updates UI when documents are added/modified/deleted.
     * No manual refresh needed - Room handles reactivity.
     */
    fun observeDocuments(
        page: Int = 1,
        pageSize: Int = 25
    ): Flow<List<Document>> = list.observeDocuments(page, pageSize)

    /**
     * Get total count of filtered documents with searchQuery + DocumentFilter.
     *
     * @param searchQuery Full-text search (from SearchBar)
     * @param filter DocumentFilter with structured criteria (from FilterSheet)
     * @return Flow that emits count and updates automatically
     */
    fun observeCountWithFilter(
        searchQuery: String? = null,
        filter: com.paperless.scanner.domain.model.DocumentFilter = com.paperless.scanner.domain.model.DocumentFilter.empty()
    ): Flow<Int> = count.observeCountWithFilter(searchQuery, filter)

    /**
     * Get count of untagged documents (for Smart Tagging).
     * Reactively updates when documents are tagged or new documents are added.
     *
     * @return Flow that emits count of documents without tags
     */
    fun observeUntaggedDocumentsCount(): Flow<Int> = count.observeUntaggedDocumentsCount()

    /**
     * Get all untagged documents from local cache (for Smart Tagging screen).
     * Uses offline-first approach - returns cached documents immediately.
     *
     * @return Result with list of untagged documents ordered by most recent
     */
    suspend fun getUntaggedDocuments(): Result<List<Document>> = list.getUntaggedDocuments()

    /**
     * PAGING 3: Get documents as paginated Flow for infinite scroll.
     *
     * BEST PRACTICE: Pager automatically handles:
     * - Loading initial data
     * - Loading more when user scrolls (append)
     * - Caching loaded pages
     * - Cancelling old requests when filter/search changes
     *
     * The PagingSource is invalidated automatically when Room DB changes.
     *
     * @param searchQuery Full-text search (from SearchBar)
     * @param filter DocumentFilter with structured criteria (from FilterSheet)
     * @return Flow<PagingData<Document>> for collectAsLazyPagingItems()
     */
    fun getDocumentsPaged(
        searchQuery: String? = null,
        filter: com.paperless.scanner.domain.model.DocumentFilter = com.paperless.scanner.domain.model.DocumentFilter.empty()
    ): Flow<PagingData<Document>> = list.getDocumentsPaged(searchQuery, filter)

    suspend fun getDocuments(
        page: Int = 1,
        pageSize: Int = 25,
        query: String? = null,
        tagIds: List<Int>? = null,
        correspondentId: Int? = null,
        documentTypeId: Int? = null,
        ordering: String = "-created",
        forceRefresh: Boolean = false
    ): Result<DocumentsResponse> = list.getDocuments(
        page, pageSize, query, tagIds, correspondentId, documentTypeId, ordering, forceRefresh
    )

    suspend fun getDocument(id: Int, forceRefresh: Boolean = false): Result<Document> =
        metadata.getDocument(id, forceRefresh)

    /**
     * BEST PRACTICE: Reactive Flow for single document observation.
     * Automatically updates UI when document is modified in Room DB.
     *
     * Note: ViewModel should trigger background refresh via getDocument(id, forceRefresh=true)
     * to sync latest data from API. Room Flow will automatically update UI when cache changes.
     *
     * Usage in ViewModel:
     * ```kotlin
     * // Trigger background API refresh (fire and forget)
     * viewModelScope.launch { getDocument(id, forceRefresh = true) }
     *
     * // Observe reactive Flow
     * observeDocument(documentId).collect { document ->
     *     // UI updates automatically when cache changes
     * }
     * ```
     */
    fun observeDocument(id: Int): Flow<Document?> = metadata.observeDocument(id)

    suspend fun searchDocuments(query: String): Result<List<Document>> = list.searchDocuments(query)

    suspend fun getDocumentCount(forceRefresh: Boolean = false): Result<Int> =
        count.getDocumentCount(forceRefresh)

    suspend fun getRecentDocuments(limit: Int = 5): Result<List<Document>> = list.getRecentDocuments(limit)

    suspend fun getUntaggedCount(): Result<Int> = count.getUntaggedCount()

    suspend fun downloadDocument(
        documentId: Int,
        onProgress: (Float) -> Unit = {}
    ): Result<File> {
        return try {
            val response = api.downloadDocument(documentId)

            val fileName = "document_${documentId}_${System.currentTimeMillis()}.pdf"
            val pdfFile = File(context.cacheDir, fileName)

            val contentLength = response.contentLength()

            response.byteStream().use { inputStream ->
                pdfFile.outputStream().use { outputStream ->
                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Long = 0
                    var read: Int

                    while (inputStream.read(buffer).also { read = it } != -1) {
                        outputStream.write(buffer, 0, read)
                        bytesRead += read

                        if (contentLength > 0) {
                            onProgress(bytesRead.toFloat() / contentLength.toFloat())
                        }
                    }
                }
            }

            Result.success(pdfFile)
        } catch (e: IOException) {
            Result.failure(PaperlessException.NetworkError(e))
        } catch (e: retrofit2.HttpException) {
            Result.failure(PaperlessException.fromHttpCode(e.code(), e.message()))
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    suspend fun deleteDocument(documentId: Int): Result<Unit> = trash.deleteDocument(documentId)

    suspend fun updateDocument(
        documentId: Int,
        title: String? = null,
        tags: List<Int>? = null,
        correspondent: Int? = null,
        documentType: Int? = null,
        archiveSerialNumber: Int? = null,
        created: String? = null
    ): Result<Document> = metadata.updateDocument(
        documentId, title, tags, correspondent, documentType, archiveSerialNumber, created
    )

    suspend fun getDocumentHistory(documentId: Int): Result<List<AuditLogEntry>> =
        audit.getDocumentHistory(documentId)

    suspend fun addNote(documentId: Int, noteText: String): Result<List<com.paperless.scanner.domain.model.Note>> =
        audit.addNote(documentId, noteText)

    suspend fun deleteNote(documentId: Int, noteId: Int): Result<List<com.paperless.scanner.domain.model.Note>> =
        audit.deleteNote(documentId, noteId)

    // User and Group methods for permissions management

    suspend fun getUsers(): Result<List<com.paperless.scanner.data.api.models.User>> {
        return try {
            if (networkMonitor.checkOnlineStatus()) {
                val response = api.getUsers()
                Result.success(response.results)
            } else {
                Result.failure(PaperlessException.NetworkError(IOException(context.getString(R.string.error_offline))))
            }
        } catch (e: retrofit2.HttpException) {
            Result.failure(PaperlessException.fromHttpCode(e.code(), e.message()))
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    suspend fun getGroups(): Result<List<com.paperless.scanner.data.api.models.Group>> {
        return try {
            if (networkMonitor.checkOnlineStatus()) {
                val response = api.getGroups()
                Result.success(response.results)
            } else {
                Result.failure(PaperlessException.NetworkError(IOException(context.getString(R.string.error_offline))))
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
        changeGroups: List<Int>
    ): Result<Document> = metadata.updateDocumentPermissions(
        documentId, owner, viewUsers, viewGroups, changeUsers, changeGroups
    )

    // ========================================
    // TRASH METHODS (Paperless-ngx v2.20+)
    // ========================================

    suspend fun getTrashDocuments(page: Int = 1, pageSize: Int = 25): Result<DocumentsResponse> =
        trash.getTrashDocuments(page, pageSize)

    suspend fun restoreDocument(documentId: Int): Result<Unit> = trash.restoreDocument(documentId)

    suspend fun restoreDocuments(documentIds: List<Int>): Result<Unit> =
        trash.restoreDocuments(documentIds)

    suspend fun permanentlyDeleteDocument(documentId: Int): Result<Unit> =
        trash.permanentlyDeleteDocument(documentId)

    suspend fun permanentlyDeleteDocuments(documentIds: List<Int>): Result<Unit> =
        trash.permanentlyDeleteDocuments(documentIds)

    suspend fun getOldDeletedDocumentIds(retentionDays: Int = 30): Result<List<Int>> =
        trash.getOldDeletedDocumentIds(retentionDays)

    fun observeTrashedDocuments(): Flow<List<com.paperless.scanner.data.database.entities.CachedDocument>> =
        trash.observeTrashedDocuments()

    fun observeTrashedDocumentsCount(): Flow<Int> = trash.observeTrashedDocumentsCount()

    fun observeOldestDeletedTimestamp(): Flow<Long?> = trash.observeOldestDeletedTimestamp()

    suspend fun cleanupOrphanedTrashDocs(serverTrashIds: Set<Int>) =
        trash.cleanupOrphanedTrashDocs(serverTrashIds)
}
