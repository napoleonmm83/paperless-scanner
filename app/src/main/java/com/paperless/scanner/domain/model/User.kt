package com.paperless.scanner.domain.model

/**
 * Domain model for a Paperless-ngx user.
 * Clean model without API-specific annotations.
 */
data class User(
    val id: Int,
    val username: String,
    val email: String? = null,
    val firstName: String? = null,
    val lastName: String? = null,
    val isStaff: Boolean = false,
    val isSuperuser: Boolean = false,
    val isActive: Boolean = true
)
