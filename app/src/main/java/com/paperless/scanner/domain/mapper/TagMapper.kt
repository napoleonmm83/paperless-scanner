package com.paperless.scanner.domain.mapper

import com.paperless.scanner.data.api.models.Tag as ApiTag
import com.paperless.scanner.domain.model.Tag as DomainTag

/**
 * Maps API Tag model to Domain Tag model
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
