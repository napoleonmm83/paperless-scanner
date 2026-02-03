package com.paperless.scanner.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Build
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
            val views = RemoteViews(context.packageName, R.layout.widget_scanner)

            // Create PendingIntent for launching MainActivity
            // Use FLAG_IMMUTABLE for security (required on Android 12+)
            val launchIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                // Add a unique action to ensure PendingIntent is unique per widget
                action = "com.paperless.scanner.WIDGET_LAUNCH_$appWidgetId"
            }

            val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId,
                launchIntent,
                pendingIntentFlags
            )

            // Set click handler on the entire widget
            views.setOnClickPendingIntent(R.id.widget_container, pendingIntent)

            // Update pending count display
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

            // Update the widget
            appWidgetManager.updateAppWidget(appWidgetId, views)

            crashlytics.log("LegacyScannerWidget updated successfully for widget $appWidgetId")

        } catch (e: Exception) {
            Log.e(TAG, "Failed to update widget $appWidgetId", e)
            crashlytics.recordException(e)
            crashlytics.setCustomKey("widget_update_failed", true)
            crashlytics.setCustomKey("widget_id", appWidgetId)
        }
    }
}
