package com.paperless.scanner.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import com.paperless.scanner.MainActivity
import com.paperless.scanner.R
import com.paperless.scanner.util.DeepLinkHandler

/** The standalone one-cell launcher for the camera scanner. */
class SingleScanWidgetReceiver : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(DeepLinkHandler.URI_SCAN_CAMERA)).apply {
                component = ComponentName(context, MainActivity::class.java)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                appWidgetId,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val views = RemoteViews(context.packageName, R.layout.widget_single_scan).apply {
                setOnClickPendingIntent(R.id.widget_single_scan_container, pendingIntent)
            }
            manager.updateAppWidget(appWidgetId, views)
        }
    }
}
