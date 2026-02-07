package com.paperless.scanner.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.paperless.scanner.data.repository.UploadQueueRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Worker that updates all widget instances with current pending upload count
 * and server connectivity status.
 *
 * Can be triggered:
 * - After upload completes (from UploadWorker)
 * - On network status change (from NetworkMonitor)
 * - Periodically via widget updatePeriodMillis
 *
 * Uses the same approach as UploadWorker for Hilt dependency injection.
 */
@HiltWorker
class WidgetUpdateWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val uploadQueueRepository: UploadQueueRepository
) : CoroutineWorker(context, workerParams) {

    companion object {
        private const val TAG = "WidgetUpdateWorker"
        private const val WORK_NAME = "widget_update"

        /**
         * Enqueue a one-shot widget update.
         * Uses REPLACE policy to avoid stacking multiple updates.
         */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<WidgetUpdateWorker>().build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }

    override suspend fun doWork(): Result {
        return try {
            val pendingCount = uploadQueueRepository.getPendingUploadCount()
            val isOnline = checkNetworkConnectivity()

            Log.d(TAG, "Updating widgets: pending=$pendingCount, online=$isOnline")

            updateGlanceWidgets(pendingCount, isOnline)
            updateLegacyWidgets(pendingCount, isOnline)

            // Trigger widget redraw
            triggerWidgetUpdate()

            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "Widget update failed", e)
            Result.retry()
        }
    }

    private fun checkNetworkConnectivity(): Boolean {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
            ?: return false
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private suspend fun updateGlanceWidgets(pendingCount: Int, isOnline: Boolean) {
        try {
            val manager = GlanceAppWidgetManager(context)
            val glanceIds = manager.getGlanceIds(ScannerWidget::class.java)
            val widgetPrefs = WidgetPreferences(context.applicationContext)

            for (glanceId in glanceIds) {
                // Read widget type from SharedPreferences and sync to Glance state
                val appWidgetId = (glanceId as? androidx.glance.appwidget.AppWidgetId)?.appWidgetId
                val widgetType = if (appWidgetId != null) {
                    widgetPrefs.getWidgetConfig(appWidgetId).type.name
                } else {
                    WidgetType.QUICK_SCAN.name
                }

                updateAppWidgetState(context, glanceId) { prefs ->
                    prefs[ScannerWidget.PENDING_COUNT_KEY] = pendingCount
                    prefs[ScannerWidget.SERVER_ONLINE_KEY] = isOnline
                    prefs[ScannerWidget.WIDGET_TYPE_KEY] = widgetType
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to update Glance widget state", e)
        }
    }

    private fun updateLegacyWidgets(pendingCount: Int, isOnline: Boolean) {
        val prefs = context.getSharedPreferences("scanner_widget_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putInt("pending_upload_count", pendingCount)
            .putBoolean("server_online", isOnline)
            .apply()
    }

    private fun triggerWidgetUpdate() {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val componentName = ComponentName(context, ScannerWidgetReceiver::class.java)
        val widgetIds = appWidgetManager.getAppWidgetIds(componentName)

        if (widgetIds.isNotEmpty()) {
            val intent = Intent(context, ScannerWidgetReceiver::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, widgetIds)
            }
            context.sendBroadcast(intent)
        }
    }
}
