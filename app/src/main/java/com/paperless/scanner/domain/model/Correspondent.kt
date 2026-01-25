package com.paperless.scanner.domain.model

/**
 * Domain model for Correspondent
 * Clean model without API-specific annotations
 */
data class Correspondent(
    val id: Int,
    val name: String,
    val match: String? = null,
    val matchingAlgorithm: Int? = null,
    val documentCount: Int? = null
)
