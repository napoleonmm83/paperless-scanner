package com.paperless.scanner.data.network

import kotlinx.coroutines.flow.StateFlow

/**
 * Test-double seam for [NetworkMonitor] (#321): the reachability surface consumed by
 * UploadWorker and ServerHealthViewModel. Lifecycle methods (startMonitoring/destroy)
 * stay on the concrete class — PaperlessApp keeps injecting that directly.
 */
interface NetworkMonitorContract {
    val isOnline: StateFlow<Boolean>
    fun hasValidatedInternet(): Boolean
}
