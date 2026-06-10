package com.paperless.scanner.testing.fakes

import com.paperless.scanner.data.health.ServerHealthMonitorContract
import com.paperless.scanner.data.health.ServerHealthResult
import com.paperless.scanner.data.health.ServerStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Typed fake for [ServerHealthMonitorContract] (#239/#321): drive [reachable]/[status]
 * directly; configure [healthResult] for the next [checkServerHealth] call.
 */
class FakeServerHealthMonitor(initiallyReachable: Boolean = true) : ServerHealthMonitorContract {
    val status = MutableStateFlow<ServerStatus>(ServerStatus.Unknown)
    val reachable = MutableStateFlow(initiallyReachable)

    var healthResult: ServerHealthResult = ServerHealthResult.Success
    var checkServerHealthCalls = 0
        private set

    override val serverStatus: StateFlow<ServerStatus> = status
    override val isServerReachable: StateFlow<Boolean> = reachable

    override suspend fun checkServerHealth(): ServerHealthResult {
        checkServerHealthCalls++
        return healthResult
    }
}
