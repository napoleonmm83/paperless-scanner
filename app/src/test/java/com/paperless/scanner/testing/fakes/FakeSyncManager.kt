package com.paperless.scanner.testing.fakes

import com.paperless.scanner.data.sync.SyncManagerContract
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Typed fake for [SyncManagerContract] (#239/#321): drive [pendingChanges] directly;
 * configure [fullSyncResult] for the next [performFullSync] call.
 */
class FakeSyncManager : SyncManagerContract {
    val pendingChanges = MutableStateFlow(0)

    var fullSyncResult: Result<Unit> = Result.success(Unit)
    var performFullSyncCalls = 0
        private set

    override val pendingChangesCount: StateFlow<Int> = pendingChanges

    override suspend fun performFullSync(): Result<Unit> {
        performFullSyncCalls++
        return fullSyncResult
    }
}
