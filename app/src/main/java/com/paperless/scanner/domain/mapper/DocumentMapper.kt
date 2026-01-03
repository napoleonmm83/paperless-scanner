package com.paperless.scanner.domain.mapper

import com.paperless.scanner.data.api.models.Document as ApiDocument
import com.paperless.scanner.data.api.models.DocumentsResponse as ApiDocumentsResponse
import com.paperless.scanner.domain.model.Document as DomainDocument
import com.paperless.scanner.domain.model.DocumentsResponse as DomainDocumentsResponse

/**
 * Maps API Document model to Domain Document model
 */
fun ApiDocument.toDomain(): DomainDocument {
    return DomainDocument(
        id = id,
        title = title,
        content = content,
        created = created,
        modified = modified,
        added = added,
        correspondentId = correspondentId,
        documentTypeId = documentTypeId,
        tags = tags,
        archiveSerialNumber = archiveSerialNumber,
        originalFileName = originalFileName
    )
}

/**
 * Maps list of API Documents to list of Domain Documents
 */
fun List<ApiDocument>.toDomain(): List<DomainDocument> {
    return map { it.toDomain() }
}

/**
 * Maps API DocumentsResponse to Domain DocumentsResponse
 */
fun ApiDocumentsResponse.toDomain(): DomainDocumentsResponse {
    return DomainDocumentsResponse(
        count = count,
        next = next,
        previous = previous,
        results = results.toDomain()
    )
}
