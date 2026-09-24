package com.paperless.scanner.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.view.View
import android.widget.RemoteViews
import com.paperless.scanner.MainActivity
import com.paperless.scanner.R
import com.paperless.scanner.util.DeepLinkHandler
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SingleScanWidgetReceiverTest {

    @Test
    fun `single scan is registered as a separate widget provider`() {
        val context = RuntimeEnvironment.getApplication()
        val provider = context.packageManager.getReceiverInfo(
            ComponentName(context, SingleScanWidgetReceiver::class.java),
            PackageManager.GET_META_DATA
        )

        assertNotEquals(0, provider.metaData.getInt("android.appwidget.provider"))
    }

    @Test
    fun `widget tap launches camera scan directly`() {
        val context = RuntimeEnvironment.getApplication()
        val manager = mockk<AppWidgetManager>(relaxed = true)
        val views = slot<RemoteViews>()

        SingleScanWidgetReceiver().onUpdate(context, manager, intArrayOf(42))

        verify { manager.updateAppWidget(42, capture(views)) }
        val widgetView = views.captured.apply(context, null)
        widgetView.findViewById<View>(R.id.widget_single_scan_container).performClick()

        val intent = shadowOf(context).nextStartedActivity
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(DeepLinkHandler.URI_SCAN_CAMERA, intent.dataString)
    }
}
