package com.paperless.scanner.data.repository

import android.net.Uri
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.api.ProgressRequestBody
import com.paperless.scanner.data.analytics.CrashlyticsHelper
import com.paperless.scanner.data.service.DocumentSerializer
import com.paperless.scanner.data.service.ImageProcessorService
import com.paperless.scanner.data.service.PdfGeneratorService
import com.paperless.scanner.R
import java.io.IOException
import javax.inject.Named
import kotlin.coroutines.cancellation.CancellationException
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import javax.inject.Inject

class DocumentRepository @Inject constructor(
    @Named("cacheDir") private val cacheDir: File,
    private val api: PaperlessApi,
    private val crashlyticsHelper: CrashlyticsHelper,
    private val imageProcessor: ImageProcessorService,
    private val pdfGenerator: PdfGeneratorService,
    private val serializer: DocumentSerializer,
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
        } catch (e: CancellationException) {
            throw e
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
            val safeMessage = e.message?.takeIf { it.isNotBlank() } ?: "Unknown error during PDF creation"
            crashlyticsHelper.logStateBreadcrumb("UPLOAD_ERROR", "PDF creation: $safeMessage")
            android.util.Log.e("DocumentRepository", "IllegalArgumentException during PDF creation: $safeMessage", e)
            Result.failure(PaperlessException.ContentError(R.string.error_pdf_creation))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            // Safe error message extraction (prevent secondary exceptions)
            val safeMessage = e.message?.takeIf { it.isNotBlank() } ?: "Image could not be processed"
            crashlyticsHelper.logStateBreadcrumb("UPLOAD_ERROR", "Image processing: $safeMessage")
            android.util.Log.e("DocumentRepository", "IllegalStateException during PDF creation: $safeMessage", e)
            Result.failure(PaperlessException.ContentError(R.string.error_image_process_failed))
        } catch (e: Exception) {
            // Catch-all for any unexpected exceptions (including iText7 internal errors)
            val safeMessage = e.message?.takeIf { it.isNotBlank() } ?: "Unknown error during PDF creation"
            crashlyticsHelper.logStateBreadcrumb("UPLOAD_ERROR", "${e.javaClass.simpleName}: $safeMessage")
            android.util.Log.e("DocumentRepository", "Unexpected exception during multi-page upload: ${e.javaClass.simpleName} - $safeMessage", e)
            Result.failure(PaperlessException.ContentError(R.string.error_pdf_creation))
        }
    }

    suspend fun downloadDocument(
        documentId: Int,
        onProgress: (Float) -> Unit = {}
    ): Result<File> {
        return try {
            val response = api.downloadDocument(documentId)

            val fileName = "document_${documentId}_${System.currentTimeMillis()}.pdf"
            val pdfFile = File(cacheDir, fileName)

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
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }
}
