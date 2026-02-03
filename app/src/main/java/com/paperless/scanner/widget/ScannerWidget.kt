@file:SuppressLint("RestrictedApi")

package com.paperless.scanner.widget

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.ActionParameters
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.paperless.scanner.MainActivity
import com.paperless.scanner.R

/**
 * ActionCallback to launch MainActivity from widget.
 *
 * Uses ActionCallback instead of actionStartActivity() to avoid Glance's
 * InvisibleActionTrampolineActivity which causes IllegalArgumentException
 * on OnePlus Android 11 devices.
 *
 * Crash: "List adapter activity trampoline invoked without specifying target intent"
 * Fix: Create PendingIntent with FLAG_IMMUTABLE directly, bypassing trampoline
 *
 * IMPROVED (2026-02): Added multiple fallback strategies and explicit ComponentName
 * to maximize compatibility across OEM Android implementations.
 */
class LaunchMainActivityCallback : ActionCallback {

    companion object {
        private const val TAG = "ScannerWidget"
    }

    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val crashlytics = FirebaseCrashlytics.getInstance()

        // Log widget click for monitoring
        crashlytics.log("Widget clicked - launching MainActivity (Glance)")
        crashlytics.setCustomKey("widget_launch_method", "ActionCallback_Improved")
        crashlytics.setCustomKey("device_info", WidgetDeviceChecker.getDeviceInfo())

        // Strategy 1: Direct startActivity with explicit ComponentName (most reliable)
        if (tryDirectLaunch(context, crashlytics)) return

        // Strategy 2: PendingIntent.send() as fallback
        if (tryPendingIntentLaunch(context, crashlytics)) return

        // Strategy 3: Package manager launch as last resort
        tryPackageManagerLaunch(context, crashlytics)
    }

    /**
     * Strategy 1: Direct startActivity with explicit ComponentName.
     * This bypasses any trampoline mechanisms and is the most reliable approach.
     */
    private fun tryDirectLaunch(context: Context, crashlytics: FirebaseCrashlytics): Boolean {
        return try {
            val intent = Intent().apply {
                component = ComponentName(context.packageName, MainActivity::class.java.name)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            }

            context.startActivity(intent)

            Log.d(TAG, "Widget launch successful via direct startActivity")
            crashlytics.log("Widget launch successful via direct startActivity")
            crashlytics.setCustomKey("widget_launch_strategy", "direct")
            true

        } catch (e: Exception) {
            Log.w(TAG, "Direct startActivity failed", e)
            crashlytics.log("Direct startActivity failed: ${e.message}")
            false
        }
    }

    /**
     * Strategy 2: PendingIntent with FLAG_IMMUTABLE.
     * Works on most devices but can fail on some OEM implementations.
     */
    private fun tryPendingIntentLaunch(context: Context, crashlytics: FirebaseCrashlytics): Boolean {
        return try {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                // Add unique action to ensure fresh PendingIntent
                action = "com.paperless.scanner.WIDGET_LAUNCH_${System.currentTimeMillis()}"
            }

            val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val pendingIntent = PendingIntent.getActivity(
                context,
                System.currentTimeMillis().toInt(),
                intent,
                pendingIntentFlags
            )

            pendingIntent.send()

            Log.d(TAG, "Widget launch successful via PendingIntent")
            crashlytics.log("Widget launch successful via PendingIntent")
            crashlytics.setCustomKey("widget_launch_strategy", "pending_intent")
            true

        } catch (e: Exception) {
            Log.w(TAG, "PendingIntent launch failed", e)
            crashlytics.log("PendingIntent launch failed: ${e.message}")
            crashlytics.recordException(e)
            false
        }
    }

    /**
     * Strategy 3: Use PackageManager to get launch intent.
     * Last resort - uses system's default launcher intent resolution.
     */
    private fun tryPackageManagerLaunch(context: Context, crashlytics: FirebaseCrashlytics) {
        try {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            if (launchIntent != null) {
                launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                context.startActivity(launchIntent)

                Log.d(TAG, "Widget launch successful via PackageManager")
                crashlytics.log("Widget launch successful via PackageManager")
                crashlytics.setCustomKey("widget_launch_strategy", "package_manager")
            } else {
                throw IllegalStateException("PackageManager returned null launch intent")
            }

        } catch (e: Exception) {
            Log.e(TAG, "All widget launch strategies failed", e)
            crashlytics.recordException(e)
            crashlytics.setCustomKey("widget_launch_failed", true)
            crashlytics.setCustomKey("widget_launch_strategy", "all_failed")
        }
    }
}

class ScannerWidget : GlanceAppWidget() {

    companion object {
        val PENDING_COUNT_KEY = intPreferencesKey("pending_upload_count")
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                ScannerWidgetContent()
            }
        }
    }

    @Composable
    private fun ScannerWidgetContent() {
        val pendingCount = currentState(key = PENDING_COUNT_KEY) ?: 0

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.primaryContainer)
                .clickable(actionRunCallback<LaunchMainActivityCallback>()),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    provider = ImageProvider(R.drawable.ic_document_scanner),
                    contentDescription = "Scanner",
                    modifier = GlanceModifier.size(48.dp)
                )

                Spacer(modifier = GlanceModifier.height(8.dp))

                Text(
                    text = "Paperless",
                    style = TextStyle(
                        color = ColorProvider(R.color.widget_text_primary),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                )

                Text(
                    text = "Tippen zum Scannen",
                    style = TextStyle(
                        color = ColorProvider(R.color.widget_text_secondary),
                        fontSize = 12.sp
                    )
                )

                if (pendingCount > 0) {
                    Spacer(modifier = GlanceModifier.height(8.dp))

                    Row(
                        modifier = GlanceModifier
                            .fillMaxWidth()
                            .background(GlanceTheme.colors.secondaryContainer)
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Image(
                            provider = ImageProvider(R.drawable.ic_cloud_upload),
                            contentDescription = null,
                            modifier = GlanceModifier.size(16.dp)
                        )
                        Spacer(modifier = GlanceModifier.width(4.dp))
                        Text(
                            text = "$pendingCount ausstehend",
                            style = TextStyle(
                                color = ColorProvider(R.color.widget_text_secondary),
                                fontSize = 11.sp
                            )
                        )
                    }
                }
            }
        }
    }
}

suspend fun updateWidgetPendingCount(context: Context, glanceId: GlanceId, count: Int) {
    updateAppWidgetState(context, glanceId) { prefs ->
        prefs[ScannerWidget.PENDING_COUNT_KEY] = count
    }
    ScannerWidget().update(context, glanceId)
}
