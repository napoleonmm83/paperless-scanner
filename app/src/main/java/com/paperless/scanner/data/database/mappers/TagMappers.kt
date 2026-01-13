package com.paperless.scanner.data.database.mappers

import com.paperless.scanner.data.api.models.Tag as ApiTag
import com.paperless.scanner.data.database.entities.CachedTag
import com.paperless.scanner.domain.model.Tag as DomainTag

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
