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
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.api.ProgressRequestBody
import com.paperless.scanner.data.api.safeApiCall
import com.paperless.scanner.domain.mapper.toDomain
import com.paperless.scanner.domain.model.Document
import com.paperless.scanner.domain.model.DocumentsResponse
import java.io.IOException
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

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
        } catch (e: IOException) {
            Result.failure(PaperlessException.NetworkError(e))
        } catch (e: retrofit2.HttpException) {
            Result.failure(PaperlessException.fromHttpCode(e.code(), e.message()))
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
        } catch (e: IOException) {
            Result.failure(PaperlessException.NetworkError(e))
        } catch (e: retrofit2.HttpException) {
            Result.failure(PaperlessException.fromHttpCode(e.code(), e.message()))
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

    // Document fetching methods

    suspend fun getDocuments(
        page: Int = 1,
        pageSize: Int = 25,
        query: String? = null,
        tagIds: List<Int>? = null,
        correspondentId: Int? = null,
        documentTypeId: Int? = null,
        ordering: String = "-created"
    ): Result<DocumentsResponse> = safeApiCall {
        val tagIdsString = tagIds?.takeIf { it.isNotEmpty() }?.joinToString(",")
        api.getDocuments(
            page = page,
            pageSize = pageSize,
            query = query,
            tagIds = tagIdsString,
            correspondentId = correspondentId,
            documentTypeId = documentTypeId,
            ordering = ordering
        ).toDomain()
    }

    suspend fun getDocument(id: Int): Result<Document> = safeApiCall {
        api.getDocument(id).toDomain()
    }

    suspend fun getDocumentCount(): Result<Int> = safeApiCall {
        api.getDocuments(page = 1, pageSize = 1).count
    }

    suspend fun getRecentDocuments(limit: Int = 5): Result<List<Document>> = safeApiCall {
        api.getDocuments(
            page = 1,
            pageSize = limit,
            ordering = "-added"
        ).results.toDomain()
    }

    suspend fun getUntaggedCount(): Result<Int> = safeApiCall {
        api.getDocuments(
            page = 1,
            pageSize = 1,
            tagsIsNull = true
        ).count
    }
}
