package com.paperless.scanner.testing.fakes

import android.net.Uri
import com.paperless.scanner.data.repository.DocumentRepositoryContract

/**
 * Typed fake for [DocumentRepositoryContract] (#202/#321): records every upload with
 * its full argument set so tests assert on data instead of relaxed verify calls.
 * Configure per-URI results via [uploadResults] / throwables via [uploadExceptions]
 * (keyed by the FIRST uri's string form; fallback [defaultUploadResult]); both are
 * STICKY until removed. [progressSteps] are replayed through onProgress before the
 * result is returned.
 */
class FakeDocumentRepository : DocumentRepositoryContract {
    data class RecordedUpload(
        val uris: List<Uri>,
        val title: String?,
        val tagIds: List<Int>,
        val documentTypeId: Int?,
        val correspondentId: Int?,
        val customFields: Map<Int, String>,
        val isMultiPage: Boolean,
    )

    val uploads = mutableListOf<RecordedUpload>()

    val uploadResults = mutableMapOf<String, Result<String>>()
    val uploadExceptions = mutableMapOf<String, Throwable>()
    var defaultUploadResult: Result<String> = Result.success("task-1")

    var progressSteps: List<Float> = emptyList()
    val reportedProgress = mutableListOf<Float>()

    override suspend fun uploadDocument(
        uri: Uri,
        title: String?,
        tagIds: List<Int>,
        documentTypeId: Int?,
        correspondentId: Int?,
        customFields: Map<Int, String>,
        onProgress: (Float) -> Unit,
    ): Result<String> =
        record(listOf(uri), title, tagIds, documentTypeId, correspondentId, customFields, false, onProgress)

    override suspend fun uploadMultiPageDocument(
        uris: List<Uri>,
        title: String?,
        tagIds: List<Int>,
        documentTypeId: Int?,
        correspondentId: Int?,
        customFields: Map<Int, String>,
        onProgress: (Float) -> Unit,
    ): Result<String> =
        record(uris, title, tagIds, documentTypeId, correspondentId, customFields, true, onProgress)

    private fun record(
        uris: List<Uri>,
        title: String?,
        tagIds: List<Int>,
        documentTypeId: Int?,
        correspondentId: Int?,
        customFields: Map<Int, String>,
        multiPage: Boolean,
        onProgress: (Float) -> Unit,
    ): Result<String> {
        uploads += RecordedUpload(uris, title, tagIds, documentTypeId, correspondentId, customFields, multiPage)
        val key = uris.first().toString()
        uploadExceptions[key]?.let { throw it }
        progressSteps.forEach { step ->
            reportedProgress += step
            onProgress(step)
        }
        return uploadResults[key] ?: defaultUploadResult
    }
}
