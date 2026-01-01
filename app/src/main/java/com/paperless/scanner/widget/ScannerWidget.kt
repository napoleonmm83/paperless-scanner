package com.paperless.scanner.widget

import android.content.Context
import android.content.Intent
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
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
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
import com.paperless.scanner.MainActivity
import com.paperless.scanner.R

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
        val context = LocalContext.current
        val pendingCount = currentState(key = PENDING_COUNT_KEY) ?: 0

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        Box(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.primaryContainer)
                .clickable(actionStartActivity(intent)),
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
