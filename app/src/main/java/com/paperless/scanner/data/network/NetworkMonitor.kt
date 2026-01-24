package com.paperless.scanner.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.paperless.scanner.data.sync.SyncManager
import com.paperless.scanner.worker.UploadWorker
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class NetworkMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val syncManager: SyncManager
) {
    private val TAG = "NetworkMonitor"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val workManager = WorkManager.getInstance(context)

    private val _isOnline = MutableStateFlow(checkInitialOnlineStatus())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val _isWifiConnected = MutableStateFlow(checkInitialWifiStatus())
    val isWifiConnected: StateFlow<Boolean> = _isWifiConnected.asStateFlow()

    private fun checkInitialOnlineStatus(): Boolean {
        val network = connectivityManager?.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        // Check both INTERNET capability AND validated connection
        // This ensures actual internet access, not just theoretical connectivity
        // (excludes captive portals, payment barriers, WiFi/Mobile without real internet)
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
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
            updateNetworkStatus(network)

            // Note: Do NOT trigger uploads/sync here - onAvailable fires before validation
            // Wait for onCapabilitiesChanged with NET_CAPABILITY_VALIDATED instead
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            // Update online status - require validated internet
            val wasOnline = _isOnline.value
            val isOnlineNow = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

            if (_isOnline.value != isOnlineNow) {
                Log.d(TAG, "Online status changed: $isOnlineNow (validated=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)})")
                _isOnline.value = isOnlineNow

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
            Log.d(TAG, "=== NETWORK LOST ===")
            Log.d(TAG, "Network lost - setting isOnline=false, isWifiConnected=false")
            _isOnline.value = false
            _isWifiConnected.value = false
            Log.d(TAG, "NetworkMonitor state updated: isOnline=${_isOnline.value}, isWifi=${_isWifiConnected.value}")
            Log.d(TAG, "====================")
        }
    }

    private fun updateNetworkStatus(network: Network) {
        val capabilities = connectivityManager?.getNetworkCapabilities(network)
        if (capabilities != null) {
            // Online status requires both INTERNET capability AND validated connection
            _isOnline.value = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            // WiFi status requires WiFi transport AND validated connection
            _isWifiConnected.value = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
                    capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            Log.d(TAG, "Network status: online=${_isOnline.value}, wifi=${_isWifiConnected.value} (validated=${capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)})")
        }
    }

    private fun triggerUploadWorker() {
        try {
            Log.d(TAG, "Triggering UploadWorker for pending uploads")

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val uploadRequest = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(constraints)
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
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /**
     * Check if device has validated internet connection suitable for uploads (synchronous).
     * This is the recommended method to check before starting upload operations.
     *
     * @return true if validated internet available, false otherwise
     */
    fun hasValidatedInternet(): Boolean = checkOnlineStatus()

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
}
