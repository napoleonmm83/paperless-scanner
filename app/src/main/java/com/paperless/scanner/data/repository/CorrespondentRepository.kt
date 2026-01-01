package com.paperless.scanner.data.repository

import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.models.Correspondent
import javax.inject.Inject

class CorrespondentRepository @Inject constructor(
    private val api: PaperlessApi
) {
    suspend fun getCorrespondents(): Result<List<Correspondent>> {
        return try {
            val response = api.getCorrespondents()
            Result.success(response.results)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
