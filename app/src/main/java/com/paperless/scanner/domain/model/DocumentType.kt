package com.paperless.scanner.domain.model

/**
 * Domain model for DocumentType
 * Clean model without API-specific annotations
 */
data class DocumentType(
    val id: Int,
    val name: String,
    val match: String? = null,
    val matchingAlgorithm: Int? = null
)
