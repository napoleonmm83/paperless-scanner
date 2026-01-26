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
import com.paperless.scanner.data.health.ServerHealthMonitor
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.domain.mapper.toAuditLogDomain
import com.paperless.scanner.domain.mapper.toDomain
import com.paperless.scanner.domain.model.AuditLogEntry
import com.paperless.scanner.domain.model.Document
import com.paperless.scanner.domain.model.DocumentFilter
import com.paperless.scanner.domain.model.DocumentsResponse
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import androidx.sqlite.db.SimpleSQLiteQuery
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
    private val networkMonitor: NetworkMonitor,
    private val serverHealthMonitor: ServerHealthMonitor
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
     * BEST PRACTICE: Reactive Flow with filters for offline-first search, multi-tag and date filtering.
     * Supports combining text search, multi-tag filter (OR logic), and date range filters.
     * Automatically triggers background API sync when online.
     *
     * @param searchQuery Text search in title/content (null = no search)
     * @param tagIds Multiple tag IDs filter (empty = no tag filter, OR logic if multiple)
     * @param createdAfter Filter documents created on or after this date
     * @param createdBefore Filter documents created on or before this date
     * @param addedAfter Filter documents added on or after this date
     * @param addedBefore Filter documents added on or before this date
     * @param modifiedAfter Filter documents modified on or after this date
     * @param modifiedBefore Filter documents modified on or before this date
     * @param page Page number for pagination
     * @param pageSize Results per page
     * @return Flow that emits filtered documents and updates automatically
     */
    fun observeDocumentsFiltered(
        searchQuery: String? = null,
        tagIds: List<Int> = emptyList(),
        correspondentId: Int? = null,
        documentTypeId: Int? = null,
        hasArchiveNumber: Boolean? = null,
        createdAfter: java.time.LocalDate? = null,
        createdBefore: java.time.LocalDate? = null,
        addedAfter: java.time.LocalDate? = null,
        addedBefore: java.time.LocalDate? = null,
        modifiedAfter: java.time.LocalDate? = null,
        modifiedBefore: java.time.LocalDate? = null,
        ordering: String = "-added",
        page: Int = 1,
        pageSize: Int = 25
    ): Flow<List<Document>> {
        val query = searchQuery?.takeIf { it.isNotBlank() }
        val hasDateFilters = createdAfter != null || createdBefore != null ||
                addedAfter != null || addedBefore != null ||
                modifiedAfter != null || modifiedBefore != null
        val hasEntityFilters = correspondentId != null || documentTypeId != null || hasArchiveNumber != null

        // DEBUG: Log Room query parameters
        android.util.Log.d("DocumentRepository", """
            ========================================
            ROOM QUERY (Local Cache):
            - Search Query: $query
            - Tag IDs: $tagIds
            - Correspondent ID: $correspondentId
            - DocumentType ID: $documentTypeId
            - Has Archive Number: $hasArchiveNumber
            - Created: $createdAfter to $createdBefore
            - Added: $addedAfter to $addedBefore
            - Modified: $modifiedAfter to $modifiedBefore
            - Has date filters: $hasDateFilters
            - Has entity filters: $hasEntityFilters
            - Page: $page, Size: $pageSize
            ✅ Date + Entity + ASN filters NOW SUPPORTED in Room!
            ========================================
        """.trimIndent())

        // Use dynamic query if: multi-tag, date filters, entity filters, OR custom ordering
        return if (tagIds.size > 1 || hasDateFilters || hasEntityFilters || ordering != "-added") {
            val sqlQuery = buildMultiTagQuery(
                searchQuery = query,
                tagIds = tagIds,
                correspondentId = correspondentId,
                documentTypeId = documentTypeId,
                hasArchiveNumber = hasArchiveNumber,
                createdAfter = createdAfter,
                createdBefore = createdBefore,
                addedAfter = addedAfter,
                addedBefore = addedBefore,
                modifiedAfter = modifiedAfter,
                modifiedBefore = modifiedBefore,
                ordering = ordering,
                limit = pageSize,
                offset = (page - 1) * pageSize
            )
            cachedDocumentDao.observeDocumentsFilteredDynamic(sqlQuery)
                .map { cachedList ->
                    android.util.Log.d("DocumentRepository", "ROOM: Got ${cachedList.size} documents from cache (dynamic query)")
                    cachedList.map { it.toCachedDomain() }
                }
        } else {
            // Single tag or no filters: use original static query for better performance
            cachedDocumentDao.observeDocumentsFiltered(
                searchQuery = query,
                tagId = tagIds.firstOrNull(),
                limit = pageSize,
                offset = (page - 1) * pageSize
            ).map { cachedList ->
                android.util.Log.d("DocumentRepository", "ROOM: Got ${cachedList.size} documents from cache (static query)")
                cachedList.map { it.toCachedDomain() }
            }
        }
    }

    /**
     * Get total count of filtered documents as reactive Flow.
     * Supports multi-tag filtering with OR logic, date filtering, entity filtering, and ASN filtering.
     */
    fun observeFilteredCount(
        searchQuery: String? = null,
        tagIds: List<Int> = emptyList(),
        correspondentId: Int? = null,
        documentTypeId: Int? = null,
        hasArchiveNumber: Boolean? = null,
        createdAfter: java.time.LocalDate? = null,
        createdBefore: java.time.LocalDate? = null,
        addedAfter: java.time.LocalDate? = null,
        addedBefore: java.time.LocalDate? = null,
        modifiedAfter: java.time.LocalDate? = null,
        modifiedBefore: java.time.LocalDate? = null
    ): Flow<Int> {
        val query = searchQuery?.takeIf { it.isNotBlank() }
        val hasDateFilters = createdAfter != null || createdBefore != null ||
                addedAfter != null || addedBefore != null ||
                modifiedAfter != null || modifiedBefore != null
        val hasEntityFilters = correspondentId != null || documentTypeId != null || hasArchiveNumber != null

        // Use dynamic query for multi-tag count, date filtering, or entity filtering
        return if (tagIds.size > 1 || hasDateFilters || hasEntityFilters) {
            val sqlQuery = buildMultiTagCountQuery(
                searchQuery = query,
                tagIds = tagIds,
                correspondentId = correspondentId,
                documentTypeId = documentTypeId,
                hasArchiveNumber = hasArchiveNumber,
                createdAfter = createdAfter,
                createdBefore = createdBefore,
                addedAfter = addedAfter,
                addedBefore = addedBefore,
                modifiedAfter = modifiedAfter,
                modifiedBefore = modifiedBefore
            )
            cachedDocumentDao.getFilteredCountDynamic(sqlQuery)
        } else {
            // Single tag or no filters: use original static query
            cachedDocumentDao.getFilteredCount(searchQuery = query, tagId = tagIds.firstOrNull())
        }
    }

    /**
     * PAGING 3: Reactive Flow with automatic infinite scrolling.
     *
     * BEST PRACTICE: Replaces manual pagination with efficient Paging 3 library.
     * Automatically handles:
     * - Loading pages on demand (no manual loadNextPage() calls)
     * - Placeholder counting
     * - Database invalidation (automatic reload on DB changes)
     * - Memory efficiency (only keeps loaded pages in memory)
     *
     * Benefits over manual pagination:
     * - Simpler code (no manual page tracking)
     * - Better performance (intelligent prefetching)
     * - Built-in LoadState handling (loading/error states)
     * - Automatic cache invalidation
     *
     * Usage in ViewModel:
     * ```kotlin
     * val documentsFlow = repository.observeDocumentsPaged(filter)
     *     .cachedIn(viewModelScope)
     * ```
     *
     * Usage in UI:
     * ```kotlin
     * val lazyPagingItems = documentsFlow.collectAsLazyPagingItems()
     * LazyVerticalGrid {
     *     items(lazyPagingItems.itemCount) { index ->
     *         lazyPagingItems[index]?.let { DocumentCard(it) }
     *     }
     * }
     * ```
     *
     * @param filter DocumentFilter with all filter options (tags, dates, search, etc.)
     * @return Flow<PagingData<Document>> that updates automatically
     */
    fun observeDocumentsPaged(filter: DocumentFilter): Flow<PagingData<Document>> {
        return Pager(
            config = PagingConfig(
                pageSize = 100,           // Documents per page
                prefetchDistance = 30,    // Start loading next page when 30 items from end
                enablePlaceholders = false, // Don't show placeholders for unloaded items
                initialLoadSize = 100     // Load 100 items initially
            ),
            pagingSourceFactory = {
                // Build dynamic SQL query for filtering
                // Note: PagingSource handles LIMIT and OFFSET automatically, so we don't add them here
                val sqlQuery = buildMultiTagQueryForPaging(
                    searchQuery = filter.query?.takeIf { it.isNotBlank() },
                    tagIds = filter.tagIds,
                    correspondentId = filter.correspondentId,
                    documentTypeId = filter.documentTypeId,
                    hasArchiveNumber = filter.hasArchiveNumber,
                    createdAfter = filter.createdAfter,
                    createdBefore = filter.createdBefore,
                    addedAfter = filter.addedAfter,
                    addedBefore = filter.addedBefore,
                    modifiedAfter = filter.modifiedAfter,
                    modifiedBefore = filter.modifiedBefore,
                    ordering = filter.ordering
                )
                cachedDocumentDao.getDocumentsPagingSource(sqlQuery)
            }
        ).flow.map { pagingData ->
            // Map CachedDocument entities to Document domain models
            pagingData.map { cachedDoc ->
                cachedDoc.toCachedDomain()
            }
        }
    }

    suspend fun getDocuments(
        page: Int = 1,
        pageSize: Int = 25,
        filter: DocumentFilter = DocumentFilter(),
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
                // DEBUG: Log API request parameters
                android.util.Log.d("DocumentRepository", """
                    ========================================
                    API REQUEST:
                    - Query: ${filter.query}
                    - Tag IDs: ${filter.tagIds.takeIf { it.isNotEmpty() }?.joinToString(",")}
                    - Created: ${filter.createdAfter} to ${filter.createdBefore}
                    - Added: ${filter.addedAfter} to ${filter.addedBefore}
                    - Modified: ${filter.modifiedAfter} to ${filter.modifiedBefore}
                    - Date format: ISO 8601 (YYYY-MM-DD)
                    ========================================
                """.trimIndent())

                val response = api.getDocuments(
                    page = page,
                    pageSize = pageSize,
                    query = filter.query,
                    tagIds = filter.tagIds.takeIf { it.isNotEmpty() }?.joinToString(","),
                    tagsIsNull = filter.tagsIsNull,
                    correspondentId = filter.correspondentId,
                    documentTypeId = filter.documentTypeId,
                    createdAfter = filter.createdAfter?.toString(),
                    createdBefore = filter.createdBefore?.toString(),
                    addedAfter = filter.addedAfter?.toString(),
                    addedBefore = filter.addedBefore?.toString(),
                    modifiedAfter = filter.modifiedAfter?.toString(),
                    modifiedBefore = filter.modifiedBefore?.toString(),
                    hasArchiveNumber = filter.hasArchiveNumber,
                    storagePathId = filter.storagePathId,
                    ownerId = filter.ownerId,
                    ordering = filter.ordering
                )

                // DEBUG: Log API response
                android.util.Log.d("DocumentRepository", """
                    ========================================
                    API RESPONSE:
                    - Total count: ${response.count}
                    - Results: ${response.results.size} documents
                    - Sample dates (first 3):
                ${response.results.take(3).joinToString("\n") { doc ->
                    "  - Doc ${doc.id}: created=${doc.created}, added=${doc.added}, modified=${doc.modified}"
                }}
                    ========================================
                """.trimIndent())

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

    /**
     * Converts Paperless-ngx ordering string to SQL ORDER BY clause.
     *
     * Paperless ordering format:
     * - Prefix "-" means descending (e.g. "-created" = newest first)
     * - No prefix means ascending (e.g. "title" = A to Z)
     *
     * Supported fields:
     * - created, added, modified (dates)
     * - title (alphabetical)
     *
     * @param ordering Paperless-style ordering string (e.g. "-created", "title")
     * @return SQL ORDER BY clause (e.g. "ORDER BY created DESC", "ORDER BY title ASC")
     */
    private fun convertOrderingToSql(ordering: String): String {
        val isDescending = ordering.startsWith("-")
        val fieldName = if (isDescending) ordering.substring(1) else ordering
        val direction = if (isDescending) "DESC" else "ASC"

        // Map Paperless field names to SQL column names (if needed)
        val sqlField = when (fieldName) {
            "created" -> "created"
            "added" -> "added"
            "modified" -> "modified"
            "title" -> "title"
            else -> "added" // Fallback to default
        }

        return "ORDER BY $sqlField $direction"
    }

    /**
     * Builds dynamic SQL query for Paging 3 (WITHOUT LIMIT/OFFSET).
     * PagingSource will add LIMIT and OFFSET automatically.
     *
     * This is identical to buildMultiTagQuery() but without the final LIMIT/OFFSET clause.
     *
     * @param searchQuery Optional text search in title/content/originalFileName
     * @param tagIds List of tag IDs to filter (OR logic)
     * @param correspondentId Filter by correspondent
     * @param documentTypeId Filter by document type
     * @param hasArchiveNumber Filter by archive serial number presence
     * @param createdAfter Filter documents created on or after this date
     * @param createdBefore Filter documents created on or before this date
     * @param addedAfter Filter documents added on or after this date
     * @param addedBefore Filter documents added on or before this date
     * @param modifiedAfter Filter documents modified on or after this date
     * @param modifiedBefore Filter documents modified on or before this date
     * @param ordering Sort order (Paperless format like "-created", "title")
     * @return SimpleSQLiteQuery WITHOUT LIMIT/OFFSET (PagingSource will add them)
     */
    private fun buildMultiTagQueryForPaging(
        searchQuery: String?,
        tagIds: List<Int>,
        correspondentId: Int?,
        documentTypeId: Int?,
        hasArchiveNumber: Boolean?,
        createdAfter: java.time.LocalDate?,
        createdBefore: java.time.LocalDate?,
        addedAfter: java.time.LocalDate?,
        addedBefore: java.time.LocalDate?,
        modifiedAfter: java.time.LocalDate?,
        modifiedBefore: java.time.LocalDate?,
        ordering: String = "-added"
    ): SimpleSQLiteQuery {
        val args = mutableListOf<Any>()
        val sql = StringBuilder("SELECT * FROM cached_documents WHERE isDeleted = 0")

        // Add search filter if provided (includes tag name search)
        if (!searchQuery.isNullOrBlank()) {
            sql.append("""
                 AND (title LIKE ? ESCAPE '\' OR content LIKE ? ESCAPE '\' OR originalFileName LIKE ? ESCAPE '\'
                      OR EXISTS (
                          SELECT 1 FROM cached_tags t
                          WHERE t.name LIKE ? ESCAPE '\'
                          AND (tags LIKE '[' || t.id || ',%'
                               OR tags LIKE '%,' || t.id || ',%'
                               OR tags LIKE '%,' || t.id || ']'
                               OR tags LIKE '[' || t.id || ']')
                      ))
            """.trimIndent())
            val searchPattern = "%$searchQuery%"
            args.addAll(listOf(searchPattern, searchPattern, searchPattern, searchPattern))
        }

        // Add multi-tag filter (OR logic)
        if (tagIds.isNotEmpty()) {
            sql.append(" AND (")
            val tagClauses = tagIds.map { tagId ->
                args.addAll(listOf(
                    "[$tagId,%",
                    "%,$tagId,%",
                    "%,$tagId]",
                    "[$tagId]"
                ))
                "(tags LIKE ? OR tags LIKE ? OR tags LIKE ? OR tags LIKE ?)"
            }
            sql.append(tagClauses.joinToString(" OR "))
            sql.append(")")
        }

        // Add correspondent filter
        correspondentId?.let {
            sql.append(" AND correspondent = ?")
            args.add(it)
        }

        // Add document type filter
        documentTypeId?.let {
            sql.append(" AND documentType = ?")
            args.add(it)
        }

        // Add archive serial number filter
        hasArchiveNumber?.let {
            if (it) {
                sql.append(" AND archiveSerialNumber IS NULL")
            } else {
                sql.append(" AND archiveSerialNumber IS NOT NULL")
            }
        }

        // Add date filters
        createdAfter?.let {
            sql.append(" AND created >= ?")
            args.add(it.toString())
        }

        createdBefore?.let {
            sql.append(" AND created <= ?")
            args.add(it.toString())
        }

        addedAfter?.let {
            sql.append(" AND SUBSTR(added, 1, 10) >= ?")
            args.add(it.toString())
        }

        addedBefore?.let {
            sql.append(" AND SUBSTR(added, 1, 10) <= ?")
            args.add(it.toString())
        }

        modifiedAfter?.let {
            sql.append(" AND SUBSTR(modified, 1, 10) >= ?")
            args.add(it.toString())
        }

        modifiedBefore?.let {
            sql.append(" AND SUBSTR(modified, 1, 10) <= ?")
            args.add(it.toString())
        }

        // Add dynamic ordering (NO LIMIT/OFFSET - PagingSource adds them)
        sql.append(" ${convertOrderingToSql(ordering)}")

        return SimpleSQLiteQuery(sql.toString(), args.toTypedArray())
    }

    /**
     * Builds dynamic SQL query for multi-tag filtering with OR logic and date filtering.
     * Documents matching ANY of the selected tags are returned.
     * Date filtering uses SQLite string comparison (ISO 8601 strings are lexicographically comparable).
     *
     * Pattern explanation: Tags are stored as JSON array [1,2,3]
     * For each tag ID, we check 4 patterns:
     * - [tagId,   → First element
     * - ,tagId,   → Middle element
     * - ,tagId]   → Last element
     * - [tagId]   → Single element
     *
     * Multiple tags are combined with OR: (tag1 patterns) OR (tag2 patterns) OR ...
     *
     * Date filtering:
     * - created: Direct string comparison (YYYY-MM-DD format)
     * - added/modified: SUBSTR to extract date part from ISO DateTime (YYYY-MM-DDTHH:MM:SS...)
     *
     * @param searchQuery Optional text search in title/content/originalFileName
     * @param tagIds List of tag IDs to filter (OR logic)
     * @param createdAfter Filter documents created on or after this date
     * @param createdBefore Filter documents created on or before this date
     * @param addedAfter Filter documents added on or after this date
     * @param addedBefore Filter documents added on or before this date
     * @param modifiedAfter Filter documents modified on or after this date
     * @param modifiedBefore Filter documents modified on or before this date
     * @param ordering Sort order (Paperless format like "-created", "title")
     * @param limit Max results
     * @param offset Pagination offset
     * @return SimpleSQLiteQuery with bind parameters
     */
    private fun buildMultiTagQuery(
        searchQuery: String?,
        tagIds: List<Int>,
        correspondentId: Int?,
        documentTypeId: Int?,
        hasArchiveNumber: Boolean?,
        createdAfter: java.time.LocalDate?,
        createdBefore: java.time.LocalDate?,
        addedAfter: java.time.LocalDate?,
        addedBefore: java.time.LocalDate?,
        modifiedAfter: java.time.LocalDate?,
        modifiedBefore: java.time.LocalDate?,
        ordering: String = "-added",
        limit: Int,
        offset: Int
    ): SimpleSQLiteQuery {
        val args = mutableListOf<Any>()
        val sql = StringBuilder("SELECT * FROM cached_documents WHERE isDeleted = 0")

        // Add search filter if provided (includes tag name search)
        // BEST PRACTICE: ESCAPE '\' prevents wildcard characters (%, _) from being interpreted
        if (!searchQuery.isNullOrBlank()) {
            sql.append("""
                 AND (title LIKE ? ESCAPE '\' OR content LIKE ? ESCAPE '\' OR originalFileName LIKE ? ESCAPE '\'
                      OR EXISTS (
                          SELECT 1 FROM cached_tags t
                          WHERE t.name LIKE ? ESCAPE '\'
                          AND (tags LIKE '[' || t.id || ',%'
                               OR tags LIKE '%,' || t.id || ',%'
                               OR tags LIKE '%,' || t.id || ']'
                               OR tags LIKE '[' || t.id || ']')
                      ))
            """.trimIndent())
            val searchPattern = "%$searchQuery%"
            // Args: title, content, originalFileName, tag name
            args.addAll(listOf(searchPattern, searchPattern, searchPattern, searchPattern))
        }

        // Add multi-tag filter (OR logic)
        if (tagIds.isNotEmpty()) {
            sql.append(" AND (")
            val tagClauses = tagIds.map { tagId ->
                // Add 4 patterns for this tag ID
                args.addAll(listOf(
                    "[$tagId,%",   // First element: [1,...]
                    "%,$tagId,%",  // Middle element: [...,1,...]
                    "%,$tagId]",   // Last element: [...,1]
                    "[$tagId]"     // Single element: [1]
                ))
                "(tags LIKE ? OR tags LIKE ? OR tags LIKE ? OR tags LIKE ?)"
            }
            sql.append(tagClauses.joinToString(" OR "))
            sql.append(")")
        }

        // Add correspondent filter
        correspondentId?.let {
            sql.append(" AND correspondent = ?")
            args.add(it)
        }

        // Add document type filter
        documentTypeId?.let {
            sql.append(" AND documentType = ?")
            args.add(it)
        }

        // Add archive serial number filter
        // hasArchiveNumber=true means "without ASN" (IS NULL)
        // hasArchiveNumber=false means "with ASN" (IS NOT NULL)
        hasArchiveNumber?.let {
            if (it) {
                sql.append(" AND archiveSerialNumber IS NULL")
            } else {
                sql.append(" AND archiveSerialNumber IS NOT NULL")
            }
        }

        // Add date filters
        // BEST PRACTICE: ISO 8601 date strings are lexicographically comparable in SQLite
        // "created" is stored as YYYY-MM-DD → direct comparison
        // "added" and "modified" are stored as YYYY-MM-DDTHH:MM:SS+TZ → use SUBSTR for date part

        createdAfter?.let {
            sql.append(" AND created >= ?")
            args.add(it.toString()) // LocalDate.toString() = YYYY-MM-DD
        }

        createdBefore?.let {
            sql.append(" AND created <= ?")
            args.add(it.toString())
        }

        addedAfter?.let {
            sql.append(" AND SUBSTR(added, 1, 10) >= ?")
            args.add(it.toString()) // Extract YYYY-MM-DD from YYYY-MM-DDTHH:MM:SS...
        }

        addedBefore?.let {
            sql.append(" AND SUBSTR(added, 1, 10) <= ?")
            args.add(it.toString())
        }

        modifiedAfter?.let {
            sql.append(" AND SUBSTR(modified, 1, 10) >= ?")
            args.add(it.toString())
        }

        modifiedBefore?.let {
            sql.append(" AND SUBSTR(modified, 1, 10) <= ?")
            args.add(it.toString())
        }

        // Add dynamic ordering
        sql.append(" ${convertOrderingToSql(ordering)} LIMIT ? OFFSET ?")
        args.addAll(listOf(limit, offset))

        return SimpleSQLiteQuery(sql.toString(), args.toTypedArray())
    }

    /**
     * Builds dynamic SQL query for counting documents with multi-tag and date filtering.
     * Same logic as buildMultiTagQuery but returns COUNT(*).
     */
    private fun buildMultiTagCountQuery(
        searchQuery: String?,
        tagIds: List<Int>,
        correspondentId: Int?,
        documentTypeId: Int?,
        hasArchiveNumber: Boolean?,
        createdAfter: java.time.LocalDate?,
        createdBefore: java.time.LocalDate?,
        addedAfter: java.time.LocalDate?,
        addedBefore: java.time.LocalDate?,
        modifiedAfter: java.time.LocalDate?,
        modifiedBefore: java.time.LocalDate?
    ): SimpleSQLiteQuery {
        val args = mutableListOf<Any>()
        val sql = StringBuilder("SELECT COUNT(*) FROM cached_documents WHERE isDeleted = 0")

        // Add search filter if provided (includes tag name search)
        // BEST PRACTICE: ESCAPE '\' prevents wildcard characters (%, _) from being interpreted
        if (!searchQuery.isNullOrBlank()) {
            sql.append("""
                 AND (title LIKE ? ESCAPE '\' OR content LIKE ? ESCAPE '\' OR originalFileName LIKE ? ESCAPE '\'
                      OR EXISTS (
                          SELECT 1 FROM cached_tags t
                          WHERE t.name LIKE ? ESCAPE '\'
                          AND (tags LIKE '[' || t.id || ',%'
                               OR tags LIKE '%,' || t.id || ',%'
                               OR tags LIKE '%,' || t.id || ']'
                               OR tags LIKE '[' || t.id || ']')
                      ))
            """.trimIndent())
            val searchPattern = "%$searchQuery%"
            // Args: title, content, originalFileName, tag name
            args.addAll(listOf(searchPattern, searchPattern, searchPattern, searchPattern))
        }

        // Add multi-tag filter (OR logic)
        if (tagIds.isNotEmpty()) {
            sql.append(" AND (")
            val tagClauses = tagIds.map { tagId ->
                // Add 4 patterns for this tag ID
                args.addAll(listOf(
                    "[$tagId,%",   // First element
                    "%,$tagId,%",  // Middle element
                    "%,$tagId]",   // Last element
                    "[$tagId]"     // Single element
                ))
                "(tags LIKE ? OR tags LIKE ? OR tags LIKE ? OR tags LIKE ?)"
            }
            sql.append(tagClauses.joinToString(" OR "))
            sql.append(")")
        }

        // Add correspondent filter
        correspondentId?.let {
            sql.append(" AND correspondent = ?")
            args.add(it)
        }

        // Add document type filter
        documentTypeId?.let {
            sql.append(" AND documentType = ?")
            args.add(it)
        }

        // Add archive serial number filter (same logic as buildMultiTagQuery)
        hasArchiveNumber?.let {
            if (it) {
                sql.append(" AND archiveSerialNumber IS NULL")
            } else {
                sql.append(" AND archiveSerialNumber IS NOT NULL")
            }
        }

        // Add date filters (same logic as buildMultiTagQuery)
        createdAfter?.let {
            sql.append(" AND created >= ?")
            args.add(it.toString())
        }

        createdBefore?.let {
            sql.append(" AND created <= ?")
            args.add(it.toString())
        }

        addedAfter?.let {
            sql.append(" AND SUBSTR(added, 1, 10) >= ?")
            args.add(it.toString())
        }

        addedBefore?.let {
            sql.append(" AND SUBSTR(added, 1, 10) <= ?")
            args.add(it.toString())
        }

        modifiedAfter?.let {
            sql.append(" AND SUBSTR(modified, 1, 10) >= ?")
            args.add(it.toString())
        }

        modifiedBefore?.let {
            sql.append(" AND SUBSTR(modified, 1, 10) <= ?")
            args.add(it.toString())
        }

        return SimpleSQLiteQuery(sql.toString(), args.toTypedArray())
    }
}
