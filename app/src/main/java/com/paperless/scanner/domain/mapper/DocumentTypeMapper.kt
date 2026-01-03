package com.paperless.scanner.domain.mapper

import com.paperless.scanner.data.api.models.DocumentType as ApiDocumentType
import com.paperless.scanner.domain.model.DocumentType as DomainDocumentType

/**
 * Maps API DocumentType model to Domain DocumentType model
 */
fun ApiDocumentType.toDomain(): DomainDocumentType {
    return DomainDocumentType(
        id = id,
        name = name,
        match = match,
        matchingAlgorithm = matchingAlgorithm
    )
}

/**
 * Maps list of API DocumentTypes to list of Domain DocumentTypes
 */
fun List<ApiDocumentType>.toDomain(): List<DomainDocumentType> {
    return map { it.toDomain() }
}
