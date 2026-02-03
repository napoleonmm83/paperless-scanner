package com.paperless.scanner.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * Hybrid Widget Receiver that automatically selects between Glance and Legacy implementations.
 *
 * On devices known to have issues with Glance's InvisibleActionTrampolineActivity
 * (e.g., OnePlus Android 11), this receiver delegates to the LegacyScannerWidget
 * which uses traditional RemoteViews for maximum compatibility.
 *
 * On other devices, it uses the modern Glance-based ScannerWidget for better
 * Compose integration and features.
 *
 * Detection is performed once per receiver lifecycle and logged to Crashlytics
 * for monitoring crash rates across different implementations.
 */
class ScannerWidgetReceiver : GlanceAppWidgetReceiver() {

    companion object {
        private const val TAG = "ScannerWidgetReceiver"
    }

    override val glanceAppWidget: GlanceAppWidget = ScannerWidget()

    // Lazy initialization to check device compatibility
    private val shouldUseLegacy: Boolean by lazy {
        val useLegacy = WidgetDeviceChecker.shouldUseLegacyWidget()
        val crashlytics = FirebaseCrashlytics.getInstance()

        crashlytics.setCustomKey("widget_use_legacy", useLegacy)
        crashlytics.setCustomKey("device_info", WidgetDeviceChecker.getDeviceInfo())

        Log.d(TAG, "Device check: useLegacy=$useLegacy, ${WidgetDeviceChecker.getDeviceInfo()}")

        useLegacy
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val crashlytics = FirebaseCrashlytics.getInstance()

        if (shouldUseLegacy) {
            // Delegate to legacy RemoteViews implementation
            crashlytics.log("Using LegacyScannerWidget for update")
            Log.d(TAG, "Delegating to LegacyScannerWidget")

            val legacyWidget = LegacyScannerWidget()
            legacyWidget.onUpdate(context, appWidgetManager, appWidgetIds)

        } else {
            // Use Glance implementation (default)
            crashlytics.log("Using Glance ScannerWidget for update")
            Log.d(TAG, "Using Glance ScannerWidget")

            try {
                super.onUpdate(context, appWidgetManager, appWidgetIds)
            } catch (e: Exception) {
                // If Glance fails, fall back to legacy
                Log.e(TAG, "Glance widget update failed, falling back to legacy", e)
                crashlytics.recordException(e)
                crashlytics.setCustomKey("glance_fallback_triggered", true)

                val legacyWidget = LegacyScannerWidget()
                legacyWidget.onUpdate(context, appWidgetManager, appWidgetIds)
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        val crashlytics = FirebaseCrashlytics.getInstance()

        if (shouldUseLegacy) {
            // Delegate all broadcasts to legacy widget
            crashlytics.log("LegacyScannerWidget onReceive: ${intent.action}")

            val legacyWidget = LegacyScannerWidget()
            legacyWidget.onReceive(context, intent)

        } else {
            // Use Glance implementation
            crashlytics.log("Glance ScannerWidget onReceive: ${intent.action}")

            try {
                super.onReceive(context, intent)
            } catch (e: Exception) {
                // If Glance fails, fall back to legacy
                Log.e(TAG, "Glance onReceive failed, falling back to legacy", e)
                crashlytics.recordException(e)
                crashlytics.setCustomKey("glance_receive_fallback", true)

                val legacyWidget = LegacyScannerWidget()
                legacyWidget.onReceive(context, intent)
            }
        }
    }

    override fun onEnabled(context: Context) {
        val crashlytics = FirebaseCrashlytics.getInstance()
        crashlytics.log("Widget enabled: useLegacy=$shouldUseLegacy")
        Log.d(TAG, "Widget enabled: useLegacy=$shouldUseLegacy")

        if (shouldUseLegacy) {
            LegacyScannerWidget().onEnabled(context)
        } else {
            try {
                super.onEnabled(context)
            } catch (e: Exception) {
                Log.e(TAG, "Glance onEnabled failed", e)
                crashlytics.recordException(e)
            }
        }
    }

    override fun onDisabled(context: Context) {
        val crashlytics = FirebaseCrashlytics.getInstance()
        crashlytics.log("Widget disabled: useLegacy=$shouldUseLegacy")
        Log.d(TAG, "Widget disabled: useLegacy=$shouldUseLegacy")

        if (shouldUseLegacy) {
            LegacyScannerWidget().onDisabled(context)
        } else {
            try {
                super.onDisabled(context)
            } catch (e: Exception) {
                Log.e(TAG, "Glance onDisabled failed", e)
                crashlytics.recordException(e)
            }
        }
    }
}
