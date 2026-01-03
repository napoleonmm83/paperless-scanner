package com.paperless.scanner.data.repository

import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.safeApiCall
import com.paperless.scanner.domain.mapper.toDomain
import com.paperless.scanner.domain.model.Correspondent
import javax.inject.Inject

class CorrespondentRepository @Inject constructor(
    private val api: PaperlessApi
) {
    suspend fun getCorrespondents(): Result<List<Correspondent>> = safeApiCall {
        api.getCorrespondents().results.toDomain()
    }
}
