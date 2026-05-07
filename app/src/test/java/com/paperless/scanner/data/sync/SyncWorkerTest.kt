package com.paperless.scanner.data.sync

import android.content.Context
import android.util.Log
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.paperless.scanner.data.analytics.CrashlyticsHelper
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.spyk
import io.mockk.unmockkStatic
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit tests for [SyncWorker].
 *
 * Covers the four Result branches in [SyncWorker.doWork]:
 * - sync success → Result.success
 * - sync failure + retries available → Result.retry
 * - sync failure + retries exhausted → Result.failure
 * - exception thrown → Result.retry (or failure if at max attempts)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class SyncWorkerTest {

    private lateinit var context: Context
    private lateinit var syncManager: SyncManager
    private lateinit var crashlyticsHelper: CrashlyticsHelper

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        syncManager = mockk(relaxed = true)
        crashlyticsHelper = mockk(relaxed = true)

        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun createWorker(runAttemptCount: Int = 0): SyncWorker {
        val workerParams: WorkerParameters = mockk(relaxed = true)
        every { workerParams.runAttemptCount } returns runAttemptCount
        return spyk(SyncWorker(context, workerParams, syncManager, crashlyticsHelper))
    }

    @Test
    fun `doWork returns success when sync succeeds`() = runBlocking {
        coEvery { syncManager.performFullSync() } returns Result.success(Unit)

        val result = createWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun `doWork returns retry when sync fails and below max attempts`() = runBlocking {
        coEvery { syncManager.performFullSync() } returns Result.failure(Exception("API down"))

        val result = createWorker(runAttemptCount = 0).doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun `doWork returns failure when sync fails and max attempts reached`() = runBlocking {
        coEvery { syncManager.performFullSync() } returns Result.failure(Exception("API down"))

        val result = createWorker(runAttemptCount = SyncWorker.MAX_RETRIES).doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
    }

    @Test
    fun `doWork returns retry when exception thrown below max attempts`() = runBlocking {
        coEvery { syncManager.performFullSync() } throws RuntimeException("Boom")

        val result = createWorker(runAttemptCount = 1).doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun `doWork returns failure when exception thrown at max attempts`() = runBlocking {
        coEvery { syncManager.performFullSync() } throws RuntimeException("Boom")

        val result = createWorker(runAttemptCount = SyncWorker.MAX_RETRIES).doWork()

        assertEquals(ListenableWorker.Result.failure(), result)
    }
}
