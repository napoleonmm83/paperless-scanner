package com.paperless.scanner.data.repository

import android.net.Uri
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.domain.error.PaperlessException
import com.paperless.scanner.data.api.ProgressRequestBody
import com.paperless.scanner.data.analytics.UploadMetricsTracker
import com.paperless.scanner.data.service.DocumentSerializer
import com.paperless.scanner.data.service.ImageProcessorService
import com.paperless.scanner.data.service.PdfGeneratorService
import com.paperless.scanner.data.service.UploadResponseParseException
import com.paperless.scanner.data.service.UploadResponseParser
import com.paperless.scanner.util.LogSanitizer
import com.paperless.scanner.util.SharedFileCache
import com.paperless.scanner.util.withRetry
import com.paperless.scanner.R
import java.io.IOException
import javax.inject.Named
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import javax.inject.Inject

class DocumentRepository @Inject constructor(
    @Named("cacheDir") private val cacheDir: File,
    private val api: PaperlessApi,
    private val uploadMetricsTracker: UploadMetricsTracker,
    private val imageProcessor: ImageProcessorService,
    private val pdfGenerator: PdfGeneratorService,
    private val serializer: DocumentSerializer,
) : DocumentRepositoryContract {
    companion object {
        private const val TAG = "DocumentRepository"
    }

    override suspend fun uploadDocument(
        uri: Uri,
        title: String?,
        tagIds: List<Int>,
        documentTypeId: Int?,
        correspondentId: Int?,
        customFields: Map<Int, String>,
        onProgress: (Float) -> Unit
    ): Result<String> {
        uploadMetricsTracker.logSinglePageUploadStart()
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

            val taskId = UploadResponseParser.parseTaskId(response.string())
            uploadMetricsTracker.logSinglePageUploadSuccess(taskId)
            Result.success(taskId)
        } catch (e: IOException) {
            uploadMetricsTracker.logUploadError("NetworkError: ${e.message}")
            Result.failure(PaperlessException.NetworkError(e))
        } catch (e: retrofit2.HttpException) {
            val errorBody = try {
                e.response()?.errorBody()?.string()
            } catch (_: Exception) {
                null
            }
            uploadMetricsTracker.logUploadError("HTTP ${e.code()}")
            android.util.Log.e("DocumentRepository", "Upload failed: HTTP ${e.code()}, body: ${LogSanitizer.sanitizeErrorBody(errorBody)}")
            Result.failure(PaperlessException.fromHttpCode(e.code(), errorBody ?: e.message()))
        } catch (e: IllegalArgumentException) {
            uploadMetricsTracker.logUploadError("ContentError: ${e.message}")
            Result.failure(PaperlessException.ContentError(R.string.error_file_read_failed))
        } catch (e: CancellationException) {
            throw e
        } catch (e: UploadResponseParseException) {
            // Malformed/empty server upload response — surface as a parse error (codex review on PR-B).
            uploadMetricsTracker.logUploadError("ParseError: ${e.message}")
            Result.failure(PaperlessException.ParseError(e.message))
        } catch (e: Exception) {
            uploadMetricsTracker.logUploadError("${e.javaClass.simpleName}: ${e.message}")
            Result.failure(PaperlessException.from(e))
        }
    }

    override suspend fun uploadMultiPageDocument(
        uris: List<Uri>,
        title: String?,
        tagIds: List<Int>,
        documentTypeId: Int?,
        correspondentId: Int?,
        customFields: Map<Int, String>,
        onProgress: (Float) -> Unit
    ): Result<String> {
        uploadMetricsTracker.logMultiPageUploadStart(uris.size)
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

            val taskId = UploadResponseParser.parseTaskId(response.string())
            android.util.Log.d("DocumentRepository", "Task ID received: $taskId")
            uploadMetricsTracker.logMultiPageUploadSuccess(taskId)
            Result.success(taskId)
        } catch (e: IOException) {
            uploadMetricsTracker.logUploadError("NetworkError: ${e.message}")
            Result.failure(PaperlessException.NetworkError(e))
        } catch (e: retrofit2.HttpException) {
            val errorBody = try {
                e.response()?.errorBody()?.string()
            } catch (_: Exception) {
                null
            }
            uploadMetricsTracker.logUploadError("HTTP ${e.code()}")
            android.util.Log.e("DocumentRepository", "Multi-page upload failed: HTTP ${e.code()}, body: ${LogSanitizer.sanitizeErrorBody(errorBody)}")
            Result.failure(PaperlessException.fromHttpCode(e.code(), errorBody ?: e.message()))
        } catch (e: IllegalArgumentException) {
            // Safe error message extraction (prevent secondary exceptions)
            val safeMessage = e.message?.takeIf { it.isNotBlank() } ?: "Unknown error during PDF creation"
            uploadMetricsTracker.logUploadError("PDF creation: $safeMessage")
            android.util.Log.e("DocumentRepository", "IllegalArgumentException during PDF creation: $safeMessage", e)
            Result.failure(PaperlessException.ContentError(R.string.error_pdf_creation))
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            // Safe error message extraction (prevent secondary exceptions)
            val safeMessage = e.message?.takeIf { it.isNotBlank() } ?: "Image could not be processed"
            uploadMetricsTracker.logUploadError("Image processing: $safeMessage")
            android.util.Log.e("DocumentRepository", "IllegalStateException during PDF creation: $safeMessage", e)
            Result.failure(PaperlessException.ContentError(R.string.error_image_process_failed))
        } catch (e: UploadResponseParseException) {
            // Malformed/empty server upload response — surface as an upload error,
            // not a PDF-creation/image-processing failure (codex review on PR-B).
            uploadMetricsTracker.logUploadError("ParseError: ${e.message}")
            Result.failure(PaperlessException.ParseError(e.message))
        } catch (e: Exception) {
            // Catch-all for any unexpected exceptions (including iText7 internal errors)
            val safeMessage = e.message?.takeIf { it.isNotBlank() } ?: "Unknown error during PDF creation"
            uploadMetricsTracker.logUploadError("${e.javaClass.simpleName}: $safeMessage")
            android.util.Log.e("DocumentRepository", "Unexpected exception during multi-page upload: ${e.javaClass.simpleName} - $safeMessage", e)
            Result.failure(PaperlessException.ContentError(R.string.error_pdf_creation))
        }
    }

    suspend fun downloadDocument(
        documentId: Int,
        onProgress: (Float) -> Unit = {}
    ): Result<File> {
        // Held out here, OUTSIDE withContext, and that position is the whole point.
        //
        // An earlier version kept it inside and cleared it right before Result.success.
        // That misses the narrowest window there is: if the caller is cancelled AFTER the
        // last ensureActive() — a back-press while the final chunk is written — the block
        // finishes normally and hands back a Result, but withContext resumes a cancelled
        // caller with CancellationException instead (prompt cancellation guarantee). The
        // caller never receives the file, the inner catch never runs because nothing threw
        // inside, and a complete PDF is orphaned in the shared cache until the age sweep.
        // A cold review reproduced exactly that; the pin is the EOF-cancellation test.
        //
        // Out here the catch sees withContext's own cancellation, and the handover is
        // marked only once withContext has returned — at which point the caller really
        // does hold the file.
        var partialFile: File? = null
        try {
            return withContext(Dispatchers.IO) {
                downloadInto(documentId, onProgress) { partialFile = it }
            }.also {
                // Returned normally, so the caller has the Result and owns the file.
                partialFile = null
            }
        } catch (e: CancellationException) {
            // NonCancellable + IO, and both halves are load-bearing.
            //
            // IO: this catch resumes in the CALLER's context, and for the documented
            // caller that is viewModelScope — i.e. the main thread. A bare delete() here
            // would put filesystem I/O back on the very thread this whole change exists
            // to get off.
            //
            // NonCancellable: we are already inside a cancelled scope, so a plain
            // withContext(Dispatchers.IO) would throw at its first suspension point and
            // never reach the delete. Without it the cleanup silently stops happening —
            // a lost function, not a slow one. The EOF test below is what catches that.
            partialFile?.let { file ->
                withContext(NonCancellable + Dispatchers.IO) { file.delete() }
            }
            throw e
        }
    }

    private suspend fun downloadInto(
        documentId: Int,
        onProgress: (Float) -> Unit,
        onFileCreated: (File) -> Unit
    ): Result<File> {
        // The dispatcher switch in the caller is the whole point, not tidiness.
        //
        // `api.downloadDocument` is a suspend call and Retrofit dispatches THAT off the
        // caller's thread by itself — which is why this looked safe for a long time. But
        // the ResponseBody it hands back is UNREAD: the byteStream() loop below does the
        // actual socket reads, and those run in the CALLER's context. PdfViewerViewModel
        // calls this from viewModelScope, i.e. Dispatchers.Main (user report a9e00ce0,
        // v1.5.245).
        //
        // What that costs depends on the protocol, and saying "it always threw" would be
        // wrong — the viewer demonstrably worked for people. On HTTP/1.1 it is a real
        // socket read and BlockGuard throws NetworkOnMainThreadException, which is the
        // reported crash. On HTTP/2 the read comes out of an in-memory frame buffer that
        // a separate reader thread fills, so nothing throws and the main thread simply
        // BLOCKS for the length of the download. Same defect, two faces.
        //
        // It surfaced as "Unbekannter Fehler" because NetworkOnMainThreadException is a
        // RuntimeException and PaperlessException.from() has no case for it — nothing in
        // the message pointed at a thread. The fix belongs HERE and not in the ViewModel:
        // this is the only place every caller passes through.
        // The file exists from the moment outputStream() opens it, so an abort — cancelled
        // caller, dropped connection, full disk — would leave a zero-byte or half-written
        // PDF behind that no caller ever learns about: the Result is discarded or failed,
        // so lastDownload is never set and onCleared has nothing to delete. It would sit
        // in the shared cache until the age sweep. onFileCreated hands the path to the
        // caller of this function, which owns the cleanup.
        var partialFile: File? = null
        return try {
            val response = withRetry { api.downloadDocument(documentId) }

            val fileName = "document_${documentId}_${System.currentTimeMillis()}.pdf"
            // #241: write into the FileProvider-scoped subdir so the downloaded PDF
            // can be shared/opened without exposing the entire cache root.
            val pdfFile = File(SharedFileCache.sharedPdfsDir(cacheDir), fileName)
            partialFile = pdfFile
            onFileCreated(pdfFile)

            val contentLength = response.contentLength()

            response.byteStream().use { inputStream ->
                pdfFile.outputStream().use { outputStream ->
                    val buffer = ByteArray(8 * 1024)
                    var bytesRead: Long = 0
                    var read: Int

                    while (inputStream.read(buffer).also { read = it } != -1) {
                        // This loop has no suspension point, so withContext cannot break
                        // out of it and a blocking read on Dispatchers.IO is never
                        // interrupted. Without this check, leaving the viewer mid-download
                        // keeps the thread reading to EOF and writes a full file that
                        // nobody owns — the Result is discarded, lastDownload never set,
                        // onCleared deletes nothing, and it sits in the shared cache until
                        // the age sweep. The old code could not reach that state: the read
                        // died on the first byte.
                        currentCoroutineContext().ensureActive()
                        outputStream.write(buffer, 0, read)
                        bytesRead += read

                        if (contentLength > 0) {
                            onProgress(bytesRead.toFloat() / contentLength.toFloat())
                        }
                    }
                }
            }

            // NOT cleared here. Returning normally is not the same as the caller receiving
            // the Result — see the comment on downloadDocument. The handover is marked one
            // level up, after withContext has returned.
            Result.success(pdfFile)
        } catch (e: CancellationException) {
            partialFile?.delete()
            throw e
        } catch (e: Exception) {
            // Pinned by DocumentRepositoryTest ("a DNS failure keeps its identity
            // through the repository"). An earlier version of this comment cited that
            // test before it was written — a cold review caught it, and re-inserting the
            // deleted catch left every test green, which is what "no coverage" looks
            // like from the inside.
            //
            // This used to carry two extra catches ahead of the generic one — an
            // IOException branch and an HttpException branch — and both were lossy.
            // PaperlessException.from() already classifies every case they handled, and
            // it distinguishes six more that the IOException branch flattened, because
            // UnknownHostException, ConnectException, SocketTimeoutException,
            // CleartextNotAllowlistedException, CertificatePinMismatchException and
            // SSLException are all IOException subclasses. Catching the supertype first
            // shadowed every one of them: a wrong certificate and a blocked cleartext
            // host both arrived as "Network error: please check your internet connection".
            //
            // What this does NOT recover: a *generic* IOException — a full disk during
            // the write loop above, say — still maps to NetworkError, because that is
            // from()'s own fallback. The gain is the six typed cases, not every case.
            //
            // Deleting the two catches is the fix; from() is strictly more specific and
            // ends with the same NetworkError for a plain IOException. It also maps
            // HttpException through fromHttpCode with the response body rather than
            // e.message(), which for retrofit is the status line, not the server's text.
            //
            // What this does NOT change, contrary to an earlier version of this comment:
            // retry behaviour. withRetry (line 224) wraps the call INSIDE this try, so it
            // only ever sees the raw throwable and never the mapped type; isRetryable is
            // read by TrashDeleteWorker, not here. CertificatePinMismatchException was
            // already rethrown without backoff by NetworkRetry.kt:36 — since issue #36,
            // not since this change — and CleartextNotAllowlistedException still burns the
            // full ladder as a plain IOException. Claiming otherwise took credit for
            // someone else's fix and was false about the one case it named.
            partialFile?.delete()
            Result.failure(PaperlessException.from(e))
        }
    }
}
