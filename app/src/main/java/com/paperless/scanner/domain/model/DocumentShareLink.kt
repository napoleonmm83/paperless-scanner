package com.paperless.scanner.domain.model

data class DocumentShareLink(
    val id: Int,
    val created: String,
    val expiration: String?,
    val url: String,
)

enum class ShareFileVersion(val apiValue: String) { ARCHIVE("archive"), ORIGINAL("original") }

data class DocumentShareLinks(val links: List<DocumentShareLink>, val hasArchiveVersion: Boolean)
