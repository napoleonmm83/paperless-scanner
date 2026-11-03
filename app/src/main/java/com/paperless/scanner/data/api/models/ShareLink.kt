package com.paperless.scanner.data.api.models

import com.google.gson.annotations.SerializedName

/** The document-specific list deliberately omits document and file_version. */
data class ShareLinkResponse(
    val id: Int,
    val created: String,
    val expiration: String? = null,
    val slug: String,
)

data class CreateShareLinkRequest(
    val document: Int,
    val expiration: String?,
    @SerializedName("file_version") val fileVersion: String,
)

data class ShareDocumentMetadata(
    @SerializedName("has_archive_version") val hasArchiveVersion: Boolean = false,
)
