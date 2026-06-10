package com.paperless.scanner.data.health

import kotlinx.coroutines.flow.StateFlow

/**
 * Test-double seam for [ServerHealthMonitor] (#321): the health surface consumed by
 * UploadWorker and ServerHealthViewModel. Lifecycle methods (startPolling/destroy)
 * stay on the concrete class — PaperlessApp keeps injecting that directly.
 */
interface ServerHealthMonitorContract {
    val serverStatus: StateFlow<ServerStatus>
    val isServerReachable: StateFlow<Boolean>
    suspend fun checkServerHealth(): ServerHealthResult
}
