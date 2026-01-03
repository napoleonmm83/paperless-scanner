package com.paperless.scanner.domain.model

/**
 * Domain model for Tag (Label)
 * Clean model without API-specific annotations
 */
data class Tag(
    val id: Int,
    val name: String,
    val color: String? = null,
    val match: String? = null,
    val matchingAlgorithm: Int? = null,
    val isInboxTag: Boolean = false,
    val documentCount: Int = 0
)
