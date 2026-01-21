@file:SuppressLint("RestrictedApi")

package com.paperless.scanner.widget

import android.annotation.SuppressLint
import android.app.PendingIntent
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
 */
class LaunchMainActivityCallback : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters
    ) {
        val crashlytics = FirebaseCrashlytics.getInstance()

        // Log widget click for monitoring
        crashlytics.log("Widget clicked - launching MainActivity")
        crashlytics.setCustomKey("widget_launch_method", "ActionCallback")
        crashlytics.setCustomKey("device_manufacturer", Build.MANUFACTURER)
        crashlytics.setCustomKey("device_model", Build.MODEL)
        crashlytics.setCustomKey("android_version", Build.VERSION.SDK_INT)

        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            // Create PendingIntent with FLAG_IMMUTABLE (required for Android 11+)
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            Log.d("ScannerWidget", "Launching MainActivity via PendingIntent")
            pendingIntent.send()

            crashlytics.log("Widget launch successful via PendingIntent")

        } catch (e: Exception) {
            // Fallback: Direct activity launch if PendingIntent fails
            Log.w("ScannerWidget", "PendingIntent failed, using direct startActivity", e)
            crashlytics.recordException(e)
            crashlytics.setCustomKey("widget_launch_fallback", true)

            try {
                val intent = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                }
                context.startActivity(intent)

                crashlytics.log("Widget launch successful via fallback startActivity")

            } catch (fallbackException: Exception) {
                // Both methods failed - log for debugging
                Log.e("ScannerWidget", "Widget launch completely failed", fallbackException)
                crashlytics.recordException(fallbackException)
                crashlytics.setCustomKey("widget_launch_failed", true)
            }
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
