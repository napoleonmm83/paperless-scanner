package com.paperless.scanner.domain.model

/**
 * Domain model for a Paperless-ngx custom field definition (Issue #343).
 *
 * Mirrors the shape of `data.api.models.CustomField` but lives in the domain
 * namespace so the upload UI never imports the transport DTO. Map with
 * `CustomField.toDomain()` (see `domain/mapper/CustomFieldMapper`).
 */
data class CustomField(
    val id: Int,
    val name: String,
    val dataType: String? = null, // "string", "integer", "monetary", "date", etc.
)
