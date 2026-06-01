package com.paperless.scanner.worker

import android.content.Context
import androidx.work.NetworkType
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    }
}
