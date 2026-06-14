package com.paperless.scanner.worker

import androidx.work.Constraints
import androidx.work.NetworkType
import com.paperless.scanner.data.datastore.TokenManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for the WorkManager [Constraints] used by every upload-queue
 * trigger: the in-app immediate drain ([UploadWorkManager.scheduleImmediateUpload]), the
 * network-reconnect trigger ([com.paperless.scanner.data.network.NetworkMonitor]) and the
 * server-reconnect trigger ([com.paperless.scanner.PaperlessApp]). Keeping it in one place
 * guarantees all three honor the user's network preference identically.
 *
 * Network type follows the "upload only on unmetered networks" preference:
 *  - enabled  → [NetworkType.UNMETERED]: the OS holds the work until a non-metered network
 *    (typically Wi-Fi) is available, so uploads never spend the user's mobile-data plan.
 *  - disabled → [NetworkType.CONNECTED]: any validated connection, including mobile data.
 *
 * Battery/storage deferral mirrors `SyncWorker` — keep the transfer below the OS
 * low-battery / low-storage thresholds rather than draining a nearly-empty battery or a
 * full disk; the work stays enqueued and runs as soon as the constraint clears (#134).
 */
@Singleton
class UploadConstraintsProvider @Inject constructor(
    private val tokenManager: TokenManager
) {
    /**
     * Builds upload constraints reflecting the current preference value. Reads the
     * preference synchronously because the upload-queue triggers are not suspending and
     * run off the main thread (network/server callbacks, post-enqueue calls).
     */
    fun build(): Constraints {
        val unmeteredOnly = tokenManager.getUploadUnmeteredOnlySync()
        return Constraints.Builder()
            .setRequiredNetworkType(
                if (unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
            )
            .setRequiresBatteryNotLow(true)
            .setRequiresStorageNotLow(true)
            .build()
    }
}
