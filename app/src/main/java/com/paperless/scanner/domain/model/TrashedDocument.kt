package com.paperless.scanner.domain.model

/**
 * Domain model for a document in the trash.
 *
 * Extends the core document data with trash-specific metadata
 * (deletedAt timestamp) that the trash UI requires for countdown
 * and auto-delete calculations.
 */
data class TrashedDocument(
    val id: Int,
    val title: String,
    val created: String,
    val deletedAt: Long?
)
