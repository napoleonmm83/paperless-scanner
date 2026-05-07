package com.paperless.scanner.domain.model

/**
 * Domain model for a Paperless-ngx permission group.
 * Clean model without API-specific annotations.
 */
data class Group(
    val id: Int,
    val name: String
)
