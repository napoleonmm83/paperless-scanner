package com.paperless.scanner.worker

import android.content.Context
import android.util.Log
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.paperless.scanner.data.analytics.CrashlyticsHelper
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

    private fun createWorker(documentId: Int = 42): TrashDeleteWorker {
        val workerParams: WorkerParameters = mockk(relaxed = true)
        val inputData = Data.Builder()
            .putInt(TrashDeleteWorker.KEY_DOCUMENT_ID, documentId)
            .build()
        every { workerParams.inputData } returns inputData
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
    }

    @Test
    fun `doWork returns failure and records history when delete fails`() = runTest {
        coEvery { tokenManager.getPendingTrashDeletesSync() } returns "42:1700000000"
        coEvery { trashRepository.permanentlyDeleteDocument(42) } returns Result.failure(Exception("Network down"))
        coEvery { cachedDocumentDao.getDocument(42) } returns null

        val result = createWorker(documentId = 42).doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
        coVerify { syncHistoryRepository.recordFailure(any(), any(), any(), any(), any(), eq(42)) }
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
}
