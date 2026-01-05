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

    private fun checkInitialOnlineStatus(): Boolean {
        val network = connectivityManager?.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            Log.d(TAG, "Network available - triggering upload queue and sync")
            _isOnline.value = true

            // Trigger upload queue worker immediately
            triggerUploadWorker()

            // Trigger sync when network becomes available
            scope.launch {
                try {
                    syncManager.performFullSync()
                } catch (e: Exception) {
                    Log.e(TAG, "Auto-sync failed on network reconnect", e)
                }
            }
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "Network lost")
            _isOnline.value = false
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

    // Synchronous check for backwards compatibility
    fun checkOnlineStatus(): Boolean {
        val network = connectivityManager?.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
