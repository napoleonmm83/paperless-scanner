package com.paperless.scanner.domain.mapper

import com.paperless.scanner.data.api.models.Correspondent as ApiCorrespondent
import com.paperless.scanner.domain.model.Correspondent as DomainCorrespondent

/**
 * Maps API Correspondent model to Domain Correspondent model
 */
fun ApiCorrespondent.toDomain(): DomainCorrespondent {
    return DomainCorrespondent(
        id = id,
        name = name,
        match = match,
        matchingAlgorithm = matchingAlgorithm
    )
}

/**
 * Maps list of API Correspondents to list of Domain Correspondents
 */
fun List<ApiCorrespondent>.toDomain(): List<DomainCorrespondent> {
    return map { it.toDomain() }
}
