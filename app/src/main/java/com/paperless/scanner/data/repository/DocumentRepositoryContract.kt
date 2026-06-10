package com.paperless.scanner.data.repository

import android.net.Uri

/**
 * Test-double seam for [DocumentRepository] (#321): the upload surface consumed by
 * UploadWorker. Default parameter values live HERE (Kotlin forbids them on overrides).
 */
interface DocumentRepositoryContract {
    suspend fun uploadDocument(
        uri: Uri,
        title: String? = null,
        tagIds: List<Int> = emptyList(),
        documentTypeId: Int? = null,
        correspondentId: Int? = null,
        customFields: Map<Int, String> = emptyMap(),
        onProgress: (Float) -> Unit = {},
    ): Result<String>

    suspend fun uploadMultiPageDocument(
        uris: List<Uri>,
        title: String? = null,
        tagIds: List<Int> = emptyList(),
        documentTypeId: Int? = null,
        correspondentId: Int? = null,
        customFields: Map<Int, String> = emptyMap(),
        onProgress: (Float) -> Unit = {},
    ): Result<String>
}
