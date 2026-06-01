package com.paperless.scanner.worker

import android.content.Context
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit tests for [TrashDeleteWorkManager] scheduling constraints (#134).
 *
 * Kept separate from [UploadWorkManagerTest] to avoid WorkManager test-singleton
 * cross-contamination. Enqueued work is never driven to run, so the @HiltWorker
 * [TrashDeleteWorker] is never instantiated — the tests only inspect the enqueued
 * WorkInfo's constraints.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class TrashDeleteWorkManagerTest {

    private lateinit var context: Context
    private lateinit var workManager: WorkManager
    private lateinit var trashDeleteWorkManager: TrashDeleteWorkManager

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        workManager = WorkManager.getInstance(context)
        trashDeleteWorkManager = TrashDeleteWorkManager(context)
    }

    @Test
    fun `schedulePendingDelete enqueues work requiring CONNECTED network`() {
        trashDeleteWorkManager.schedulePendingDelete(documentId = 42, delaySeconds = 5)

        val constraints = workManager
            .getWorkInfosForUniqueWork(TrashDeleteWorker.workName(42)).get()
            .first().constraints
        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        // A user-initiated delete must not be blocked on battery (only network).
        assertFalse(constraints.requiresBatteryNotLow())
    }

    @Test
    fun `scheduleImmediateDelete enqueues work requiring CONNECTED network`() {
        trashDeleteWorkManager.scheduleImmediateDelete(documentId = 42)

        val constraints = workManager
            .getWorkInfosForUniqueWork(TrashDeleteWorker.workName(42)).get()
            .first().constraints
        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        // Shares deleteConstraints() with schedulePendingDelete → battery not required.
        assertFalse(constraints.requiresBatteryNotLow())
    }

    @Test
    fun `scheduleImmediateDelete keeps an existing pending delete instead of replacing it`() {
        // An already-scheduled delete (here a long-delayed one stands in for an in-flight
        // or WorkManager backoff-retry delete that carries its runAttemptCount).
        trashDeleteWorkManager.schedulePendingDelete(documentId = 42, delaySeconds = 3600)
        val firstId = workManager
            .getWorkInfosForUniqueWork(TrashDeleteWorker.workName(42)).get().single().id

        // restorePendingDeletes() re-triggers this on VM recreation. KEEP must preserve the
        // existing work — REPLACE would cancel it and reset runAttemptCount, deleting
        // immediately (before a restore) and bypassing MAX_DELETE_RETRIES. (#129)
        trashDeleteWorkManager.scheduleImmediateDelete(documentId = 42)

        val workInfos = workManager.getWorkInfosForUniqueWork(TrashDeleteWorker.workName(42)).get()
        assertTrue(
            "existing delete must be preserved (KEEP), not cancelled; states=${workInfos.map { it.state }}",
            workInfos.none { it.state == WorkInfo.State.CANCELLED }
        )
        assertEquals("same work retained (no replacement)", firstId, workInfos.single().id)
    }
}
