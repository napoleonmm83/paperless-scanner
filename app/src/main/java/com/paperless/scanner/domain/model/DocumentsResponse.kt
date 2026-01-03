package com.paperless.scanner.domain.model

/**
 * Domain model for paginated documents response
 * Clean model without API-specific annotations
 */
data class DocumentsResponse(
    val count: Int,
    val next: String? = null,
    val previous: String? = null,
    val results: List<Document>
)
