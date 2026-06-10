package com.paperless.scanner.worker

import android.content.Context
import android.util.Log
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.paperless.scanner.domain.error.PaperlessException
import com.paperless.scanner.data.database.dao.CachedDocumentDao
import com.paperless.scanner.testing.fakes.FakeCrashlyticsHelper
import com.paperless.scanner.testing.fakes.FakeSyncHistoryRepository
import com.paperless.scanner.testing.fakes.FakeTokenManager
import com.paperless.scanner.testing.fakes.FakeTrashRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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
 *
 * #202 (plan-03): trashRepository/tokenManager/syncHistoryRepository/crashlyticsHelper
 * are the typed fakes from testing/fakes/ (compile-time-checked against the #321
 * contracts); assertions inspect recorded data instead of relaxed verify calls.
 * cachedDocumentDao stays a MockK mock — DAOs have no contracts (Room interfaces).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class TrashDeleteWorkerTest {

    private lateinit var context: Context
    private lateinit var trashRepository: FakeTrashRepository
    private lateinit var tokenManager: FakeTokenManager
    private lateinit var syncHistoryRepository: FakeSyncHistoryRepository
    private lateinit var cachedDocumentDao: CachedDocumentDao
    private lateinit var crashlyticsHelper: FakeCrashlyticsHelper

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        trashRepository = FakeTrashRepository()
        tokenManager = FakeTokenManager()
        syncHistoryRepository = FakeSyncHistoryRepository()
        cachedDocumentDao = mockk(relaxed = true)
        crashlyticsHelper = FakeCrashlyticsHelper()

        // Default: document is not in the cache (still deleted / not restored).
        coEvery { cachedDocumentDao.getDocument(any()) } returns null

        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any()) } returns 0
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
        assertTrue("no delete must be attempted", trashRepository.deletedDocumentIds.isEmpty())
    }

    @Test
    fun `doWork returns success and skips delete when document no longer pending`() = runTest {
        // No matching documentId in pending list → user cancelled the delete
        tokenManager.pendingTrashDeletes = "99:1700000000"

        val result = createWorker(documentId = 42).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue("no delete must be attempted", trashRepository.deletedDocumentIds.isEmpty())
    }

    @Test
    fun `doWork returns success and records history when delete succeeds`() = runTest {
        tokenManager.pendingTrashDeletes = "42:1700000000"
        trashRepository.deleteResults[42] = Result.success(Unit)

        val result = createWorker(documentId = 42).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
        assertEquals(listOf(42), trashRepository.deletedDocumentIds)
        assertEquals(42, syncHistoryRepository.successes.single().documentId)
        assertEquals(listOf(42), tokenManager.removedPendingTrashDeletes)
    }

    @Test
    fun `doWork returns failure and records history when delete fails`() = runTest {
        tokenManager.pendingTrashDeletes = "42:1700000000"
        trashRepository.deleteResults[42] = Result.failure(Exception("Network down"))

        val result = createWorker(documentId = 42).doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
        assertEquals(42, syncHistoryRepository.failures.single().documentId)
        // A non-retryable failure is terminal → clear the entry (no infinite reschedule).
        assertEquals(listOf(42), tokenManager.removedPendingTrashDeletes)
    }

    @Test
    fun `doWork still returns success when history record throws after successful delete`() = runTest {
        // Failure to record sync history must NOT propagate to the delete result
        tokenManager.pendingTrashDeletes = "42:1700000000"
        trashRepository.deleteResults[42] = Result.success(Unit)
        syncHistoryRepository.recordSuccessException = RuntimeException("History DB locked")

        val result = createWorker(documentId = 42).doWork()

        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun `doWork retries and keeps the pending entry on a transient server error`() = runTest {
        tokenManager.pendingTrashDeletes = "42:1700000000"
        trashRepository.deleteResults[42] = Result.failure(PaperlessException.ServerError(503))

        val result = createWorker(documentId = 42, runAttemptCount = 0).doWork()

        // 5xx is transient → retry, and the pending entry must SURVIVE so the retry can
        // re-check it (and so a restore can clear it before the backoff retry fires).
        assertEquals(ListenableWorker.Result.retry(), result)
        assertTrue("entry must survive", tokenManager.removedPendingTrashDeletes.isEmpty())
    }

    @Test
    fun `doWork gives up and clears the entry once retries are exhausted`() = runTest {
        tokenManager.pendingTrashDeletes = "42:1700000000"
        trashRepository.deleteResults[42] = Result.failure(PaperlessException.ServerError(503))

        val result = createWorker(
            documentId = 42,
            runAttemptCount = TrashDeleteWorker.MAX_DELETE_RETRIES
        ).doWork()

        // Bounded retry: at the cap, fail terminally and CLEAR the entry so the
        // ViewModel's restorePendingDeletes() does not reschedule it forever.
        assertEquals(ListenableWorker.Result.failure(), result)
        assertEquals(listOf(42), tokenManager.removedPendingTrashDeletes)
    }

    @Test
    fun `doWork fails terminally and clears the entry on a 4xx error`() = runTest {
        tokenManager.pendingTrashDeletes = "42:1700000000"
        trashRepository.deleteResults[42] = Result.failure(PaperlessException.AuthError(403))

        val result = createWorker(documentId = 42, runAttemptCount = 0).doWork()

        // 4xx is permanent → no retry; clear the entry.
        assertEquals(ListenableWorker.Result.failure(), result)
        assertEquals(listOf(42), tokenManager.removedPendingTrashDeletes)
    }

    @Test
    fun `doWork skips the delete and clears the entry when the document was restored`() = runTest {
        tokenManager.pendingTrashDeletes = "42:1700000000"
        // getDocument returns only non-deleted (isDeleted=0) rows, so a non-null result
        // means the document was restored after the delete was scheduled.
        coEvery { cachedDocumentDao.getDocument(42) } returns mockk(relaxed = true)

        val result = createWorker(documentId = 42).doWork()

        // Defense-in-depth: never permanently delete a restored document.
        assertEquals(ListenableWorker.Result.success(), result)
        assertTrue("no delete must be attempted", trashRepository.deletedDocumentIds.isEmpty())
        assertEquals(listOf(42), tokenManager.removedPendingTrashDeletes)
    }

    @Test
    fun `doWork retries and keeps the entry when the restored-state lookup fails`() = runTest {
        tokenManager.pendingTrashDeletes = "42:1700000000"
        coEvery { cachedDocumentDao.getDocument(42) } throws RuntimeException("DB locked")

        val result = createWorker(documentId = 42).doWork()

        // Fail closed: a DB read failure must NOT be treated as "not restored" and proceed
        // to delete. Retry, keep the entry, and never attempt the permanent delete. (#129)
        assertEquals(ListenableWorker.Result.retry(), result)
        assertTrue("no delete must be attempted", trashRepository.deletedDocumentIds.isEmpty())
        assertTrue("entry must survive", tokenManager.removedPendingTrashDeletes.isEmpty())
    }
}
