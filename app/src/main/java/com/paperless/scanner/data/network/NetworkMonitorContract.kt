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
     * Reactive stream of whether the active network is unmetered (advertises
     * [android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED]). For UI only — the Sync
     * Center collects it to explain why a queued upload is waiting. May briefly lag the active
     * network on multi-network devices; the upload gate uses [isActiveNetworkUnmetered] instead.
     */
    val isUnmetered: StateFlow<Boolean>

    /**
     * Synchronous, freshly read from the active (default) network at call time. Used by
     * [com.paperless.scanner.worker.UploadWorker] as the upload gate for the "upload only on
     * unmetered networks" preference: reading the active network directly avoids trusting the
     * reactive stream, which a capabilities callback can update from a non-active network on
     * multi-network devices. False when there is no active network.
     */
    fun isActiveNetworkUnmetered(): Boolean
}
