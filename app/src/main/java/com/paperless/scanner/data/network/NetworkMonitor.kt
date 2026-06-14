package com.paperless.scanner.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.util.Log
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.paperless.scanner.data.sync.SyncManager
import com.paperless.scanner.worker.UploadConstraintsProvider
import com.paperless.scanner.worker.UploadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncManager: SyncManager,
    private val uploadConstraintsProvider: UploadConstraintsProvider
) : NetworkMonitorContract {
    private val TAG = "NetworkMonitor"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val workManager = WorkManager.getInstance(context)

    // Debounce to prevent rapid online/offline flapping
    private var offlineDebounceJob: kotlinx.coroutines.Job? = null
    private val OFFLINE_DEBOUNCE_MS = 2000L // Wait 2 seconds before declaring offline

    private val _isOnline = MutableStateFlow(checkInitialOnlineStatus())
    override val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _isWifiConnected = MutableStateFlow(checkInitialWifiStatus())
    val isWifiConnected: StateFlow<Boolean> = _isWifiConnected.asStateFlow()

    private fun checkInitialOnlineStatus(): Boolean {
        val network = connectivityManager?.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return hasUsableUplink(capabilities)
    }

    private fun checkInitialWifiStatus(): Boolean {
        val network = connectivityManager?.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        // Check both WiFi transport AND validated internet connection
        // This ensures we have actual internet access, not just WiFi connection
        // (e.g., excludes captive portals, payment barriers, WiFi without internet)
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "Network available")

            // Cancel any pending offline debounce - network is back
            offlineDebounceJob?.cancel()
            offlineDebounceJob = null

            updateNetworkStatus(network)

            // Note: Do NOT trigger uploads/sync here - onAvailable fires before validation
            // Wait for onCapabilitiesChanged with NET_CAPABILITY_VALIDATED instead
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            // Update online status - require validated internet with a real uplink
            val wasOnline = _isOnline.value
            val isOnlineNow = hasUsableUplink(capabilities)

            if (_isOnline.value != isOnlineNow) {
                Log.d(TAG, "Online status changed: $isOnlineNow (validated=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)})")
                _isOnline.value = isOnlineNow

                // Update widgets with new connectivity status
                com.paperless.scanner.widget.WidgetUpdateWorker.enqueue(context)

                // Trigger uploads and sync ONLY when transitioning to validated online state
                if (!wasOnline && isOnlineNow) {
                    Log.d(TAG, "Validated internet now available - triggering upload queue and sync")

                    // Trigger upload queue worker
                    triggerUploadWorker()

                    // Trigger sync
                    scope.launch {
                        try {
                            syncManager.performFullSync()
                        } catch (e: Exception) {
                            Log.e(TAG, "Auto-sync failed on network reconnect", e)
                        }
                    }
                }
            }

            // Update WiFi status - require both WiFi transport AND validated internet
            val isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            if (_isWifiConnected.value != isWifi) {
                Log.d(TAG, "WiFi status changed: $isWifi (transport=${capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)}, validated=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)})")
                _isWifiConnected.value = isWifi
            }
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "=== NETWORK LOST (debounced) ===")

            // Cancel any pending offline transition
            offlineDebounceJob?.cancel()

            // Debounce: Wait before declaring offline to prevent flapping
            // This handles cases where network briefly disconnects during handoff
            offlineDebounceJob = scope.launch {
                Log.d(TAG, "Starting offline debounce (${OFFLINE_DEBOUNCE_MS}ms)")
                delay(OFFLINE_DEBOUNCE_MS)

                // Double-check if still offline after debounce
                val stillOffline = !checkOnlineStatus()
                if (stillOffline) {
                    Log.d(TAG, "Debounce complete - confirming offline state")
                    _isOnline.value = false
                    _isWifiConnected.value = false
                    Log.d(TAG, "NetworkMonitor state updated: isOnline=false, isWifi=false")
                } else {
                    Log.d(TAG, "Debounce complete - network recovered, staying online")
                }
            }
            Log.d(TAG, "================================")
        }
    }

    private fun updateNetworkStatus(network: Network) {
        val capabilities = connectivityManager?.getNetworkCapabilities(network)
        if (capabilities != null) {
            // Online status requires validated internet with a real uplink
            _isOnline.value = hasUsableUplink(capabilities)
            // WiFi status requires WiFi transport AND validated connection
            _isWifiConnected.value = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            Log.d(TAG, "Network status: online=${_isOnline.value}, wifi=${_isWifiConnected.value} (validated=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)})")
        }
    }

    private fun triggerUploadWorker() {
        try {
            Log.d(TAG, "Triggering UploadWorker for pending uploads")

            // Shared constraints (network preference + #134 battery/storage deferral) so a
            // reconnect-triggered drain honors the user's unmetered-only setting exactly like
            // the in-app trigger.
            val uploadRequest = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(uploadConstraintsProvider.build())
                .build()

            workManager.enqueueUniqueWork(
                "instant_upload_on_reconnect",
                ExistingWorkPolicy.REPLACE,
                uploadRequest
            )

            Log.d(TAG, "UploadWorker enqueued successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger UploadWorker", e)
        }
    }

    /**
     * Cancels this monitor's coroutine scope and any pending offline-debounce job (#142).
     *
     * Idempotent. The scope is an app-singleton, so this is a best-effort teardown invoked
     * from [com.paperless.scanner.PaperlessApp.onTerminate] — on real devices the OS reclaims
     * the process (and the scope) without notice; this stops any in-flight debounce / auto-sync
     * coroutine in an orderly shutdown and under instrumentation.
     */
    fun destroy() {
        // Unregister the ConnectivityManager callback FIRST so the framework stops delivering
        // callbacks into a half-torn-down monitor once the scope is cancelled. stopMonitoring()
        // is no-op-safe (it catches the not-registered case) if monitoring was never started.
        stopMonitoring()
        offlineDebounceJob?.cancel()
        offlineDebounceJob = null
        scope.cancel()
    }

    fun startMonitoring() {
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        try {
            connectivityManager?.registerNetworkCallback(request, networkCallback)
            Log.d(TAG, "Network monitoring started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start network monitoring", e)
        }
    }

    fun stopMonitoring() {
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
            Log.d(TAG, "Network monitoring stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to stop network monitoring", e)
        }
    }

    /**
     * Check if device has validated internet connection (synchronous).
     * Used for backwards compatibility and immediate checks.
     *
     * This ensures:
     * 1. Network is available
     * 2. Has internet capability
     * 3. Internet connection is validated by Android (not captive portal, payment barrier, etc.)
     *
     * @return true if validated internet available, false otherwise
     */
    fun checkOnlineStatus(): Boolean {
        val network = connectivityManager?.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return hasUsableUplink(capabilities)
    }

    /**
     * Check if device has validated internet connection suitable for uploads (synchronous).
     * This is the recommended method to check before starting upload operations.
     *
     * @return true if validated internet available, false otherwise
     */
    override fun hasValidatedInternet(): Boolean = checkOnlineStatus()

    /**
     * Check if device is connected to WiFi with validated internet access (synchronous).
     * Used for AI WiFi-only feature checks.
     *
     * This method ensures:
     * 1. Device is connected via WiFi transport (not mobile data)
     * 2. Internet connection is validated by Android (not captive portal, payment barrier, etc.)
     *
     * @return true if connected via WiFi with actual internet, false otherwise
     */
    fun isWifiConnectedSync(): Boolean {
        val network = connectivityManager?.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        // Require both WiFi transport AND validated internet connection
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * True when the active network is unmetered (NET_CAPABILITY_NOT_METERED), i.e. uploading
     * over it does not spend the user's mobile-data plan. Backs the runtime enforcement of the
     * "upload only on unmetered networks" preference in [com.paperless.scanner.worker.UploadWorker].
     *
     * Uses the OS metered flag rather than the Wi-Fi transport so a metered hotspot is
     * correctly excluded and an unmetered Ethernet/USB tether is correctly allowed.
     */
    override fun isActiveNetworkUnmetered(): Boolean {
        val network = connectivityManager?.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    companion object {
        /**
         * True when [capabilities] describe a network with actual internet access.
         *
         * Beyond INTERNET+VALIDATED (which already excludes captive portals and
         * payment barriers) this guards against the dead-VPN case (#364): a VPN whose
         * underlying network is gone (observed with Cloudflare 1.1.1.1 in airplane
         * mode on a Pixel 8) keeps its tun interface up and continues to advertise
         * INTERNET|VALIDATED with `UnderlyingNetworks: []`. Since Android Q the system
         * merges the underlying network's transports into the VPN network's
         * capabilities, so a transport set containing ONLY VPN means there is no real
         * uplink behind it.
         */
        internal fun hasUsableUplink(capabilities: NetworkCapabilities): Boolean {
            if (!capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            ) {
                return false
            }
            // Transport merging for VPN networks is only reliable on Q+; below that,
            // keep the legacy behavior rather than risk a false offline under a real VPN.
            val vpnWithoutUplink = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    NON_VPN_TRANSPORTS.none { capabilities.hasTransport(it) }
            return !vpnWithoutUplink
        }

        // Every transport a VPN's underlying network can surface as (codex P2: a VPN
        // over USB reverse-tethering shows VPN+USB only). The newer constants are
        // compile-time ints — hasTransport() with them is a harmless no-op pre-release.
        private val NON_VPN_TRANSPORTS = intArrayOf(
            NetworkCapabilities.TRANSPORT_WIFI,
            NetworkCapabilities.TRANSPORT_CELLULAR,
            NetworkCapabilities.TRANSPORT_ETHERNET,
            NetworkCapabilities.TRANSPORT_BLUETOOTH,
            NetworkCapabilities.TRANSPORT_WIFI_AWARE,
            NetworkCapabilities.TRANSPORT_LOWPAN,
            NetworkCapabilities.TRANSPORT_USB,
            NetworkCapabilities.TRANSPORT_THREAD,
            NetworkCapabilities.TRANSPORT_SATELLITE,
        )
    }
}
