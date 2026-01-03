package com.paperless.scanner.data.repository

import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.models.DocumentType
import com.paperless.scanner.data.api.safeApiCall
import javax.inject.Inject

class DocumentTypeRepository @Inject constructor(
    private val api: PaperlessApi
) {
    suspend fun getDocumentTypes(): Result<List<DocumentType>> = safeApiCall {
        api.getDocumentTypes().results
    }
}
