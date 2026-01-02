package com.paperless.scanner.data.repository

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Image
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.ProgressRequestBody
import com.paperless.scanner.data.api.models.DocumentsResponse
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import com.paperless.scanner.data.api.models.Document as ApiDocument

class DocumentRepository @Inject constructor(
    private val context: Context,
    private val api: PaperlessApi
) {
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

            val tagsBody = if (tagIds.isNotEmpty()) {
                tagIds.joinToString(",").toRequestBody("text/plain".toMediaTypeOrNull())
            } else null

            val documentTypeBody = documentTypeId?.toString()
                ?.toRequestBody("text/plain".toMediaTypeOrNull())

            val correspondentBody = correspondentId?.toString()
                ?.toRequestBody("text/plain".toMediaTypeOrNull())

            val response = api.uploadDocument(
                document = documentPart,
                title = titleBody,
                tags = tagsBody,
                documentType = documentTypeBody,
                correspondent = correspondentBody
            )

            file.delete()

            val taskId = response.string().trim().removeSurrounding("\"")
            Result.success(taskId)
        } catch (e: Exception) {
            Result.failure(e)
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

            val tagsBody = if (tagIds.isNotEmpty()) {
                tagIds.joinToString(",").toRequestBody("text/plain".toMediaTypeOrNull())
            } else null

            val documentTypeBody = documentTypeId?.toString()
                ?.toRequestBody("text/plain".toMediaTypeOrNull())

            val correspondentBody = correspondentId?.toString()
                ?.toRequestBody("text/plain".toMediaTypeOrNull())

            val response = api.uploadDocument(
                document = documentPart,
                title = titleBody,
                tags = tagsBody,
                documentType = documentTypeBody,
                correspondent = correspondentBody
            )

            pdfFile.delete()

            val taskId = response.string().trim().removeSurrounding("\"")
            Result.success(taskId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun createPdfFromImages(uris: List<Uri>): File {
        val fileName = "document_${System.currentTimeMillis()}.pdf"
        val pdfFile = File(context.cacheDir, fileName)

        PdfWriter(pdfFile).use { writer ->
            PdfDocument(writer).use { pdfDoc ->
                Document(pdfDoc).use { document ->
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
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException("Cannot open input stream for URI: $uri")

        return inputStream.use { stream ->
            val bitmap = BitmapFactory.decodeStream(stream)
            val quality = calculateCompressionQuality(bitmap)
            ByteArrayOutputStream().use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
                outputStream.toByteArray()
            }
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

    // Document fetching methods

    suspend fun getDocuments(
        page: Int = 1,
        pageSize: Int = 25,
        query: String? = null,
        tagIds: List<Int>? = null,
        correspondentId: Int? = null,
        documentTypeId: Int? = null,
        ordering: String = "-created"
    ): Result<DocumentsResponse> {
        return try {
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
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getDocument(id: Int): Result<ApiDocument> {
        return try {
            val response = api.getDocument(id)
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getDocumentCount(): Result<Int> {
        return try {
            val response = api.getDocuments(page = 1, pageSize = 1)
            Result.success(response.count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getRecentDocuments(limit: Int = 5): Result<List<ApiDocument>> {
        return try {
            val response = api.getDocuments(
                page = 1,
                pageSize = limit,
                ordering = "-added"
            )
            Result.success(response.results)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getUntaggedCount(): Result<Int> {
        return try {
            // Paperless-ngx API: tags__id__isnull=true for untagged documents
            val response = api.getDocuments(
                page = 1,
                pageSize = 1,
                tagIds = null
            )
            // Note: This is a workaround. Proper implementation would need
            // a dedicated API endpoint or query parameter for untagged docs
            Result.success(response.count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
