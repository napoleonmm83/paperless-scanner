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

    /**
     * True when the active network is unmetered (typically Wi-Fi / Ethernet) — i.e. it
     * advertises [android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED].
     *
     * Lets [com.paperless.scanner.worker.UploadWorker] enforce the user's "upload only on
     * unmetered networks" preference at runtime, independent of the WorkManager network
     * constraint baked in at enqueue time. Returns false when there is no active network.
     */
    fun isActiveNetworkUnmetered(): Boolean
}
