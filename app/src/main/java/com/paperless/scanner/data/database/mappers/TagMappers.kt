package com.paperless.scanner.data.database.mappers

import com.paperless.scanner.data.api.models.Tag as ApiTag
import com.paperless.scanner.data.database.entities.CachedTag
import com.paperless.scanner.domain.model.Tag as DomainTag

// ============================================================
// CACHE-AWARE MAPPERS (API → Cache → Domain)
// ============================================================
// This package contains mappers for cache-related conversions:
// - API → CachedEntity (for persisting API data with sync metadata)
// - CachedEntity → Domain (for reading cached data)
//
// CachedEntity includes additional fields not in API/Domain:
// - lastSyncedAt: When this entity was last synced
// - isDeleted: Soft-delete flag for optimistic updates
//
// For direct API → Domain conversion (without cache), use:
// com.paperless.scanner.domain.mapper
//
// Usage in Repositories:
// - import toCachedEntity for API → Cache
// - import toDomain as toCachedDomain for Cache → Domain
// ============================================================

/**
 * Maps API Tag to CachedTag for database persistence.
 * Adds sync metadata (lastSyncedAt, isDeleted).
 */
fun ApiTag.toCachedEntity(): CachedTag {
    return CachedTag(
        id = id,
        name = name,
        color = color,
        match = match,
        matchingAlgorithm = matchingAlgorithm,
        isInboxTag = isInboxTag,
        documentCount = documentCount ?: 0,
        lastSyncedAt = System.currentTimeMillis(),
        isDeleted = false
    )
}

fun CachedTag.toDomain(): DomainTag {
    return DomainTag(
        id = id,
        name = name,
        color = color,
        match = match,
        matchingAlgorithm = matchingAlgorithm,
        isInboxTag = isInboxTag,
        documentCount = documentCount
    )
}
