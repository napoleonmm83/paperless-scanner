package com.paperless.scanner.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
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
import com.paperless.scanner.data.api.models.PermissionSet
import com.paperless.scanner.data.api.models.SetPermissionsRequest
import com.paperless.scanner.data.api.models.UpdateDocumentRequest
import com.paperless.scanner.data.api.models.UpdateDocumentWithPermissionsRequest
import com.paperless.scanner.data.api.safeApiCall
import com.paperless.scanner.data.database.dao.CachedDocumentDao
import com.paperless.scanner.data.database.dao.CachedTagDao
import com.paperless.scanner.data.database.dao.PendingChangeDao
import com.paperless.scanner.data.database.entities.PendingChange
import com.paperless.scanner.data.database.mappers.toCachedEntity
import com.paperless.scanner.data.database.mappers.toDomain as toCachedDomain
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
    private val pendingChangeDao: PendingChangeDao,
    private val networkMonitor: NetworkMonitor
) {
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
            val pdfFile = createPdfFromImages(uris)

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

            val response = api.uploadDocument(
                document = documentPart,
                title = titleBody,
                tags = tagsParts,
                documentType = documentTypeBody,
                correspondent = correspondentBody
            )

            pdfFile.delete()

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
            android.util.Log.e("DocumentRepository", "Multi-page upload failed: HTTP ${e.code()}, body: $errorBody")
            Result.failure(PaperlessException.fromHttpCode(e.code(), errorBody ?: e.message()))
        } catch (e: IllegalArgumentException) {
            Result.failure(PaperlessException.ContentError(e.message ?: "PDF konnte nicht erstellt werden"))
        } catch (e: IllegalStateException) {
            Result.failure(PaperlessException.ContentError(e.message ?: "Bild konnte nicht verarbeitet werden"))
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    private fun createPdfFromImages(uris: List<Uri>): File {
        val fileName = "document_${System.currentTimeMillis()}.pdf"
        val pdfFile = File(context.cacheDir, fileName)

        PdfWriter(pdfFile).use { writer ->
            PdfDocument(writer).use { pdfDoc ->
                ITextDocument(pdfDoc).use { document ->
                    uris.forEachIndexed { index, uri ->
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
                    }
                }
            }
        }

        return pdfFile
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
     * BEST PRACTICE: Reactive Flow with filters for offline-first search and tag filtering.
     * Supports combining text search and tag filter simultaneously.
     * Automatically triggers background API sync when online.
     *
     * @param searchQuery Text search in title/content (null = no search)
     * @param tagId Single tag ID filter (null = no tag filter)
     * @param page Page number for pagination
     * @param pageSize Results per page
     * @return Flow that emits filtered documents and updates automatically
     */
    fun observeDocumentsFiltered(
        searchQuery: String? = null,
        tagId: Int? = null,
        page: Int = 1,
        pageSize: Int = 25
    ): Flow<List<Document>> {
        val query = searchQuery?.takeIf { it.isNotBlank() }

        return cachedDocumentDao.observeDocumentsFiltered(
            searchQuery = query,
            tagId = tagId,
            limit = pageSize,
            offset = (page - 1) * pageSize
        ).map { cachedList ->
            cachedList.map { it.toCachedDomain() }
        }
    }

    /**
     * Get total count of filtered documents as reactive Flow.
     */
    fun observeFilteredCount(
        searchQuery: String? = null,
        tagId: Int? = null
    ): Flow<Int> {
        val query = searchQuery?.takeIf { it.isNotBlank() }
        return cachedDocumentDao.getFilteredCount(searchQuery = query, tagId = tagId)
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
                // Online: Delete via API
                val response = api.deleteDocument(documentId)

                if (response.isSuccessful) {
                    // Hard delete from cache - document is confirmed deleted on server
                    cachedDocumentDao.hardDelete(documentId)
                    Result.success(Unit)
                } else {
                    Result.failure(
                        PaperlessException.fromHttpCode(
                            response.code(),
                            response.message()
                        )
                    )
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

                // Soft delete from local cache immediately for UX
                // Will be hard-deleted during next sync when deletion is pushed
                cachedDocumentDao.softDelete(documentId)

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
            if (networkMonitor.checkOnlineStatus()) {
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
}
