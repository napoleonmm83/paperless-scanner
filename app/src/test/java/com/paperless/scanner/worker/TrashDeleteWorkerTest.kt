package com.paperless.scanner.worker

import android.content.Context
import android.util.Log
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.paperless.scanner.data.analytics.CrashlyticsHelper
import com.paperless.scanner.domain.error.PaperlessException
import com.paperless.scanner.data.database.dao.CachedDocumentDao
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.data.repository.SyncHistoryRepository
import com.paperless.scanner.data.repository.TrashRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit tests for [TrashDeleteWorker].
 *
 * Covers all Result branches in [TrashDeleteWorker.doWork]:
 * - missing input → Result.failure
 * - delete already cancelled by user → Result.success (skip)
 * - successful delete → Result.success
 * - failed delete → Result.failure
 * - history record failure does not propagate to delete result
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class TrashDeleteWorkerTest {

    private lateinit var context: Context
    private lateinit var trashRepository: TrashRepository
    private lateinit var tokenManager: TokenManager
    private lateinit var syncHistoryRepository: SyncHistoryRepository
    private lateinit var cachedDocumentDao: CachedDocumentDao
    private lateinit var crashlyticsHelper: CrashlyticsHelper

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        trashRepository = mockk(relaxed = true)
        tokenManager = mockk(relaxed = true)
        syncHistoryRepository = mockk(relaxed = true)
        cachedDocumentDao = mockk(relaxed = true)
        crashlyticsHelper = mockk(relaxed = true)

        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun createWorker(documentId: Int = 42, runAttemptCount: Int = 0): TrashDeleteWorker {
        val workerParams: WorkerParameters = mockk(relaxed = true)
        val inputData = Data.Builder()
            .putInt(TrashDeleteWorker.KEY_DOCUMENT_ID, documentId)
            .build()
        every { workerParams.inputData } returns inputData
        every { workerParams.runAttemptCount } returns runAttemptCount
        return TrashDeleteWorker(
            context, workerParams,
            trashRepository, tokenManager, syncHistoryRepository,
            cachedDocumentDao, crashlyticsHelper
        )
    }

    private fun createWorkerWithoutInput(): TrashDeleteWorker {
        val workerParams: WorkerParameters = mockk(relaxed = true)
        every { workerParams.inputData } returns Data.EMPTY
        return TrashDeleteWorker(
            context, workerParams,
            trashRepository, tokenManager, syncHistoryRepository,
            cachedDocumentDao, crashlyticsHelper
        )
    }

    @Test
    fun `doWork returns failure when documentId missing from inputData`() = runTest {
        val result = createWorkerWithoutInput().doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
        coVerify(exactly = 0) { trashRepository.permanentlyDeleteDocument(any()) }
    }

    @Test
    fun `doWork returns success and skips delete when document no longer pending`() = runTest {
        // No matching documentId in pending list → user cancelled the delete
        coEvery { tokenManager.getPendingTrashDeletesSync() } returns "99:1700000000"

        val result = createWorker(documentId = 42).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { trashRepository.permanentlyDeleteDocument(any()) }
    }

    @Test
    fun `doWork returns success and records history when delete succeeds`() = runTest {
        coEvery { tokenManager.getPendingTrashDeletesSync() } returns "42:1700000000"
        coEvery { trashRepository.permanentlyDeleteDocument(42) } returns Result.success(Unit)
        coEvery { cachedDocumentDao.getDocument(42) } returns null

        val result = createWorker(documentId = 42).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        coVerify { trashRepository.permanentlyDeleteDocument(42) }
        coVerify { syncHistoryRepository.recordSuccess(any(), any(), any(), eq(42)) }
        coVerify { tokenManager.removePendingTrashDelete(42) }
    }

    @Test
    fun `doWork returns failure and records history when delete fails`() = runTest {
        coEvery { tokenManager.getPendingTrashDeletesSync() } returns "42:1700000000"
        coEvery { trashRepository.permanentlyDeleteDocument(42) } returns Result.failure(Exception("Network down"))
        coEvery { cachedDocumentDao.getDocument(42) } returns null

        val result = createWorker(documentId = 42).doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
        coVerify { syncHistoryRepository.recordFailure(any(), any(), any(), any(), any(), eq(42)) }
        // A non-retryable failure is terminal → clear the entry (no infinite reschedule).
        coVerify { tokenManager.removePendingTrashDelete(42) }
    }

    @Test
    fun `doWork still returns success when history record throws after successful delete`() = runTest {
        // Failure to record sync history must NOT propagate to the delete result
        coEvery { tokenManager.getPendingTrashDeletesSync() } returns "42:1700000000"
        coEvery { trashRepository.permanentlyDeleteDocument(42) } returns Result.success(Unit)
        coEvery { cachedDocumentDao.getDocument(42) } returns null
        coEvery {
            syncHistoryRepository.recordSuccess(any(), any(), any(), any())
        } throws RuntimeException("History DB locked")

        val result = createWorker(documentId = 42).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun `doWork retries and keeps the pending entry on a transient server error`() = runTest {
        coEvery { tokenManager.getPendingTrashDeletesSync() } returns "42:1700000000"
        coEvery { cachedDocumentDao.getDocument(42) } returns null
        coEvery { trashRepository.permanentlyDeleteDocument(42) } returns
            Result.failure(PaperlessException.ServerError(503))

        val result = createWorker(documentId = 42, runAttemptCount = 0).doWork()

        // 5xx is transient → retry, and the pending entry must SURVIVE so the retry can
        // re-check it (and so a restore can clear it before the backoff retry fires).
        assertEquals(ListenableWorker.Result.retry(), result)
        coVerify(exactly = 0) { tokenManager.removePendingTrashDelete(42) }
    }

    @Test
    fun `doWork gives up and clears the entry once retries are exhausted`() = runTest {
        coEvery { tokenManager.getPendingTrashDeletesSync() } returns "42:1700000000"
        coEvery { cachedDocumentDao.getDocument(42) } returns null
        coEvery { trashRepository.permanentlyDeleteDocument(42) } returns
            Result.failure(PaperlessException.ServerError(503))

        val result = createWorker(
            documentId = 42,
            runAttemptCount = TrashDeleteWorker.MAX_DELETE_RETRIES
        ).doWork()

        // Bounded retry: at the cap, fail terminally and CLEAR the entry so the
        // ViewModel's restorePendingDeletes() does not reschedule it forever.
        assertEquals(ListenableWorker.Result.failure(), result)
        coVerify { tokenManager.removePendingTrashDelete(42) }
    }

    @Test
    fun `doWork fails terminally and clears the entry on a 4xx error`() = runTest {
        coEvery { tokenManager.getPendingTrashDeletesSync() } returns "42:1700000000"
        coEvery { cachedDocumentDao.getDocument(42) } returns null
        coEvery { trashRepository.permanentlyDeleteDocument(42) } returns
            Result.failure(PaperlessException.AuthError(403))

        val result = createWorker(documentId = 42, runAttemptCount = 0).doWork()

        // 4xx is permanent → no retry; clear the entry.
        assertEquals(ListenableWorker.Result.failure(), result)
        coVerify { tokenManager.removePendingTrashDelete(42) }
    }

    @Test
    fun `doWork skips the delete and clears the entry when the document was restored`() = runTest {
        coEvery { tokenManager.getPendingTrashDeletesSync() } returns "42:1700000000"
        // getDocument returns only non-deleted (isDeleted=0) rows, so a non-null result
        // means the document was restored after the delete was scheduled.
        coEvery { cachedDocumentDao.getDocument(42) } returns mockk(relaxed = true)

        val result = createWorker(documentId = 42).doWork()

        // Defense-in-depth: never permanently delete a restored document.
        assertEquals(ListenableWorker.Result.success(), result)
        coVerify(exactly = 0) { trashRepository.permanentlyDeleteDocument(any()) }
        coVerify { tokenManager.removePendingTrashDelete(42) }
    }

    @Test
    fun `doWork retries and keeps the entry when the restored-state lookup fails`() = runTest {
        coEvery { tokenManager.getPendingTrashDeletesSync() } returns "42:1700000000"
        coEvery { cachedDocumentDao.getDocument(42) } throws RuntimeException("DB locked")

        val result = createWorker(documentId = 42).doWork()

        // Fail closed: a DB read failure must NOT be treated as "not restored" and proceed
        // to delete. Retry, keep the entry, and never attempt the permanent delete. (#129)
        assertEquals(ListenableWorker.Result.retry(), result)
        coVerify(exactly = 0) { trashRepository.permanentlyDeleteDocument(any()) }
        coVerify(exactly = 0) { tokenManager.removePendingTrashDelete(42) }
    }
}
