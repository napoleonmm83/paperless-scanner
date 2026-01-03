package com.paperless.scanner.data.repository

import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.api.models.CreateTagRequest
import com.paperless.scanner.data.api.models.UpdateTagRequest
import com.paperless.scanner.data.api.safeApiCall
import com.paperless.scanner.data.api.safeApiResponse
import com.paperless.scanner.domain.mapper.toDomain
import com.paperless.scanner.domain.model.Document
import com.paperless.scanner.domain.model.Tag
import javax.inject.Inject

class TagRepository @Inject constructor(
    private val api: PaperlessApi
) {
    suspend fun getTags(): Result<List<Tag>> = safeApiCall {
        api.getTags().results.toDomain()
    }

    suspend fun createTag(name: String, color: String? = null): Result<Tag> = safeApiCall {
        api.createTag(CreateTagRequest(name = name, color = color)).toDomain()
    }

    suspend fun updateTag(id: Int, name: String, color: String? = null): Result<Tag> = safeApiCall {
        api.updateTag(id, UpdateTagRequest(name = name, color = color)).toDomain()
    }

    suspend fun deleteTag(id: Int): Result<Unit> = safeApiResponse {
        api.deleteTag(id)
    }

    suspend fun getDocumentsForTag(tagId: Int): Result<List<Document>> = safeApiCall {
        api.getDocuments(
            tagIds = tagId.toString(),
            pageSize = 100
        ).results.toDomain()
    }
}
