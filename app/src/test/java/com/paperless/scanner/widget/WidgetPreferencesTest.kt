package com.paperless.scanner.widget

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Unit tests for WidgetPreferences SharedPreferences storage.
 *
 * Tests verify:
 * - Default config is returned when no config is saved
 * - setWidgetConfig + getWidgetConfig roundtrip for each WidgetType
 * - removeWidgetConfig clears data and returns defaults
 * - Multiple widget IDs are stored independently
 *
 * Uses Robolectric for Android Context and SharedPreferences access.
 */
@RunWith(RobolectricTestRunner::class)
class WidgetPreferencesTest {

    private lateinit var widgetPreferences: WidgetPreferences

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        // Clear any leftover data from previous tests
        context.getSharedPreferences(WidgetPreferences.PREFS_NAME, 0).edit().clear().commit()
        widgetPreferences = WidgetPreferences(context)
    }

    // ==================== Default config tests ====================

    @Test
    fun `getWidgetConfig returns default when no config saved`() {
        val config = widgetPreferences.getWidgetConfig(99999)

        assertEquals(WidgetType.QUICK_SCAN, config.type)
    }

    @Test
    fun `getWidgetConfig returns default WidgetConfig values`() {
        val config = widgetPreferences.getWidgetConfig(88888)
        val defaultConfig = WidgetConfig()

        assertEquals(defaultConfig.type, config.type)
    }

    // ==================== Roundtrip tests for each WidgetType ====================

    @Test
    fun `setWidgetConfig and getWidgetConfig roundtrip for QUICK_SCAN`() {
        val widgetId = 1
        val config = WidgetConfig(type = WidgetType.QUICK_SCAN)

        widgetPreferences.setWidgetConfig(widgetId, config)
        val retrieved = widgetPreferences.getWidgetConfig(widgetId)

        assertEquals(config.type, retrieved.type)
    }

    @Test
    fun `setWidgetConfig and getWidgetConfig roundtrip for STATUS`() {
        val widgetId = 2
        val config = WidgetConfig(type = WidgetType.STATUS)

        widgetPreferences.setWidgetConfig(widgetId, config)
        val retrieved = widgetPreferences.getWidgetConfig(widgetId)

        assertEquals(config.type, retrieved.type)
    }

    @Test
    fun `setWidgetConfig and getWidgetConfig roundtrip for COMBINED`() {
        val widgetId = 3
        val config = WidgetConfig(type = WidgetType.COMBINED)

        widgetPreferences.setWidgetConfig(widgetId, config)
        val retrieved = widgetPreferences.getWidgetConfig(widgetId)

        assertEquals(config.type, retrieved.type)
    }

    @Test
    fun `setWidgetConfig overwrites previous config`() {
        val widgetId = 4

        // Set initial config
        val initialConfig = WidgetConfig(type = WidgetType.QUICK_SCAN)
        widgetPreferences.setWidgetConfig(widgetId, initialConfig)

        // Overwrite with new config
        val updatedConfig = WidgetConfig(type = WidgetType.STATUS)
        widgetPreferences.setWidgetConfig(widgetId, updatedConfig)

        // Verify the updated config is returned
        val retrieved = widgetPreferences.getWidgetConfig(widgetId)
        assertEquals(updatedConfig.type, retrieved.type)
    }

    // ==================== removeWidgetConfig tests ====================

    @Test
    fun `removeWidgetConfig clears data and returns defaults`() {
        val widgetId = 5

        // Save a config
        val config = WidgetConfig(type = WidgetType.COMBINED)
        widgetPreferences.setWidgetConfig(widgetId, config)

        // Verify it was saved
        val savedConfig = widgetPreferences.getWidgetConfig(widgetId)
        assertEquals(WidgetType.COMBINED, savedConfig.type)

        // Remove the config
        widgetPreferences.removeWidgetConfig(widgetId)

        // Verify defaults are returned
        val afterRemoval = widgetPreferences.getWidgetConfig(widgetId)
        assertEquals(WidgetType.QUICK_SCAN, afterRemoval.type)
    }

    @Test
    fun `removeWidgetConfig is safe to call when no config exists`() {
        // Should not throw when removing a non-existent config
        widgetPreferences.removeWidgetConfig(77777)

        // Should still return defaults
        val config = widgetPreferences.getWidgetConfig(77777)
        assertEquals(WidgetType.QUICK_SCAN, config.type)
    }

    // ==================== Multiple widget independence tests ====================

    @Test
    fun `multiple widget IDs are stored independently`() {
        val widgetId1 = 10
        val widgetId2 = 20

        val config1 = WidgetConfig(type = WidgetType.QUICK_SCAN)
        val config2 = WidgetConfig(type = WidgetType.STATUS)

        widgetPreferences.setWidgetConfig(widgetId1, config1)
        widgetPreferences.setWidgetConfig(widgetId2, config2)

        val retrieved1 = widgetPreferences.getWidgetConfig(widgetId1)
        val retrieved2 = widgetPreferences.getWidgetConfig(widgetId2)

        // Verify widget 1 config
        assertEquals(WidgetType.QUICK_SCAN, retrieved1.type)

        // Verify widget 2 config
        assertEquals(WidgetType.STATUS, retrieved2.type)
    }

    @Test
    fun `removing one widget config does not affect another`() {
        val widgetId1 = 30
        val widgetId2 = 40

        val config1 = WidgetConfig(type = WidgetType.COMBINED)
        val config2 = WidgetConfig(type = WidgetType.STATUS)

        widgetPreferences.setWidgetConfig(widgetId1, config1)
        widgetPreferences.setWidgetConfig(widgetId2, config2)

        // Remove only widget 1
        widgetPreferences.removeWidgetConfig(widgetId1)

        // Widget 1 should return defaults
        val retrieved1 = widgetPreferences.getWidgetConfig(widgetId1)
        assertEquals(WidgetType.QUICK_SCAN, retrieved1.type)

        // Widget 2 should still have its config
        val retrieved2 = widgetPreferences.getWidgetConfig(widgetId2)
        assertEquals(WidgetType.STATUS, retrieved2.type)
    }

    @Test
    fun `updating one widget config does not affect another`() {
        val widgetId1 = 50
        val widgetId2 = 60

        // Set both configs
        widgetPreferences.setWidgetConfig(
            widgetId1,
            WidgetConfig(type = WidgetType.QUICK_SCAN)
        )
        widgetPreferences.setWidgetConfig(
            widgetId2,
            WidgetConfig(type = WidgetType.STATUS)
        )

        // Update only widget 1
        widgetPreferences.setWidgetConfig(
            widgetId1,
            WidgetConfig(type = WidgetType.COMBINED)
        )

        // Widget 1 should be updated
        assertEquals(WidgetType.COMBINED, widgetPreferences.getWidgetConfig(widgetId1).type)

        // Widget 2 should remain unchanged
        assertEquals(WidgetType.STATUS, widgetPreferences.getWidgetConfig(widgetId2).type)
    }
}
