package com.paperless.scanner.data.repository

import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.models.Correspondent
import com.paperless.scanner.data.api.safeApiCall
import javax.inject.Inject

class CorrespondentRepository @Inject constructor(
    private val api: PaperlessApi
) {
    suspend fun getCorrespondents(): Result<List<Correspondent>> = safeApiCall {
        api.getCorrespondents().results
    }
}
