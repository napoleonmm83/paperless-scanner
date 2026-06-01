package com.paperless.scanner.worker

import android.content.Context
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit tests for [UploadWorkManager] scheduling constraints (#134).
 *
 * Uses the AndroidX WorkManager test harness. Enqueued work is deliberately never
 * driven to run (no [WorkManagerTestInitHelper] TestDriver calls), so the
 * @HiltWorker [UploadWorker] is never instantiated — the test only inspects the
 * enqueued WorkInfo's constraints, which are part of the WorkSpec regardless of
 * run state.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class UploadWorkManagerTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var uploadWorkManager: UploadWorkManager

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
        uploadWorkManager = UploadWorkManager(context)
    }

    @Test
    fun `scheduleImmediateUpload enqueues work requiring battery-not-low, storage-not-low and CONNECTED`() {
        uploadWorkManager.scheduleImmediateUpload()

        val workInfos = workManager.getWorkInfosForUniqueWork(UploadWorker.WORK_NAME).get()
        assertEquals(1, workInfos.size)
        val constraints = workInfos.first().constraints
        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        assertTrue(constraints.requiresBatteryNotLow())
        assertTrue(constraints.requiresStorageNotLow())
    }

    @Test
    fun `two rapid scheduleImmediateUpload calls keep both enqueued (no lost queue items, #130)`() {
        // REPLACE would cancel the first request when the second arrives, dropping
        // any pending-queue rows the first worker had already begun draining.
        // APPEND_OR_REPLACE appends the second behind the first instead, so neither
        // is lost (#130). Work never runs here (CONNECTED constraint unmet in the
        // test harness), so both stay live unless one is cancelled.
        uploadWorkManager.scheduleImmediateUpload()
        uploadWorkManager.scheduleImmediateUpload()

        val workInfos = workManager.getWorkInfosForUniqueWork(UploadWorker.WORK_NAME).get()
        val live = workInfos.filter { it.state != WorkInfo.State.CANCELLED }
        assertEquals(
            "Both rapidly-scheduled uploads must survive; states=${workInfos.map { it.state }}",
            2,
            live.size
        )
    }
}
