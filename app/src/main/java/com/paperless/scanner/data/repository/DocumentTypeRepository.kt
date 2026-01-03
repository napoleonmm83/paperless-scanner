package com.paperless.scanner.data.repository

import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.safeApiCall
import com.paperless.scanner.domain.mapper.toDomain
import com.paperless.scanner.domain.model.DocumentType
import javax.inject.Inject

class DocumentTypeRepository @Inject constructor(
    private val api: PaperlessApi
) {
    suspend fun getDocumentTypes(): Result<List<DocumentType>> = safeApiCall {
        api.getDocumentTypes().results.toDomain()
    }
}
