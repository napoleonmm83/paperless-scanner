package com.paperless.scanner.data.repository

import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.models.DocumentType
import javax.inject.Inject

class DocumentTypeRepository @Inject constructor(
    private val api: PaperlessApi
) {
    suspend fun getDocumentTypes(): Result<List<DocumentType>> {
        return try {
            val response = api.getDocumentTypes()
            Result.success(response.results)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
