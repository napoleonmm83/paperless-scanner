package com.paperless.scanner.data.database.mappers

import com.paperless.scanner.data.api.models.DocumentType as ApiDocumentType
import com.paperless.scanner.data.database.entities.CachedDocumentType
import com.paperless.scanner.domain.model.DocumentType as DomainDocumentType

fun ApiDocumentType.toCachedEntity(): CachedDocumentType {
    return CachedDocumentType(
        id = id,
        name = name,
        match = match,
        matchingAlgorithm = matchingAlgorithm,
        documentCount = documentCount,
        lastSyncedAt = System.currentTimeMillis(),
        isDeleted = false
    )
}

fun CachedDocumentType.toDomain(): DomainDocumentType {
    return DomainDocumentType(
        id = id,
        name = name,
        match = match,
        matchingAlgorithm = matchingAlgorithm,
        documentCount = documentCount
    )
}
