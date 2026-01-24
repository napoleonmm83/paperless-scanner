package com.paperless.scanner

import android.app.Application
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import com.paperless.scanner.data.billing.BillingManager
import com.paperless.scanner.data.health.ServerHealthMonitor
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.data.sync.SyncWorker
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class PaperlessApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var networkMonitor: NetworkMonitor

    @Inject
    lateinit var serverHealthMonitor: ServerHealthMonitor

    @Inject
    lateinit var billingManager: BillingManager

    override fun onCreate() {
        super.onCreate()

        // Initialize offline mode infrastructure
        initializeOfflineMode()

        // Initialize server health monitoring with lifecycle awareness
        initializeServerHealthMonitoring()

        cleanupOldCacheFiles()

        // Initialize Google Play Billing
        billingManager.initialize()
    }

    private fun initializeOfflineMode() {
        try {
            // Start network monitoring for auto-sync on reconnect
            networkMonitor.startMonitoring()
            Log.d(TAG, "Network monitoring started")

            // Schedule periodic sync with WorkManager
            SyncWorker.schedule(this)
            Log.d(TAG, "Periodic sync scheduled")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize offline mode", e)
        }
    }

    private fun initializeServerHealthMonitoring() {
        try {
            // Setup App Lifecycle Observer for intelligent polling
            ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    // App entered foreground - start polling every 30s
                    serverHealthMonitor.onForeground()
                    Log.d(TAG, "Server health monitoring: Foreground mode (30s interval)")
                }

                override fun onStop(owner: LifecycleOwner) {
                    // App entered background - switch to 5min interval
                    serverHealthMonitor.onBackground()
                    Log.d(TAG, "Server health monitoring: Background mode (5min interval)")
                }
            })
            Log.d(TAG, "Server health monitoring initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize server health monitoring", e)
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    private fun cleanupOldCacheFiles() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val cacheDir = cacheDir
                val oneHourAgo = System.currentTimeMillis() - CACHE_MAX_AGE_MS
                var deletedCount = 0
                var freedBytes = 0L

                cacheDir.listFiles()?.forEach { file ->
                    if (file.isFile &&
                        file.name.startsWith("document_") &&
                        file.lastModified() < oneHourAgo
                    ) {
                        freedBytes += file.length()
                        if (file.delete()) {
                            deletedCount++
                        }
                    }
                }

                if (deletedCount > 0) {
                    val freedMB = freedBytes / (1024.0 * 1024.0)
                    Log.d(TAG, "Cache cleanup: $deletedCount files deleted (${String.format("%.2f", freedMB)} MB)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Cache cleanup failed", e)
            }
        }
    }

    companion object {
        private const val TAG = "PaperlessApp"
        private const val CACHE_MAX_AGE_MS = 60 * 60 * 1000L // 1 hour
    }
}
