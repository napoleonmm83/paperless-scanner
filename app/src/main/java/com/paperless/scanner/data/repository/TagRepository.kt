package com.paperless.scanner.data.repository

import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.models.CreateTagRequest
import com.paperless.scanner.data.api.models.Document
import com.paperless.scanner.data.api.models.Tag
import com.paperless.scanner.data.api.models.UpdateTagRequest
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

    suspend fun updateTag(id: Int, name: String, color: String? = null): Result<Tag> {
        return try {
            val tag = api.updateTag(id, UpdateTagRequest(name = name, color = color))
            Result.success(tag)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteTag(id: Int): Result<Unit> {
        return try {
            val response = api.deleteTag(id)
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to delete tag: ${response.code()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getDocumentsForTag(tagId: Int): Result<List<Document>> {
        return try {
            val response = api.getDocuments(
                tagIds = tagId.toString(),
                pageSize = 100
            )
            Result.success(response.results)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
