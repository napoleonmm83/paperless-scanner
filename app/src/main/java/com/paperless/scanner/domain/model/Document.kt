package com.paperless.scanner.domain.model

/**
 * Domain model for Document
 * Clean model without API-specific annotations
 */
data class Document(
    val id: Int,
    val title: String,
    val content: String? = null,
    val created: String,
    val modified: String,
    val added: String,
    val correspondentId: Int? = null,
    val documentTypeId: Int? = null,
    val tags: List<Int> = emptyList(),
    val archiveSerialNumber: Int? = null,
    val originalFileName: String? = null
)
