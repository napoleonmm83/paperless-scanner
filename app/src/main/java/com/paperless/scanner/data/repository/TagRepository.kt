package com.paperless.scanner.data.repository

import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.models.CreateTagRequest
import com.paperless.scanner.data.api.models.Tag
import javax.inject.Inject

class TagRepository @Inject constructor(
    private val api: PaperlessApi
) {
    suspend fun getTags(): Result<List<Tag>> {
        return try {
            val response = api.getTags()
            Result.success(response.results)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createTag(name: String, color: String? = null): Result<Tag> {
        return try {
            val tag = api.createTag(CreateTagRequest(name = name, color = color))
            Result.success(tag)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
