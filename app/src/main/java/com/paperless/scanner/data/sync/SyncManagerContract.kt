package com.paperless.scanner.data.sync

import kotlinx.coroutines.flow.StateFlow

/**
 * Test-double seam for [SyncManager] (#321): the sync surface consumed by SyncWorker
 * and ServerHealthViewModel. NOTE: [pendingChangesCount] is a StateFlow (the design
 * doc wrongly said Flow) and SyncManager has no stop() method.
 */
interface SyncManagerContract {
    val pendingChangesCount: StateFlow<Int>
    suspend fun performFullSync(): Result<Unit>
}
