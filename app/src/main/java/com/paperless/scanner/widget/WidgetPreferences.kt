package com.paperless.scanner.widget

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class WidgetType {
    QUICK_SCAN,
    STATUS,
    COMBINED
}

data class WidgetConfig(
    val type: WidgetType = WidgetType.QUICK_SCAN
)

/**
 * Stores per-widget configuration using SharedPreferences.
 *
 * SharedPreferences chosen over DataStore because:
 * - Synchronous reads are reliable in ALL Android contexts (widget, receiver, activity)
 * - No coroutine context required - works in provideGlance, onUpdate, config activity
 * - Widget config is simple key-value data that doesn't need DataStore's complexity
 * - Avoids DataStore delegate caching issues across different Context instances
 */
@Singleton
class WidgetPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {

    companion object {
        private const val TAG = "WidgetPreferences"
        internal const val PREFS_NAME = "widget_config"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun widgetTypeKey(widgetId: Int) = "widget_type_$widgetId"

    fun getWidgetConfig(widgetId: Int): WidgetConfig {
        val typeString = prefs.getString(widgetTypeKey(widgetId), null)
        val type = try {
            typeString?.let { WidgetType.valueOf(it) } ?: WidgetType.QUICK_SCAN
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Invalid widget type '$typeString' for widget $widgetId, using default")
            WidgetType.QUICK_SCAN
        }

        Log.d(TAG, "getWidgetConfig: id=$widgetId, type=$type")
        return WidgetConfig(type)
    }

    /**
     * Saves widget config synchronously using commit() to ensure data is persisted
     * before the widget attempts to read it.
     */
    fun setWidgetConfig(widgetId: Int, config: WidgetConfig) {
        val success = prefs.edit()
            .putString(widgetTypeKey(widgetId), config.type.name)
            .commit() // commit() not apply() - ensures synchronous write before widget renders

        Log.d(TAG, "setWidgetConfig: id=$widgetId, type=${config.type}, success=$success")
    }

    fun removeWidgetConfig(widgetId: Int) {
        prefs.edit()
            .remove(widgetTypeKey(widgetId))
            .apply()

        Log.d(TAG, "removeWidgetConfig: id=$widgetId")
    }
}
