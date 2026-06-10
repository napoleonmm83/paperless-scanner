package com.paperless.scanner.testing.fakes

import android.content.Context
import com.paperless.scanner.data.repository.SyncHistoryRepositoryContract
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Typed fake for [SyncHistoryRepositoryContract] (#239/#321). [failedCountFlow] is
 * replaceable so tests can inject a throwing flow; recordSuccess/recordFailure are
 * captured as data for precise assertions (no relaxed-mock false passes).
 */
class FakeSyncHistoryRepository : SyncHistoryRepositoryContract {
    /** Replace with a throwing flow to drive upstream-error paths. */
    var failedCountFlow: Flow<Int> = MutableStateFlow(0)

    data class RecordedSuccess(
        val actionType: String,
        val title: String,
        val details: String?,
        val documentId: Int?,
    )

    data class RecordedFailure(
        val actionType: String,
        val title: String,
        val userMessage: String,
        val technicalError: String?,
        val details: String?,
        val documentId: Int?,
    )

    val successes = mutableListOf<RecordedSuccess>()
    val failures = mutableListOf<RecordedFailure>()

    /** Set to make [recordSuccess] throw (history-write failures must not propagate). */
    var recordSuccessException: Throwable? = null

    override fun observeFailedCount(): Flow<Int> = failedCountFlow

    override suspend fun recordSuccess(
        actionType: String,
        title: String,
        details: String?,
        documentId: Int?,
    ) {
        recordSuccessException?.let { throw it }
        successes += RecordedSuccess(actionType, title, details, documentId)
    }

    override suspend fun recordFailure(
        actionType: String,
        title: String,
        userMessage: String,
        technicalError: String?,
        details: String?,
        documentId: Int?,
    ) {
        failures += RecordedFailure(actionType, title, userMessage, technicalError, details, documentId)
    }

    override fun getUserFriendlyError(context: Context, httpCode: Int?, exception: Exception?): String =
        "user-friendly($httpCode)"

    override fun getTechnicalError(httpCode: Int?, message: String?, exception: Exception?): String =
        "technical($httpCode, $message)"
}
