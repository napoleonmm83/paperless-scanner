package com.paperless.scanner.widget

import android.content.Context
import android.util.Log
import androidx.work.ListenableWorker
import androidx.work.WorkerParameters
import com.paperless.scanner.testing.fakes.FakeUploadQueueRepository
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
 * Unit tests for [WidgetUpdateWorker].
 *
 * Covers:
 * - Happy path: queue count + connectivity collected → Result.success
 * - Repository throws → Result.retry
 * - Verifies the worker queries the pending upload count
 *
 * Glance widget state updates and AppWidgetManager broadcasts are exercised
 * indirectly through Robolectric's stubs; they don't need to succeed for the
 * worker to return success.
 *
 * #202 (plan-03): uploadQueueRepository is the typed fake from testing/fakes/,
 * compile-time-checked against the #321 contract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class WidgetUpdateWorkerTest {

    private lateinit var context: Context
    private lateinit var uploadQueueRepository: FakeUploadQueueRepository

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        uploadQueueRepository = FakeUploadQueueRepository()

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

    private fun createWorker(): WidgetUpdateWorker {
        val workerParams: WorkerParameters = mockk(relaxed = true)
        return WidgetUpdateWorker(context, workerParams, uploadQueueRepository)
    }

    @Test
    fun `doWork returns success on happy path`() = runTest {
        val result = createWorker().doWork()

        assertEquals(ListenableWorker.Result.success(), result)
    }

    @Test
    fun `doWork returns retry when repository throws`() = runTest {
        uploadQueueRepository.getPendingUploadCountException = RuntimeException("DB closed")

        val result = createWorker().doWork()

        assertEquals(ListenableWorker.Result.retry(), result)
    }

    @Test
    fun `doWork queries pending upload count`() = runTest {
        createWorker().doWork()

        assertEquals(
            1,
            uploadQueueRepository.recordedCalls.count { it == "getPendingUploadCount" },
        )
    }
}
