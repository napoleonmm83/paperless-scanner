package com.paperless.scanner.data.repository

import android.content.Context
import android.net.Uri
import com.paperless.scanner.data.api.PaperlessApi
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
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
        documentTypeId: Int? = null
    ): Result<String> {
        return try {
            val file = getFileFromUri(uri)
            val requestFile = file.asRequestBody("image/jpeg".toMediaTypeOrNull())
            val documentPart = MultipartBody.Part.createFormData(
                "document",
                file.name,
                requestFile
            )

            val titleBody = title?.toRequestBody("text/plain".toMediaTypeOrNull())

            // For multiple tags, we need to send them as separate parts
            // This is handled by making multiple calls or using a specific format
            val tagsBody = if (tagIds.isNotEmpty()) {
                tagIds.joinToString(",").toRequestBody("text/plain".toMediaTypeOrNull())
            } else null

            val documentTypeBody = documentTypeId?.toString()
                ?.toRequestBody("text/plain".toMediaTypeOrNull())

            val response = api.uploadDocument(
                document = documentPart,
                title = titleBody,
                tags = tagsBody,
                documentType = documentTypeBody
            )

            // Clean up temp file
            file.delete()

            // API returns task ID as plain string (possibly quoted)
            val taskId = response.string().trim().removeSurrounding("\"")
            Result.success(taskId)
        } catch (e: Exception) {
            Result.failure(e)
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
