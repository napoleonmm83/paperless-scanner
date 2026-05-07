package com.paperless.scanner.data.repository

import android.content.Context
import com.paperless.scanner.R
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.api.models.CreateNoteRequest
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.domain.mapper.toAuditLogDomain
import com.paperless.scanner.domain.mapper.toDomain
import com.paperless.scanner.domain.model.AuditLogEntry
import com.paperless.scanner.domain.model.Note
import com.paperless.scanner.util.withRetry
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

/**
 * Phase 3.2 of #51 — extracted from DocumentRepository.
 *
 * Owns audit-history operations: getDocumentHistory, addNote, deleteNote.
 * All methods are online-only thin wrappers around PaperlessApi; offline
 * branches return PaperlessException.NetworkError. No cache, no offline-queue.
 */
@Singleton
class AuditRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: PaperlessApi,
    private val networkMonitor: NetworkMonitor,
) {

    suspend fun getDocumentHistory(documentId: Int): Result<List<AuditLogEntry>> {
        return try {
            if (networkMonitor.checkOnlineStatus()) {
                val history = withRetry { api.getDocumentHistory(documentId) }
                Result.success(history.toAuditLogDomain())
            } else {
                Result.failure(
                    PaperlessException.NetworkError(IOException(context.getString(R.string.error_offline)))
                )
            }
        } catch (e: retrofit2.HttpException) {
            Result.failure(PaperlessException.fromHttpCode(e.code(), e.message()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    suspend fun addNote(documentId: Int, noteText: String): Result<List<Note>> {
        return try {
            if (networkMonitor.checkOnlineStatus()) {
                // POST: addNote is non-idempotent — no withRetry, otherwise a
                // 5xx after a server-side commit would create a duplicate note.
                val request = CreateNoteRequest(note = noteText)
                val notes = api.addNote(documentId, request)
                Result.success(notes.map { it.toDomain() })
            } else {
                Result.failure(
                    PaperlessException.NetworkError(IOException(context.getString(R.string.error_offline)))
                )
            }
        } catch (e: retrofit2.HttpException) {
            Result.failure(PaperlessException.fromHttpCode(e.code(), e.message()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    suspend fun deleteNote(documentId: Int, noteId: Int): Result<List<Note>> {
        return try {
            if (networkMonitor.checkOnlineStatus()) {
                val notes = withRetry { api.deleteNote(documentId, noteId) }
                Result.success(notes.map { it.toDomain() })
            } else {
                Result.failure(
                    PaperlessException.NetworkError(IOException(context.getString(R.string.error_offline)))
                )
            }
        } catch (e: retrofit2.HttpException) {
            Result.failure(PaperlessException.fromHttpCode(e.code(), e.message()))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }
}
