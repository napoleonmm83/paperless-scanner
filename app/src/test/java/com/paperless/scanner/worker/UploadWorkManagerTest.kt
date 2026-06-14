package com.paperless.scanner.worker

import android.content.Context
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.paperless.scanner.data.datastore.TokenManager
import io.mockk.every
import io.mockk.mockk
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
    private lateinit var tokenManager: TokenManager
    private lateinit var uploadWorkManager: UploadWorkManager

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
        tokenManager = mockk()
        // Default: unmetered-only OFF → CONNECTED (preserves pre-feature behavior).
        every { tokenManager.getUploadUnmeteredOnlySync() } returns false
        uploadWorkManager = UploadWorkManager(context, UploadConstraintsProvider(tokenManager))
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
    fun `scheduleImmediateUpload requires UNMETERED when the unmetered-only preference is set`() {
        every { tokenManager.getUploadUnmeteredOnlySync() } returns true
        val manager = UploadWorkManager(context, UploadConstraintsProvider(tokenManager))

        manager.scheduleImmediateUpload()

        val workInfos = workManager.getWorkInfosForUniqueWork(UploadWorker.WORK_NAME).get()
        assertEquals(1, workInfos.size)
        val constraints = workInfos.first().constraints
        assertEquals(NetworkType.UNMETERED, constraints.requiredNetworkType)
        // Battery/storage deferral (#134) is independent of the network preference.
        assertTrue(constraints.requiresBatteryNotLow())
        assertTrue(constraints.requiresStorageNotLow())
    }

    @Test
    fun `rescheduleForConstraintChange re-enqueues with the current constraint`() {
        every { tokenManager.getUploadUnmeteredOnlySync() } returns true
        val manager = UploadWorkManager(context, UploadConstraintsProvider(tokenManager))

        manager.rescheduleForConstraintChange()

        val active = workManager.getWorkInfosForUniqueWork(UploadWorker.WORK_NAME).get()
            .filter { it.state != WorkInfo.State.CANCELLED }
        assertEquals(1, active.size)
        assertEquals(NetworkType.UNMETERED, active.first().constraints.requiredNetworkType)
    }

    @Test
    fun `rescheduleForConstraintChange REPLACEs stale UNMETERED work after the preference is disabled`() {
        // Given: work was enqueued while unmetered-only was ON (held under UNMETERED).
        every { tokenManager.getUploadUnmeteredOnlySync() } returns true
        UploadWorkManager(context, UploadConstraintsProvider(tokenManager)).scheduleImmediateUpload()

        // When: the user disables the preference and the queue is rescheduled.
        every { tokenManager.getUploadUnmeteredOnlySync() } returns false
        UploadWorkManager(context, UploadConstraintsProvider(tokenManager)).rescheduleForConstraintChange()

        // Then: exactly one live work remains, now requiring only CONNECTED — the previously
        // UNMETERED-blocked upload is no longer stuck on mobile data.
        val active = workManager.getWorkInfosForUniqueWork(UploadWorker.WORK_NAME).get()
            .filter { it.state != WorkInfo.State.CANCELLED }
        assertEquals(1, active.size)
        assertEquals(NetworkType.CONNECTED, active.first().constraints.requiredNetworkType)
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
        // Both must survive: REPLACE would CANCEL the first, and neither should end up
        // FAILED. APPEND_OR_REPLACE chains the second request behind the first, so it is
        // BLOCKED (dependent on the constraint-held predecessor) rather than ENQUEUED —
        // hence we count "not dropped / not errored" rather than asserting a specific
        // live state (CodeRabbit).
        val survived = workInfos.filter {
            it.state != WorkInfo.State.CANCELLED && it.state != WorkInfo.State.FAILED
        }
        assertEquals(
            "Both rapidly-scheduled uploads must survive; states=${workInfos.map { it.state }}",
            2,
            survived.size
        )
    }
}
