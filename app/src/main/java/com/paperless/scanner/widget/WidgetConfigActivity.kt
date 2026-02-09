package com.paperless.scanner.widget

import android.annotation.SuppressLint
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.glance.appwidget.AppWidgetId
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.paperless.scanner.R
import com.paperless.scanner.ui.theme.DarkTechBackground
import com.paperless.scanner.ui.theme.DarkTechOnSurface
import com.paperless.scanner.ui.theme.DarkTechOnSurfaceMuted
import com.paperless.scanner.ui.theme.DarkTechOutline
import com.paperless.scanner.ui.theme.DarkTechPrimary
import com.paperless.scanner.ui.theme.DarkTechSurface
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class WidgetConfigActivity : ComponentActivity() {

    @Inject
    lateinit var widgetPreferences: WidgetPreferences

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        // Set canceled result by default (user backs out)
        setResult(RESULT_CANCELED)

        // Get the widget ID from the intent
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID

        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        setContent {
            WidgetConfigScreen(
                onSave = { config -> saveConfigAndFinish(config) },
                onCancel = { finish() }
            )
        }
    }

    @SuppressLint("RestrictedApi")
    private fun saveConfigAndFinish(config: WidgetConfig) {
        Log.d(TAG, "Saving widget config: id=$appWidgetId, type=${config.type}")

        // 1. Save config synchronously (SharedPreferences with commit())
        widgetPreferences.setWidgetConfig(appWidgetId, config)

        // 2. Verify config was saved correctly
        val savedConfig = widgetPreferences.getWidgetConfig(appWidgetId)
        Log.d(TAG, "Verified saved config: id=$appWidgetId, type=${savedConfig.type}")

        if (savedConfig.type != config.type) {
            Log.e(TAG, "CONFIG MISMATCH! Saved=${savedConfig.type}, Expected=${config.type}")
        }

        // 3. Update widget
        if (!WidgetDeviceChecker.shouldUseLegacyWidget()) {
            // Glance: update Glance state to trigger reactive recomposition
            val glanceId = AppWidgetId(appWidgetId)
            lifecycleScope.launch {
                try {
                    Log.d(TAG, "Updating Glance state: id=$appWidgetId, type=${config.type}")
                    // Write widget type to Glance state → triggers recomposition
                    updateAppWidgetState(this@WidgetConfigActivity, glanceId) { prefs ->
                        prefs[ScannerWidget.WIDGET_TYPE_KEY] = config.type.name
                    }
                    ScannerWidget().update(this@WidgetConfigActivity, glanceId)
                    Log.d(TAG, "Glance widget updated successfully")
                } catch (e: Exception) {
                    Log.e(TAG, "Direct Glance update failed, sending broadcast fallback", e)
                    sendWidgetUpdateBroadcast()
                } finally {
                    finishWithResult()
                }
            }
        } else {
            // Legacy: broadcast triggers LegacyScannerWidget.onUpdate
            sendWidgetUpdateBroadcast()
            finishWithResult()
        }
    }

    private fun sendWidgetUpdateBroadcast() {
        val updateIntent = Intent(this, ScannerWidgetReceiver::class.java).apply {
            action = AppWidgetManager.ACTION_APPWIDGET_UPDATE
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, intArrayOf(appWidgetId))
        }
        sendBroadcast(updateIntent)
    }

    private fun finishWithResult() {
        val resultValue = Intent().apply {
            putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        }
        setResult(RESULT_OK, resultValue)
        finish()
    }

    companion object {
        private const val TAG = "WidgetConfigActivity"
    }
}

@Composable
private fun WidgetConfigScreen(
    onSave: (WidgetConfig) -> Unit,
    onCancel: () -> Unit
) {
    var selectedType by remember { mutableStateOf(WidgetType.QUICK_SCAN) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkTechBackground)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        // Title
        Text(
            text = stringResource(R.string.widget_config_title).uppercase(),
            color = DarkTechOnSurface,
            fontSize = 24.sp,
            fontWeight = FontWeight.ExtraBold,
            letterSpacing = 0.1.sp
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Widget Type Selection
        WidgetTypeCard(
            type = WidgetType.QUICK_SCAN,
            title = stringResource(R.string.widget_type_quick_scan),
            icon = Icons.Filled.CameraAlt,
            isSelected = selectedType == WidgetType.QUICK_SCAN,
            onClick = { selectedType = WidgetType.QUICK_SCAN }
        )

        Spacer(modifier = Modifier.height(12.dp))

        WidgetTypeCard(
            type = WidgetType.STATUS,
            title = stringResource(R.string.widget_type_status),
            icon = Icons.Filled.Info,
            isSelected = selectedType == WidgetType.STATUS,
            onClick = { selectedType = WidgetType.STATUS }
        )

        Spacer(modifier = Modifier.height(12.dp))

        WidgetTypeCard(
            type = WidgetType.COMBINED,
            title = stringResource(R.string.widget_type_combined),
            icon = Icons.Filled.Dashboard,
            isSelected = selectedType == WidgetType.COMBINED,
            onClick = { selectedType = WidgetType.COMBINED }
        )

        Spacer(modifier = Modifier.height(32.dp))

        // Save Button
        Button(
            onClick = {
                onSave(WidgetConfig(type = selectedType))
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = DarkTechPrimary,
                contentColor = DarkTechBackground
            )
        ) {
            Text(
                text = stringResource(R.string.widget_config_save).uppercase(),
                fontWeight = FontWeight.ExtraBold,
                fontSize = 16.sp,
                letterSpacing = 0.1.sp
            )
        }
    }
}

@Composable
private fun WidgetTypeCard(
    type: WidgetType,
    title: String,
    icon: ImageVector,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val borderColor = if (isSelected) DarkTechPrimary else DarkTechOutline

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = DarkTechSurface
        ),
        border = BorderStroke(1.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = isSelected,
                onClick = onClick,
                colors = RadioButtonDefaults.colors(
                    selectedColor = DarkTechPrimary,
                    unselectedColor = DarkTechOnSurfaceMuted
                )
            )

            Spacer(modifier = Modifier.width(12.dp))

            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isSelected) DarkTechPrimary else DarkTechOnSurfaceMuted,
                modifier = Modifier.size(24.dp)
            )

            Spacer(modifier = Modifier.width(12.dp))

            Text(
                text = title,
                color = if (isSelected) DarkTechOnSurface else DarkTechOnSurfaceMuted,
                fontSize = 16.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
            )
        }
    }
}

