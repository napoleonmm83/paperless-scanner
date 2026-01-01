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
            ByteArrayOutputStream().use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
                outputStream.toByteArray()
            }
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
}
