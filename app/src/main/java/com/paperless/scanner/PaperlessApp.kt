package com.paperless.scanner

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.paperless.scanner.data.analytics.SubscriptionAnalyticsSync
import com.paperless.scanner.data.billing.BillingManager
import com.paperless.scanner.data.config.RemoteConfigManager
import com.paperless.scanner.data.health.ServerHealthMonitor
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.data.sync.SyncWorker
import com.paperless.scanner.util.ScanDraftCache
import com.paperless.scanner.util.SharedFileCache
import com.paperless.scanner.worker.UploadWorker
import coil3.ImageLoader
import coil3.SingletonImageLoader
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltAndroidApp
class PaperlessApp : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var networkMonitor: NetworkMonitor

    @Inject
    lateinit var serverHealthMonitor: ServerHealthMonitor

    @Inject
    lateinit var billingManager: BillingManager

    @Inject
    lateinit var remoteConfigManager: RemoteConfigManager

    @Inject
    lateinit var subscriptionAnalyticsSync: SubscriptionAnalyticsSync

    @Inject
    lateinit var imageLoader: ImageLoader

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()

        // Initialize offline mode infrastructure
        initializeOfflineMode()

        // Initialize server health monitoring with lifecycle awareness
        initializeServerHealthMonitoring()

        // Observe server reachability and trigger upload queue when server comes online
        observeServerReachability()

        cleanupOldCacheFiles()

        // Initialize Google Play Billing
        billingManager.initialize()

        // Fetch launch-promo flags (fail-closed defaults until the first successful fetch)
        remoteConfigManager.initialize()

        // Mirror subscription status into Crashlytics/Analytics for the process
        // lifetime (#296). Torn down with appScope in onTerminate.
        subscriptionAnalyticsSync.start(appScope)
    }

    /**
     * Best-effort teardown of the app-singleton coroutine scopes (#142).
     *
     * NOTE: on real Android devices [onTerminate] is NEVER called — the OS kills the process
     * without notice and reclaims these scopes with it. It DOES run under the emulator and in
     * instrumentation, so this gives an explicit, testable teardown seam and prevents the
     * managers' coroutine scopes from outliving an orderly shutdown.
     */
    override fun onTerminate() {
        networkMonitor.destroy()
        serverHealthMonitor.destroy()
        billingManager.destroy()
        appScope.cancel()
        super.onTerminate()
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

                    // Refresh widget data when app comes to foreground
                    com.paperless.scanner.widget.WidgetUpdateWorker.enqueue(this@PaperlessApp)
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

    /**
     * Observe server reachability and automatically trigger upload queue when server becomes reachable.
     * This handles the case where internet is available but Paperless server was offline.
     */
    private fun observeServerReachability() {
        appScope.launch {
            var wasReachable = false
            serverHealthMonitor.isServerReachable.collect { isReachable ->
                Log.d(TAG, "Server reachability changed: wasReachable=$wasReachable, isReachable=$isReachable")

                // Trigger upload queue when server transitions from offline to online
                if (!wasReachable && isReachable) {
                    Log.d(TAG, "Server became reachable - triggering upload queue worker")
                    triggerUploadWorker()
                }

                wasReachable = isReachable
            }
        }
    }

    /**
     * Trigger UploadWorker to process pending uploads.
     * Called when server becomes reachable.
     */
    private fun triggerUploadWorker() {
        try {
            val workManager = WorkManager.getInstance(this)
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                // Match UploadWorkManager.scheduleImmediateUpload: defer below the
                // OS low-battery / low-storage thresholds so a reconnect-triggered
                // drain doesn't drain a nearly-empty battery / full disk (#134).
                .setRequiresBatteryNotLow(true)
                .setRequiresStorageNotLow(true)
                .build()

            val uploadRequest = OneTimeWorkRequestBuilder<UploadWorker>()
                .setConstraints(constraints)
                .build()

            workManager.enqueueUniqueWork(
                "upload_on_server_reconnect",
                ExistingWorkPolicy.REPLACE,
                uploadRequest
            )

            Log.d(TAG, "UploadWorker enqueued after server reconnect")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to trigger UploadWorker", e)
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    /**
     * Coil ImageLoader Factory implementation.
     * Returns the Hilt-injected ImageLoader with Auth + SSL support.
     */
    override fun newImageLoader(context: Context): ImageLoader {
        return imageLoader
    }

    private fun cleanupOldCacheFiles() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val cacheDir = cacheDir
                val now = System.currentTimeMillis()
                var deletedCount = 0
                var freedBytes = 0L

                fun sweep(dir: File, protectedFileNames: Set<String> = emptySet(), accept: (File) -> Boolean = { true }) {
                    val result = SharedFileCache.cleanupAgedUnprotected(
                        dir = dir,
                        now = now,
                        maxAgeMillis = CACHE_MAX_AGE_MS,
                        protectedFileNames = protectedFileNames,
                        accept = accept
                    )
                    deletedCount += result.deletedCount
                    freedBytes += result.freedBytes
                }

                // #241: sweep aged transient PDFs the viewer downloads into shared_pdfs
                // (the pre-#241 root-only "document_" sweep already handled these).
                sweep(File(cacheDir, SharedFileCache.SHARED_PDFS_DIR))

                // #307: sweep aged scan images too, but EXCLUDE files referenced by a
                // persisted in-progress scan draft. ScanViewModel mirrors its draft's
                // shared_images backing file names into ScanDraftCache (App-readable
                // SharedPreferences, survives process death) precisely so this boot-time
                // sweep — which runs before any ScanViewModel exists — can protect a
                // draft's cropped_* page that a delayed restore would still need.
                // Upload-only rotated_* images are never persisted, so they age out.
                val protectedNames = ScanDraftCache(this@PaperlessApp).getProtectedFileNames()
                sweep(File(cacheDir, SharedFileCache.SHARED_IMAGES_DIR), protectedFileNames = protectedNames)

                // Legacy: PDFs written to the cache root before #241 — migration cleanup.
                sweep(cacheDir) { it.name.startsWith("document_") }

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
