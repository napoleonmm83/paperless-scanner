package com.paperless.scanner.domain.mapper

import com.paperless.scanner.data.api.models.CustomField as ApiCustomField
import com.paperless.scanner.domain.model.CustomField as DomainCustomField

// ============================================================
// DIRECT API → DOMAIN MAPPERS (Custom Field — Issue #343)
// ============================================================
// Mirrors the TagMapper seam: data.api CustomField → domain CustomField,
// so the upload UI never touches the transport DTO. CustomFieldRepository
// is the only caller.
// ============================================================

/**
 * Maps the API [ApiCustomField] DTO to the domain [DomainCustomField] model.
 */
fun ApiCustomField.toDomain(): DomainCustomField = DomainCustomField(
    id = id,
    name = name,
    dataType = dataType,
)

/**
 * Maps a list of API custom fields to domain custom fields.
 */
fun List<ApiCustomField>.toDomain(): List<DomainCustomField> = map { it.toDomain() }
