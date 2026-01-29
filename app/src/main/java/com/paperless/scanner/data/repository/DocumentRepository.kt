package com.paperless.scanner.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document as ITextDocument
import com.itextpdf.layout.element.Image
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.api.ProgressRequestBody
import com.paperless.scanner.data.api.models.AcknowledgeTasksRequest
import com.paperless.scanner.data.api.models.PermissionSet
import com.paperless.scanner.data.api.models.SetPermissionsRequest
import com.paperless.scanner.data.api.models.TrashBulkActionRequest
import com.paperless.scanner.data.api.models.UpdateDocumentRequest
import com.paperless.scanner.data.api.models.UpdateDocumentWithPermissionsRequest
import com.paperless.scanner.data.api.safeApiCall
import com.paperless.scanner.data.database.dao.CachedDocumentDao
import com.paperless.scanner.data.database.dao.CachedTagDao
import com.paperless.scanner.data.database.dao.CachedTaskDao
import com.paperless.scanner.data.database.dao.PendingChangeDao
import com.paperless.scanner.data.database.entities.PendingChange
import com.paperless.scanner.data.database.mappers.toCachedEntity
import com.paperless.scanner.data.database.mappers.toDomain as toCachedDomain
import com.paperless.scanner.data.health.ServerHealthMonitor
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.domain.mapper.toAuditLogDomain
import com.paperless.scanner.domain.mapper.toDomain
import com.paperless.scanner.domain.model.AuditLogEntry
import com.paperless.scanner.domain.model.Document
import com.paperless.scanner.domain.model.DocumentsResponse
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

class DocumentRepository @Inject constructor(
    private val context: Context,
    private val api: PaperlessApi,
    private val cachedDocumentDao: CachedDocumentDao,
    private val cachedTagDao: CachedTagDao,
    private val cachedTaskDao: CachedTaskDao,
    private val pendingChangeDao: PendingChangeDao,
    private val networkMonitor: NetworkMonitor,
    private val serverHealthMonitor: ServerHealthMonitor
) {
    companion object {
        private const val TAG = "DocumentRepository"
    }

    private val gson = Gson()
    suspend fun uploadDocument(
        uri: Uri,
        title: String? = null,
        tagIds: List<Int> = emptyList(),
        documentTypeId: Int? = null,
        correspondentId: Int? = null,
        onProgress: (Float) -> Unit = {}
    ): Result<String> {
        return try {
            val file = getFileFromUri(uri)
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

            val response = api.uploadDocument(
                document = documentPart,
                title = titleBody,
                tags = tagsParts,
                documentType = documentTypeBody,
                correspondent = correspondentBody
            )

            file.delete()

            val taskId = response.string().trim().removeSurrounding("\"")
            Result.success(taskId)
        } catch (e: IOException) {
            Result.failure(PaperlessException.NetworkError(e))
        } catch (e: retrofit2.HttpException) {
            val errorBody = try {
                e.response()?.errorBody()?.string()
            } catch (_: Exception) {
                null
            }
            android.util.Log.e("DocumentRepository", "Upload failed: HTTP ${e.code()}, body: $errorBody")
            Result.failure(PaperlessException.fromHttpCode(e.code(), errorBody ?: e.message()))
        } catch (e: IllegalArgumentException) {
            Result.failure(PaperlessException.ContentError(e.message ?: "Datei konnte nicht gelesen werden"))
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    suspend fun uploadMultiPageDocument(
        uris: List<Uri>,
        title: String? = null,
        tagIds: List<Int> = emptyList(),
        documentTypeId: Int? = null,
        correspondentId: Int? = null,
        onProgress: (Float) -> Unit = {}
    ): Result<String> {
        return try {
            android.util.Log.d("DocumentRepository", "Creating PDF from ${uris.size} images...")
            val pdfFile = createPdfFromImages(uris)
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

            android.util.Log.d("DocumentRepository", "Starting upload...")
            val response = api.uploadDocument(
                document = documentPart,
                title = titleBody,
                tags = tagsParts,
                documentType = documentTypeBody,
                correspondent = correspondentBody
            )
            android.util.Log.d("DocumentRepository", "Upload complete, reading response...")

            pdfFile.delete()

            val taskId = response.string().trim().removeSurrounding("\"")
            android.util.Log.d("DocumentRepository", "Task ID received: $taskId")
            Result.success(taskId)
        } catch (e: IOException) {
            Result.failure(PaperlessException.NetworkError(e))
        } catch (e: retrofit2.HttpException) {
            val errorBody = try {
                e.response()?.errorBody()?.string()
            } catch (_: Exception) {
                null
            }
            android.util.Log.e("DocumentRepository", "Multi-page upload failed: HTTP ${e.code()}, body: $errorBody")
            Result.failure(PaperlessException.fromHttpCode(e.code(), errorBody ?: e.message()))
        } catch (e: IllegalArgumentException) {
            // Safe error message extraction (prevent secondary exceptions)
            val safeMessage = try {
                e.message ?: "PDF konnte nicht erstellt werden"
            } catch (_: Exception) {
                "PDF konnte nicht erstellt werden"
            }
            android.util.Log.e("DocumentRepository", "IllegalArgumentException during PDF creation: $safeMessage", e)
            Result.failure(PaperlessException.ContentError(safeMessage))
        } catch (e: IllegalStateException) {
            // Safe error message extraction (prevent secondary exceptions)
            val safeMessage = try {
                e.message ?: "Bild konnte nicht verarbeitet werden"
            } catch (_: Exception) {
                "Bild konnte nicht verarbeitet werden"
            }
            android.util.Log.e("DocumentRepository", "IllegalStateException during PDF creation: $safeMessage", e)
            Result.failure(PaperlessException.ContentError(safeMessage))
        } catch (e: Exception) {
            // Catch-all for any unexpected exceptions (including iText7 internal errors)
            val safeMessage = try {
                e.message?.takeIf { it.isNotBlank() } ?: "Unbekannter Fehler bei PDF-Erstellung"
            } catch (_: Exception) {
                "Unbekannter Fehler bei PDF-Erstellung"
            }
            android.util.Log.e("DocumentRepository", "Unexpected exception during multi-page upload: ${e.javaClass.simpleName} - $safeMessage", e)
            Result.failure(PaperlessException.ContentError(safeMessage))
        }
    }

    private fun createPdfFromImages(uris: List<Uri>): File {
        val fileName = "document_${System.currentTimeMillis()}.pdf"
        val pdfFile = File(context.cacheDir, fileName)

        try {
            PdfWriter(pdfFile).use { writer ->
                PdfDocument(writer).use { pdfDoc ->
                    ITextDocument(pdfDoc).use { document ->
                        uris.forEachIndexed { index, uri ->
                            try {
                                val imageBytes = getImageBytesFromUri(uri)
                                val imageData = ImageDataFactory.create(imageBytes)
                                val image = Image(imageData)

                                // Calculate page size based on image dimensions
                                val pageWidth = image.imageWidth
                                val pageHeight = image.imageHeight
                                val pageSize = PageSize(pageWidth, pageHeight)

                                // Add new page with image dimensions
                                pdfDoc.addNewPage(pageSize)

                                // Scale image to fit page
                                image.setFixedPosition(index + 1, 0f, 0f)
                                image.scaleToFit(pageWidth, pageHeight)

                                document.add(image)
                            } catch (e: Exception) {
                                // Log but continue with next image (partial PDF better than none)
                                android.util.Log.e("DocumentRepository", "Failed to add image ${index + 1}/${uris.size} to PDF: ${e.message}", e)
                                // If first image fails, rethrow (can't create empty PDF)
                                if (index == 0) {
                                    throw IllegalStateException("Erstes Bild konnte nicht verarbeitet werden", e)
                                }
                            }
                        }

                        // Verify we have at least one page
                        if (pdfDoc.numberOfPages == 0) {
                            throw IllegalStateException("PDF konnte nicht erstellt werden - keine Seiten hinzugefügt")
                        }
                    }
                }
            }

            // Verify PDF file was created and is not empty
            if (!pdfFile.exists() || pdfFile.length() == 0L) {
                throw IllegalStateException("PDF-Datei wurde nicht korrekt erstellt")
            }

            return pdfFile
        } catch (e: Exception) {
            // Clean up partial file on error
            if (pdfFile.exists()) {
                pdfFile.delete()
            }
            // Re-throw with more context
            throw when (e) {
                is IllegalStateException -> e
                is IllegalArgumentException -> e
                else -> IllegalStateException("PDF-Erstellung fehlgeschlagen: ${e.message}", e)
            }
        }
    }

    private fun getImageBytesFromUri(uri: Uri): ByteArray {
        // First pass: Get image dimensions without loading into memory
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }

        // Calculate sample size for large images to prevent OOM
        val maxPixels = 16_000_000L // 16MP max
        val imagePixels = options.outWidth.toLong() * options.outHeight.toLong()
        val sampleSize = if (imagePixels > maxPixels) {
            var sample = 1
            while ((options.outWidth / sample) * (options.outHeight / sample) > maxPixels) {
                sample *= 2
            }
            sample
        } else {
            1
        }

        // Second pass: Load the actual bitmap with calculated sample size
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }

        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open input stream for URI: $uri")

        val bitmap = inputStream.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
                ?: throw IllegalStateException("Failed to decode image from URI: $uri")
        }

        return try {
            val quality = calculateCompressionQuality(bitmap)
            ByteArrayOutputStream().use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
                outputStream.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun calculateCompressionQuality(bitmap: Bitmap): Int {
        val pixels = bitmap.width.toLong() * bitmap.height.toLong()
        return when {
            pixels > 12_000_000 -> 70  // >12MP: aggressive compression
            pixels > 8_000_000 -> 75   // >8MP: moderate compression
            pixels > 4_000_000 -> 80   // >4MP: light compression
            else -> 85                  // <=4MP: high quality
        }
    }

    private fun getFileFromUri(uri: Uri): File {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open input stream for URI: $uri")

        val fileName = "document_${System.currentTimeMillis()}.jpg"
        val tempFile = File(context.cacheDir, fileName)

        FileOutputStream(tempFile).use { outputStream ->
            inputStream.copyTo(outputStream)
        }
        inputStream.close()

        return tempFile
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
    ): Flow<List<Document>> {
        return cachedDocumentDao.observeDocuments(
            limit = pageSize,
            offset = (page - 1) * pageSize
        ).map { cachedList ->
            cachedList.map { it.toCachedDomain() }
        }
    }

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
    ): Flow<Int> {
        val query = com.paperless.scanner.data.database.DocumentFilterQueryBuilder.buildCountQuery(
            searchQuery = searchQuery,
            filter = filter
        )
        return cachedDocumentDao.getCountWithFilter(query)
    }

    /**
     * Get count of untagged documents (for Smart Tagging).
     * Reactively updates when documents are tagged or new documents are added.
     *
     * @return Flow that emits count of documents without tags
     */
    fun observeUntaggedDocumentsCount(): Flow<Int> {
        return cachedDocumentDao.observeUntaggedCount()
    }

    /**
     * Get all untagged documents from local cache (for Smart Tagging screen).
     * Uses offline-first approach - returns cached documents immediately.
     *
     * @return Result with list of untagged documents ordered by most recent
     */
    suspend fun getUntaggedDocuments(): Result<List<Document>> {
        return try {
            val cachedDocs = cachedDocumentDao.getUntaggedDocuments()
            Result.success(cachedDocs.map { it.toCachedDomain() })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

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
    ): Flow<PagingData<Document>> {
        return Pager(
            config = PagingConfig(
                pageSize = 100,
                maxSize = 500, // Memory limit: max 500 items in memory
                enablePlaceholders = false
            ),
            pagingSourceFactory = {
                val query = com.paperless.scanner.data.database.DocumentFilterQueryBuilder.buildPagingQuery(
                    searchQuery = searchQuery,
                    filter = filter
                )
                cachedDocumentDao.getDocumentsPagingSource(query)
            }
        ).flow.map { pagingData ->
            // Map CachedDocument to Domain Document
            pagingData.map { it.toCachedDomain() }
        }
    }

    suspend fun getDocuments(
        page: Int = 1,
        pageSize: Int = 25,
        query: String? = null,
        tagIds: List<Int>? = null,
        correspondentId: Int? = null,
        documentTypeId: Int? = null,
        ordering: String = "-created",
        forceRefresh: Boolean = false
    ): Result<DocumentsResponse> {
        return try {
            // Offline-First: Try cache first unless forceRefresh
            if (!forceRefresh || !networkMonitor.checkOnlineStatus()) {
                val cachedDocs = cachedDocumentDao.getDocuments(
                    limit = pageSize,
                    offset = (page - 1) * pageSize
                )

                if (cachedDocs.isNotEmpty()) {
                    val totalCount = cachedDocumentDao.getCount()
                    val domainDocs = cachedDocs.map { it.toCachedDomain() }

                    return Result.success(
                        DocumentsResponse(
                            count = totalCount,
                            next = if ((page * pageSize) < totalCount) "next" else null,
                            previous = if (page > 1) "prev" else null,
                            results = domainDocs
                        )
                    )
                }
            }

            // Network fetch (if online and forceRefresh or cache empty)
            if (networkMonitor.checkOnlineStatus()) {
                val tagIdsString = tagIds?.takeIf { it.isNotEmpty() }?.joinToString(",")
                val response = api.getDocuments(
                    page = page,
                    pageSize = pageSize,
                    query = query,
                    tagIds = tagIdsString,
                    correspondentId = correspondentId,
                    documentTypeId = documentTypeId,
                    ordering = ordering
                )

                // Update cache
                val cachedEntities = response.results.map { it.toCachedEntity() }
                cachedDocumentDao.insertAll(cachedEntities)

                Result.success(response.toDomain())
            } else {
                // Offline, no cache
                Result.failure(PaperlessException.NetworkError(IOException("Offline, keine gecachten Daten")))
            }
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    suspend fun getDocument(id: Int, forceRefresh: Boolean = false): Result<Document> {
        return try {
            // For detail view, always fetch from network to get full data (notes, permissions, etc.)
            if (forceRefresh || networkMonitor.checkOnlineStatus()) {
                return try {
                    val doc = api.getDocument(id)
                    // Cache basic document data
                    cachedDocumentDao.insert(doc.toCachedEntity())
                    Result.success(doc.toDomain())
                } catch (e: retrofit2.HttpException) {
                    // If network fails, try cache
                    val cached = cachedDocumentDao.getDocument(id)
                    if (cached != null) {
                        Result.success(cached.toCachedDomain())
                    } else {
                        Result.failure(PaperlessException.fromHttpCode(e.code(), e.message()))
                    }
                } catch (e: Exception) {
                    // If network fails, try cache
                    val cached = cachedDocumentDao.getDocument(id)
                    if (cached != null) {
                        Result.success(cached.toCachedDomain())
                    } else {
                        Result.failure(PaperlessException.from(e))
                    }
                }
            }

            // Offline: use cache
            val cached = cachedDocumentDao.getDocument(id)
            if (cached != null) {
                Result.success(cached.toCachedDomain())
            } else {
                Result.failure(PaperlessException.ClientError(404, "Dokument nicht im Cache"))
            }
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

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
    fun observeDocument(id: Int): Flow<Document?> {
        return cachedDocumentDao.observeDocument(id)
            .map { it?.toCachedDomain() }
    }

    suspend fun searchDocuments(query: String): Result<List<Document>> {
        return try {
            val cachedResults = cachedDocumentDao.searchDocuments(query)
            Result.success(cachedResults.map { it.toCachedDomain() })
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    suspend fun getDocumentCount(forceRefresh: Boolean = false): Result<Int> {
        return try {
            // BEST PRACTICE: For stats/counts, prefer server over cache to avoid stale data
            // especially in multi-client scenarios (web + mobile)
            if (!forceRefresh) {
                // Try cache first only when explicitly not forcing refresh
                val count = cachedDocumentDao.getCount()
                if (count > 0 || !networkMonitor.checkOnlineStatus()) {
                    return Result.success(count)
                }
            }

            // Fetch from network (forced or cache empty/offline)
            if (networkMonitor.checkOnlineStatus()) {
                safeApiCall {
                    api.getDocuments(page = 1, pageSize = 1).count
                }
            } else {
                // Offline fallback: use cache
                Result.success(cachedDocumentDao.getCount())
            }
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    suspend fun getRecentDocuments(limit: Int = 5): Result<List<Document>> {
        return try {
            // Try cache first
            val cached = cachedDocumentDao.getDocuments(limit = limit, offset = 0)
            if (cached.isNotEmpty() || !networkMonitor.checkOnlineStatus()) {
                return Result.success(cached.map { it.toCachedDomain() })
            }

            // Fallback to network
            safeApiCall {
                api.getDocuments(
                    page = 1,
                    pageSize = limit,
                    ordering = "-added"
                ).results.toDomain()
            }
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    suspend fun getUntaggedCount(): Result<Int> = safeApiCall {
        api.getDocuments(
            page = 1,
            pageSize = 1,
            tagsIsNull = true
        ).count
    }

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

    suspend fun deleteDocument(documentId: Int): Result<Unit> {
        return try {
            if (networkMonitor.checkOnlineStatus()) {
                // CASCADE CLEANUP STEP 1: Get all unacknowledged task IDs for this document
                // Must happen BEFORE deletion to get task IDs
                val tasks = cachedTaskDao.getAllTasks()
                    .filter { it.relatedDocument == documentId.toString() && !it.acknowledged }
                val taskIds = tasks.map { it.id }

                // OPTIMISTIC UI: Soft-delete locally FIRST for immediate UI feedback
                // This is critical for Gmail-style swipe animations where the card
                // slides off-screen before the API call completes
                val deletedAt = System.currentTimeMillis()
                cachedDocumentDao.softDelete(documentId, deletedAt = deletedAt)
                cachedTaskDao.acknowledgeTasksForDocument(documentId.toString())

                // Online: Delete via API (wrapped in try-catch for rollback on exception)
                try {
                    val response = api.deleteDocument(documentId)

                    if (response.isSuccessful) {
                        // CASCADE CLEANUP STEP 2: Acknowledge tasks on SERVER
                        if (taskIds.isNotEmpty()) {
                            try {
                                val ackRequest = com.paperless.scanner.data.api.models.AcknowledgeTasksRequest(taskIds)
                                api.acknowledgeTasks(ackRequest)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to acknowledge tasks on server: ${e.message}")
                                // Continue anyway - local cleanup is more important for UX
                            }
                        }
                        // Success: local state already updated, nothing more to do
                        Result.success(Unit)
                    } else {
                        // API FAILED: Rollback optimistic delete by restoring the document
                        Log.w(TAG, "deleteDocument API failed (HTTP ${response.code()}), rolling back optimistic delete")
                        cachedDocumentDao.restoreDocument(documentId)

                        // Extract actual error body from server response
                        val errorBody = try {
                            response.errorBody()?.string()
                        } catch (_: Exception) {
                            null
                        }
                        Log.e(TAG, "deleteDocument failed: HTTP ${response.code()}, body: $errorBody")
                        Result.failure(
                            PaperlessException.fromHttpCode(
                                response.code(),
                                errorBody ?: response.message()
                            )
                        )
                    }
                } catch (e: Exception) {
                    // API EXCEPTION (timeout, network error): Rollback optimistic delete
                    Log.w(TAG, "deleteDocument API exception, rolling back optimistic delete: ${e.message}")
                    cachedDocumentDao.restoreDocument(documentId)
                    throw e // Re-throw to be caught by outer catch block
                }
            } else {
                // Offline: Queue deletion for sync
                val pendingChange = PendingChange(
                    entityType = "document",
                    entityId = documentId,
                    changeType = "delete",
                    changeData = "{}"
                )
                pendingChangeDao.insert(pendingChange)

                // CASCADE CLEANUP: Acknowledge tasks for this document
                // CRITICAL: Must happen BEFORE soft delete so reactivity works
                cachedTaskDao.acknowledgeTasksForDocument(documentId.toString())

                // Soft delete from local cache immediately for UX
                // BEST PRACTICE: Use current time as deletion timestamp (local delete)
                // When synced from server later, 'modified' field will override this
                cachedDocumentDao.softDelete(documentId, deletedAt = System.currentTimeMillis())

                Result.success(Unit)
            }
        } catch (e: retrofit2.HttpException) {
            Result.failure(PaperlessException.fromHttpCode(e.code(), e.message()))
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
        created: String? = null
    ): Result<Document> {
        return try {
            // Check if server is reachable (internet + server online)
            if (serverHealthMonitor.isServerReachable.value) {
                // Get old tags before update (for document count adjustment)
                val oldTagIds = if (tags != null) {
                    getOldTagIds(documentId)
                } else null

                // Online: Update via API
                val request = UpdateDocumentRequest(
                    title = title,
                    tags = tags,
                    correspondent = correspondent,
                    documentType = documentType,
                    archiveSerialNumber = archiveSerialNumber,
                    created = created
                )

                val updatedDocument = api.updateDocument(documentId, request)

                // Update cache
                cachedDocumentDao.insert(updatedDocument.toCachedEntity())

                // BEST PRACTICE: Update tag document counts when tags change
                // This ensures LabelsScreen shows correct counts immediately
                if (tags != null && oldTagIds != null) {
                    updateTagDocumentCounts(oldTagIds, tags)
                }

                Result.success(updatedDocument.toDomain())
            } else {
                // Offline: Queue update for sync
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
                    changeData = changeData
                )
                pendingChangeDao.insert(pendingChange)

                // Update local cache optimistically
                val cached = cachedDocumentDao.getDocument(documentId)
                if (cached != null) {
                    Result.success(cached.toCachedDomain())
                } else {
                    Result.failure(PaperlessException.ClientError(404, "Dokument nicht im Cache"))
                }
            }
        } catch (e: retrofit2.HttpException) {
            Result.failure(PaperlessException.fromHttpCode(e.code(), e.message()))
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    /**
     * Gets the current tag IDs from a cached document.
     */
    private suspend fun getOldTagIds(documentId: Int): List<Int> {
        return try {
            val cached = cachedDocumentDao.getDocument(documentId)
            if (cached != null) {
                val listType = object : TypeToken<List<Int>>() {}.type
                gson.fromJson(cached.tags, listType) ?: emptyList()
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Updates tag document counts when tags are added/removed from a document.
     * Decrements count for removed tags, increments count for added tags.
     * This ensures LabelsScreen shows correct counts immediately.
     */
    private suspend fun updateTagDocumentCounts(oldTagIds: List<Int>, newTagIds: List<Int>) {
        try {
            val oldSet = oldTagIds.toSet()
            val newSet = newTagIds.toSet()

            // Tags that were removed: decrement count
            val removedTags = oldSet - newSet
            removedTags.forEach { tagId ->
                cachedTagDao.updateDocumentCount(tagId, -1)
            }

            // Tags that were added: increment count
            val addedTags = newSet - oldSet
            addedTags.forEach { tagId ->
                cachedTagDao.updateDocumentCount(tagId, 1)
            }
        } catch (e: Exception) {
            // Log but don't fail - cache update is best effort
            // Server is already in sync, cache will update on next full sync
        }
    }

    suspend fun getDocumentHistory(documentId: Int): Result<List<AuditLogEntry>> {
        return try {
            if (networkMonitor.checkOnlineStatus()) {
                val history = api.getDocumentHistory(documentId)
                Result.success(history.toAuditLogDomain())
            } else {
                Result.failure(PaperlessException.NetworkError(IOException("Offline")))
            }
        } catch (e: retrofit2.HttpException) {
            Result.failure(PaperlessException.fromHttpCode(e.code(), e.message()))
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    suspend fun addNote(documentId: Int, noteText: String): Result<List<com.paperless.scanner.domain.model.Note>> {
        return try {
            if (networkMonitor.checkOnlineStatus()) {
                val request = com.paperless.scanner.data.api.models.CreateNoteRequest(note = noteText)
                val notes = api.addNote(documentId, request)
                Result.success(notes.map { it.toDomain() })
            } else {
                Result.failure(PaperlessException.NetworkError(IOException("Offline")))
            }
        } catch (e: retrofit2.HttpException) {
            Result.failure(PaperlessException.fromHttpCode(e.code(), e.message()))
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    suspend fun deleteNote(documentId: Int, noteId: Int): Result<List<com.paperless.scanner.domain.model.Note>> {
        return try {
            if (networkMonitor.checkOnlineStatus()) {
                val notes = api.deleteNote(documentId, noteId)
                Result.success(notes.map { it.toDomain() })
            } else {
                Result.failure(PaperlessException.NetworkError(IOException("Offline")))
            }
        } catch (e: retrofit2.HttpException) {
            Result.failure(PaperlessException.fromHttpCode(e.code(), e.message()))
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    // User and Group methods for permissions management

    suspend fun getUsers(): Result<List<com.paperless.scanner.data.api.models.User>> {
        return try {
            if (networkMonitor.checkOnlineStatus()) {
                val response = api.getUsers()
                Result.success(response.results)
            } else {
                Result.failure(PaperlessException.NetworkError(IOException("Offline")))
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
                Result.failure(PaperlessException.NetworkError(IOException("Offline")))
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
    ): Result<Document> {
        return try {
            if (networkMonitor.checkOnlineStatus()) {
                val request = UpdateDocumentWithPermissionsRequest(
                    owner = owner,
                    setPermissions = SetPermissionsRequest(
                        view = PermissionSet(users = viewUsers, groups = viewGroups),
                        change = PermissionSet(users = changeUsers, groups = changeGroups)
                    )
                )

                val updatedDocument = api.updateDocumentPermissions(documentId, request)

                // Update cache
                cachedDocumentDao.insert(updatedDocument.toCachedEntity())

                Result.success(updatedDocument.toDomain())
            } else {
                Result.failure(PaperlessException.NetworkError(IOException("Offline")))
            }
        } catch (e: retrofit2.HttpException) {
            Result.failure(PaperlessException.fromHttpCode(e.code(), e.message()))
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    // ========================================
    // TRASH METHODS (Paperless-ngx v2.20+)
    // ========================================

    /**
     * Observe deleted documents from local cache (TrashScreen).
     * BEST PRACTICE: Reactive Flow for automatic UI updates.
     *
     * @return Flow that emits list of deleted documents (ordered by deletedAt DESC)
     */
    fun observeTrashDocuments(): Flow<List<Document>> {
        return cachedDocumentDao.observeDeletedDocuments().map { cachedList ->
            cachedList.map { it.toCachedDomain() }
        }
    }

    /**
     * Get count of documents in trash (for TrashScreen badge).
     *
     * @return Flow that emits count of deleted documents
     */
    fun observeTrashCount(): Flow<Int> {
        return cachedDocumentDao.observeDeletedCount()
    }

    /**
     * Get trash documents from server (force refresh).
     * Fetches deleted documents from Paperless-ngx trash and updates cache.
     *
     * @param page Page number
     * @param pageSize Results per page
     * @return Result with DocumentsResponse containing deleted documents
     */
    suspend fun getTrashDocuments(
        page: Int = 1,
        pageSize: Int = 25
    ): Result<DocumentsResponse> {
        return try {
            if (networkMonitor.checkOnlineStatus()) {
                val response = api.getTrash(page = page, pageSize = pageSize)

                // Update cache with deleted documents (keep isDeleted = true)
                // BEST PRACTICE: Use 'modified' field as deletion timestamp
                // When a document is deleted, Paperless-ngx updates 'modified' to deletion time
                val cachedEntities = response.results.map { doc ->
                    val deletionTimestamp = try {
                        // Parse ISO 8601 date from API (e.g., "2024-01-27T14:30:00Z")
                        java.time.Instant.parse(doc.modified).toEpochMilli()
                    } catch (e: Exception) {
                        // Fallback: If parsing fails, use current time
                        android.util.Log.e("DocumentRepository", "Failed to parse modified date: ${doc.modified}", e)
                        System.currentTimeMillis()
                    }

                    doc.toCachedEntity().copy(
                        isDeleted = true,
                        deletedAt = deletionTimestamp
                    )
                }
                cachedDocumentDao.insertAll(cachedEntities)

                Result.success(response.toDomain())
            } else {
                Result.failure(PaperlessException.NetworkError(IOException("Offline")))
            }
        } catch (e: retrofit2.HttpException) {
            Result.failure(PaperlessException.fromHttpCode(e.code(), e.message()))
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    /**
     * Restore document from trash (single document).
     * BEST PRACTICE: Offline-First with PendingChange queue.
     *
     * @param documentId Document ID to restore
     * @return Result<Unit>
     */
    suspend fun restoreDocument(documentId: Int): Result<Unit> {
        return try {
            if (networkMonitor.checkOnlineStatus()) {
                // Online: Call API
                val request = TrashBulkActionRequest(
                    documents = listOf(documentId),
                    action = "restore"
                )
                val response = api.trashBulkAction(request)

                if (response.isSuccessful) {
                    // Update local cache: remove isDeleted flag
                    cachedDocumentDao.restoreDocument(documentId)
                    Result.success(Unit)
                } else {
                    // Extract actual error body from server response
                    val errorBody = try {
                        response.errorBody()?.string()
                    } catch (_: Exception) {
                        null
                    }
                    Log.e(TAG, "restoreDocument failed: HTTP ${response.code()}, body: $errorBody")
                    Result.failure(
                        PaperlessException.fromHttpCode(
                            response.code(),
                            errorBody ?: response.message()
                        )
                    )
                }
            } else {
                // Offline: Queue restore for sync
                val pendingChange = PendingChange(
                    entityType = "trash",
                    entityId = documentId,
                    changeType = "restore",
                    changeData = "{}"
                )
                pendingChangeDao.insert(pendingChange)

                // Optimistically restore in local cache
                cachedDocumentDao.restoreDocument(documentId)

                Result.success(Unit)
            }
        } catch (e: retrofit2.HttpException) {
            Result.failure(PaperlessException.fromHttpCode(e.code(), e.message()))
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    /**
     * Restore multiple documents from trash (bulk restore).
     * BEST PRACTICE: Offline-First with PendingChange queue.
     *
     * @param documentIds List of document IDs to restore
     * @return Result<Unit>
     */
    suspend fun restoreDocuments(documentIds: List<Int>): Result<Unit> {
        return try {
            if (networkMonitor.checkOnlineStatus()) {
                // Online: Call API
                val request = TrashBulkActionRequest(
                    documents = documentIds,
                    action = "restore"
                )
                val response = api.trashBulkAction(request)

                if (response.isSuccessful) {
                    // Update local cache: remove isDeleted flag for all
                    cachedDocumentDao.restoreDocuments(documentIds)
                    Result.success(Unit)
                } else {
                    // Extract actual error body from server response
                    val errorBody = try {
                        response.errorBody()?.string()
                    } catch (_: Exception) {
                        null
                    }
                    Log.e(TAG, "restoreDocuments failed: HTTP ${response.code()}, body: $errorBody")
                    Result.failure(
                        PaperlessException.fromHttpCode(
                            response.code(),
                            errorBody ?: response.message()
                        )
                    )
                }
            } else {
                // Offline: Queue restore for each document
                documentIds.forEach { docId ->
                    val pendingChange = PendingChange(
                        entityType = "trash",
                        entityId = docId,
                        changeType = "restore",
                        changeData = "{}"
                    )
                    pendingChangeDao.insert(pendingChange)
                }

                // Optimistically restore in local cache
                cachedDocumentDao.restoreDocuments(documentIds)

                Result.success(Unit)
            }
        } catch (e: retrofit2.HttpException) {
            Result.failure(PaperlessException.fromHttpCode(e.code(), e.message()))
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    /**
     * Permanently delete document from trash (hard delete).
     * BEST PRACTICE: Offline-First with PendingChange queue.
     *
     * @param documentId Document ID to permanently delete
     * @return Result<Unit>
     */
    suspend fun permanentlyDeleteDocument(documentId: Int): Result<Unit> {
        return try {
            if (networkMonitor.checkOnlineStatus()) {
                // Online: Call API for permanent deletion
                val request = TrashBulkActionRequest(
                    documents = listOf(documentId),
                    action = "empty"  // CORRECT: Valid actions are "restore" and "empty"
                )
                val response = api.trashBulkAction(request)

                if (response.isSuccessful) {
                    // Hard delete from local cache
                    cachedDocumentDao.hardDelete(documentId)
                    Result.success(Unit)
                } else {
                    // Extract actual error body from server response
                    val errorBody = try {
                        response.errorBody()?.string()
                    } catch (_: Exception) {
                        null
                    }
                    Log.e(TAG, "permanentlyDeleteDocument failed: HTTP ${response.code()}, body: $errorBody")
                    Result.failure(
                        PaperlessException.fromHttpCode(
                            response.code(),
                            errorBody ?: response.message()
                        )
                    )
                }
            } else {
                // Offline: Queue permanent delete for sync
                val pendingChange = PendingChange(
                    entityType = "trash",
                    entityId = documentId,
                    changeType = "delete",
                    changeData = "{}"
                )
                pendingChangeDao.insert(pendingChange)

                // Hard delete from local cache (optimistic)
                cachedDocumentDao.hardDelete(documentId)

                Result.success(Unit)
            }
        } catch (e: retrofit2.HttpException) {
            Result.failure(PaperlessException.fromHttpCode(e.code(), e.message()))
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    /**
     * Permanently delete multiple documents from trash (bulk hard delete).
     * BEST PRACTICE: Offline-First with PendingChange queue.
     *
     * @param documentIds List of document IDs to permanently delete
     * @return Result<Unit>
     */
    suspend fun permanentlyDeleteDocuments(documentIds: List<Int>): Result<Unit> {
        return try {
            if (networkMonitor.checkOnlineStatus()) {
                // Online: Call API for permanent deletion
                val request = TrashBulkActionRequest(
                    documents = documentIds,
                    action = "empty"  // CORRECT: Valid actions are "restore" and "empty"
                )
                val response = api.trashBulkAction(request)

                if (response.isSuccessful) {
                    // Hard delete from local cache
                    cachedDocumentDao.deleteByIds(documentIds)
                    Result.success(Unit)
                } else {
                    // Extract actual error body from server response
                    val errorBody = try {
                        response.errorBody()?.string()
                    } catch (_: Exception) {
                        null
                    }
                    Log.e(TAG, "permanentlyDeleteDocuments failed: HTTP ${response.code()}, body: $errorBody")
                    Result.failure(
                        PaperlessException.fromHttpCode(
                            response.code(),
                            errorBody ?: response.message()
                        )
                    )
                }
            } else {
                // Offline: Queue permanent delete for each document
                documentIds.forEach { docId ->
                    val pendingChange = PendingChange(
                        entityType = "trash",
                        entityId = docId,
                        changeType = "delete",
                        changeData = "{}"
                    )
                    pendingChangeDao.insert(pendingChange)
                }

                // Hard delete from local cache (optimistic)
                cachedDocumentDao.deleteByIds(documentIds)

                Result.success(Unit)
            }
        } catch (e: retrofit2.HttpException) {
            Result.failure(PaperlessException.fromHttpCode(e.code(), e.message()))
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    /**
     * Get old deleted documents for auto-cleanup (WorkManager).
     * Returns document IDs that have been in trash longer than retention period.
     *
     * @param retentionDays Number of days to keep documents in trash (default 30)
     * @return Result with list of document IDs to permanently delete
     */
    suspend fun getOldDeletedDocumentIds(retentionDays: Int = 30): Result<List<Int>> {
        return try {
            val cutoffTime = System.currentTimeMillis() - (retentionDays * 24 * 60 * 60 * 1000L)
            val ids = cachedDocumentDao.getOldDeletedDocumentIds(cutoffTime)
            Result.success(ids)
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    /**
     * BEST PRACTICE: Reactive Flow for Trash Screen.
     * Observe all soft-deleted documents from Room cache.
     * Automatically updates UI when documents are deleted/restored.
     *
     * Returns CachedDocument (not Domain Document) because deletedAt field
     * only exists in cache layer (not in API response).
     *
     * @return Flow<List<CachedDocument>> - Emits list of deleted documents, sorted by deletion time (newest first)
     */
    fun observeTrashedDocuments(): Flow<List<com.paperless.scanner.data.database.entities.CachedDocument>> {
        return cachedDocumentDao.observeDeletedDocuments()
    }

    /**
     * Get count of documents currently in trash.
     * Used for badge display on TrashScreen navigation.
     *
     * @return Flow<Int> - Emits count of deleted documents
     */
    fun observeTrashedDocumentsCount(): Flow<Int> {
        return cachedDocumentDao.observeDeletedCount()
    }

    /**
     * Get oldest deletion timestamp for expiration countdown.
     * Used by HomeScreen TrashCard to show "Expires in X days".
     *
     * @return Flow<Long?> - Emits oldest deletedAt timestamp, or null if trash is empty
     */
    fun observeOldestDeletedTimestamp(): Flow<Long?> {
        return cachedDocumentDao.getOldestDeletedTimestamp()
    }

    /**
     * Remove local soft-deleted documents that no longer exist on the server.
     * Called after a full trash sync (all pages fetched) to keep local cache consistent.
     *
     * @param serverTrashIds Set of document IDs currently in server trash
     */
    suspend fun cleanupOrphanedTrashDocs(serverTrashIds: Set<Int>) {
        val localDeletedIds = cachedDocumentDao.getDeletedIds().toSet()
        val orphanedIds = localDeletedIds - serverTrashIds
        if (orphanedIds.isNotEmpty()) {
            cachedDocumentDao.deleteByIds(orphanedIds.toList())
            Log.d(TAG, "Cleaned up ${orphanedIds.size} orphaned trash docs: $orphanedIds")
        }
    }
}
