package com.paperless.scanner.domain.mapper

import com.paperless.scanner.data.api.models.Tag as ApiTag
import com.paperless.scanner.domain.model.Tag as DomainTag

// ============================================================
// DIRECT API → DOMAIN MAPPERS
// ============================================================
// This package contains mappers for DIRECT API to Domain conversion.
// Use these when you need fresh API data without cache involvement.
//
// For cache-related mapping (API → Cache → Domain), use:
// com.paperless.scanner.data.database.mappers
//
// Usage in Repositories:
// - import toDomain for direct API → Domain
// - import toDomain as toCachedDomain for Cache → Domain
// ============================================================

/**
 * Maps API Tag model to Domain Tag model.
 *
 * Use this for direct API responses when cache is not involved.
 * For cached data, use CachedTag.toDomain() from database.mappers.
 */
fun ApiTag.toDomain(): DomainTag {
    return DomainTag(
        id = id,
        name = name,
        color = color,
        match = match,
        matchingAlgorithm = matchingAlgorithm,
        isInboxTag = isInboxTag,
        documentCount = documentCount ?: 0
    )
}

/**
 * Maps list of API Tags to list of Domain Tags
 */
fun List<ApiTag>.toDomain(): List<DomainTag> {
    return map { it.toDomain() }
}
