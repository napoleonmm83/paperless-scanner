package com.paperless.scanner.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.paperless.scanner.MainActivity
import com.paperless.scanner.R

/**
 * Legacy RemoteViews-based widget implementation.
 *
 * This is used as a fallback on devices where Glance's InvisibleActionTrampolineActivity
 * causes crashes (e.g., OnePlus Android 11, some Samsung devices).
 *
 * Key differences from Glance widget:
 * - Uses traditional RemoteViews instead of Compose-based Glance
 * - Direct PendingIntent for activity launch (no trampoline)
 * - More compatible with custom Android OEM implementations
 */
class LegacyScannerWidget : AppWidgetProvider() {

    companion object {
        private const val TAG = "LegacyScannerWidget"
        private const val PREFS_NAME = "scanner_widget_prefs"
        private const val KEY_PENDING_COUNT = "pending_upload_count"
        private const val KEY_SERVER_ONLINE = "server_online"

        /**
         * Update pending count and refresh widgets.
         */
        fun updatePendingCount(context: Context, count: Int) {
            // Save to SharedPreferences
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putInt(KEY_PENDING_COUNT, count)
                .apply()

            // Trigger widget update
            val intent = Intent(context, LegacyScannerWidget::class.java).apply {
                action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            }
            context.sendBroadcast(intent)
        }

        /**
         * Get stored pending count.
         */
        private fun getPendingCount(context: Context): Int {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getInt(KEY_PENDING_COUNT, 0)
        }

        /**
         * Get stored server online status.
         */
        private fun getServerOnline(context: Context): Boolean {
            return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_SERVER_ONLINE, false)
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val crashlytics = FirebaseCrashlytics.getInstance()
        crashlytics.log("LegacyScannerWidget.onUpdate called for ${appWidgetIds.size} widgets")
        crashlytics.setCustomKey("widget_type", "legacy_remoteviews")
        crashlytics.setCustomKey("device_info", WidgetDeviceChecker.getDeviceInfo())

        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        Log.d(TAG, "Widget options changed for $appWidgetId")
        updateAppWidget(context, appWidgetManager, appWidgetId)
    }

    override fun onEnabled(context: Context) {
        Log.d(TAG, "Legacy widget enabled")
        FirebaseCrashlytics.getInstance().log("LegacyScannerWidget enabled")
    }

    override fun onDisabled(context: Context) {
        Log.d(TAG, "Legacy widget disabled")
        FirebaseCrashlytics.getInstance().log("LegacyScannerWidget disabled")
    }

    private fun updateAppWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val crashlytics = FirebaseCrashlytics.getInstance()

        try {
            // Read widget configuration to determine type (synchronous SharedPreferences)
            val config = try {
                WidgetPreferences(context.applicationContext).getWidgetConfig(appWidgetId)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read widget config, using default", e)
                WidgetConfig()
            }

            when (config.type) {
                WidgetType.QUICK_SCAN -> updateQuickScanWidget(context, appWidgetManager, appWidgetId)
                WidgetType.STATUS -> updateStatusWidget(context, appWidgetManager, appWidgetId)
                WidgetType.COMBINED -> updateCombinedWidget(context, appWidgetManager, appWidgetId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update widget $appWidgetId", e)
            crashlytics.recordException(e)
            crashlytics.setCustomKey("widget_update_failed", true)
            crashlytics.setCustomKey("widget_id", appWidgetId)
        }
    }

    /**
     * Default widget layout (original single-tap scanner).
     * Used as placeholder for STATUS and COMBINED types until Tasks 116/111 are implemented.
     */
    private fun updateDefaultWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_scanner)

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            action = "com.paperless.scanner.WIDGET_LAUNCH_$appWidgetId"
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            appWidgetId,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        views.setOnClickPendingIntent(R.id.widget_container, pendingIntent)

        val pendingCount = getPendingCount(context)
        if (pendingCount > 0) {
            views.setViewVisibility(R.id.widget_pending_container, View.VISIBLE)
            views.setTextViewText(
                R.id.widget_pending_count,
                context.getString(R.string.widget_pending_format, pendingCount)
            )
        } else {
            views.setViewVisibility(R.id.widget_pending_container, View.GONE)
        }

        appWidgetManager.updateAppWidget(appWidgetId, views)

        FirebaseCrashlytics.getInstance()
            .log("LegacyScannerWidget default updated for widget $appWidgetId")
    }

    /**
     * Status widget showing pending upload count and server connectivity status.
     * Tapping opens the app to the SyncCenter screen.
     */
    private fun updateStatusWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_status)

        // Tap opens SyncCenter via deep link
        views.setOnClickPendingIntent(
            R.id.widget_status_container,
            createDeepLinkPendingIntent(context, "paperless://status", appWidgetId * 10)
        )

        // Pending count
        val pendingCount = getPendingCount(context)
        views.setTextViewText(
            R.id.widget_status_pending,
            if (pendingCount > 0) {
                context.getString(R.string.widget_pending_format, pendingCount)
            } else {
                context.getString(R.string.widget_no_pending)
            }
        )

        // Server status
        val isOnline = getServerOnline(context)
        views.setImageViewResource(
            R.id.widget_status_icon,
            if (isOnline) R.drawable.ic_widget_status_online
            else R.drawable.ic_widget_status_offline
        )
        views.setTextViewText(
            R.id.widget_status_server,
            context.getString(
                if (isOnline) R.string.widget_status_online
                else R.string.widget_status_offline
            )
        )

        appWidgetManager.updateAppWidget(appWidgetId, views)

        FirebaseCrashlytics.getInstance()
            .log("LegacyScannerWidget status updated for widget $appWidgetId")
    }

    /**
     * Combined widget: Quick actions (Camera + Gallery) on top, status bar on bottom.
     * Top row buttons launch deep links, bottom status row opens SyncCenter.
     */
    private fun updateCombinedWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val views = RemoteViews(context.packageName, R.layout.widget_combined)

        // Camera button → paperless://scan/camera
        views.setOnClickPendingIntent(
            R.id.widget_combined_camera,
            createDeepLinkPendingIntent(context, "paperless://scan/camera", appWidgetId * 10 + 1)
        )

        // Gallery button → paperless://scan/gallery
        views.setOnClickPendingIntent(
            R.id.widget_combined_gallery,
            createDeepLinkPendingIntent(context, "paperless://scan/gallery", appWidgetId * 10 + 2)
        )

        // Status row → SyncCenter
        views.setOnClickPendingIntent(
            R.id.widget_combined_status,
            createDeepLinkPendingIntent(context, "paperless://status", appWidgetId * 10 + 3)
        )

        // Pending count
        val pendingCount = getPendingCount(context)
        views.setTextViewText(
            R.id.widget_combined_pending,
            if (pendingCount > 0) {
                context.getString(R.string.widget_pending_format, pendingCount)
            } else {
                context.getString(R.string.widget_no_pending)
            }
        )

        // Server status icon
        val isOnline = getServerOnline(context)
        views.setImageViewResource(
            R.id.widget_combined_status_icon,
            if (isOnline) R.drawable.ic_widget_status_online
            else R.drawable.ic_widget_status_offline
        )

        appWidgetManager.updateAppWidget(appWidgetId, views)

        FirebaseCrashlytics.getInstance()
            .log("LegacyScannerWidget combined updated for widget $appWidgetId")
    }

    /**
     * Determine if widget is in horizontal-only mode (too short for grid layout).
     * Threshold: ~80dp height corresponds to roughly 1 cell row.
     */
    private fun isHorizontalLayout(
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ): Boolean {
        val options = appWidgetManager.getAppWidgetOptions(appWidgetId)
        val minHeight = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 0)
        // Below ~80dp means the widget is a single row strip
        return minHeight < 80
    }

    /**
     * Quick Scan widget with 3 action buttons (Camera, Gallery, File).
     * Adapts layout based on widget size:
     * - Horizontal strip (1 row): 3 compact icon buttons in a row
     * - Grid (2+ rows): 2+1 grid with labels and larger icons
     */
    private fun updateQuickScanWidget(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int
    ) {
        val horizontal = isHorizontalLayout(appWidgetManager, appWidgetId)
        val layout = if (horizontal) R.layout.widget_quick_scan_horizontal
            else R.layout.widget_quick_scan

        Log.d(TAG, "Quick scan layout for widget $appWidgetId: horizontal=$horizontal")

        val views = RemoteViews(context.packageName, layout)

        // Camera button → paperless://scan/camera
        views.setOnClickPendingIntent(
            R.id.widget_btn_camera,
            createDeepLinkPendingIntent(context, "paperless://scan/camera", appWidgetId * 10 + 1)
        )

        // Gallery button → paperless://scan/gallery
        views.setOnClickPendingIntent(
            R.id.widget_btn_gallery,
            createDeepLinkPendingIntent(context, "paperless://scan/gallery", appWidgetId * 10 + 2)
        )

        // File button → paperless://scan/file
        views.setOnClickPendingIntent(
            R.id.widget_btn_file,
            createDeepLinkPendingIntent(context, "paperless://scan/file", appWidgetId * 10 + 3)
        )

        appWidgetManager.updateAppWidget(appWidgetId, views)

        FirebaseCrashlytics.getInstance()
            .log("LegacyScannerWidget quick scan updated for widget $appWidgetId (horizontal=$horizontal)")
    }

    /**
     * Creates a PendingIntent that launches a deep link URI via MainActivity.
     */
    private fun createDeepLinkPendingIntent(
        context: Context,
        deepLinkUri: String,
        requestCode: Int
    ): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(deepLinkUri)).apply {
            component = ComponentName(context.packageName, MainActivity::class.java.name)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
