package com.paperless.scanner.data.database.mappers

import com.paperless.scanner.data.api.models.Correspondent as ApiCorrespondent
import com.paperless.scanner.data.database.entities.CachedCorrespondent
import com.paperless.scanner.domain.model.Correspondent as DomainCorrespondent

fun ApiCorrespondent.toCachedEntity(): CachedCorrespondent {
    return CachedCorrespondent(
        id = id,
        name = name,
        match = match,
        matchingAlgorithm = matchingAlgorithm,
        documentCount = documentCount,
        lastSyncedAt = System.currentTimeMillis(),
        isDeleted = false
    )
}

fun CachedCorrespondent.toDomain(): DomainCorrespondent {
    return DomainCorrespondent(
        id = id,
        name = name,
        match = match,
        matchingAlgorithm = matchingAlgorithm,
        documentCount = documentCount
    )
}
