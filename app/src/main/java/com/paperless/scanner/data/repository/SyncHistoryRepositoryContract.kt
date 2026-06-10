package com.paperless.scanner.data.repository

import android.content.Context
import kotlinx.coroutines.flow.Flow

/**
 * Test-double seam for [SyncHistoryRepository] (#321): the recording/observe surface
 * consumed by UploadWorker, TrashDeleteWorker and ServerHealthViewModel. Default
 * parameter values live HERE (Kotlin forbids them on overrides) — callers that rely
 * on them must hold this contract type, not the concrete class.
 */
interface SyncHistoryRepositoryContract {
    fun observeFailedCount(): Flow<Int>

    suspend fun recordSuccess(
        actionType: String,
        title: String,
        details: String? = null,
        documentId: Int? = null,
    )

    suspend fun recordFailure(
        actionType: String,
        title: String,
        userMessage: String,
        technicalError: String? = null,
        details: String? = null,
        documentId: Int? = null,
    )

    fun getUserFriendlyError(context: Context, httpCode: Int?, exception: Exception? = null): String

    fun getTechnicalError(httpCode: Int?, message: String?, exception: Exception?): String
}
