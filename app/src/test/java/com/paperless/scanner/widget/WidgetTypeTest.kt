package com.paperless.scanner.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for WidgetType enum and WidgetConfig data class.
 *
 * Tests verify:
 * - WidgetType enum contains all expected values
 * - WidgetConfig defaults are correct
 * - WidgetConfig data class equality and copy semantics
 */
class WidgetTypeTest {

    // ==================== WidgetType enum tests ====================

    @Test
    fun `WidgetType has QUICK_SCAN value`() {
        val type = WidgetType.valueOf("QUICK_SCAN")
        assertEquals(WidgetType.QUICK_SCAN, type)
    }

    @Test
    fun `WidgetType has STATUS value`() {
        val type = WidgetType.valueOf("STATUS")
        assertEquals(WidgetType.STATUS, type)
    }

    @Test
    fun `WidgetType has COMBINED value`() {
        val type = WidgetType.valueOf("COMBINED")
        assertEquals(WidgetType.COMBINED, type)
    }

    @Test
    fun `WidgetType has exactly 3 values`() {
        val values = WidgetType.entries
        assertEquals(3, values.size)
    }

    @Test
    fun `WidgetType entries contains all expected values`() {
        val values = WidgetType.entries
        assertTrue(values.contains(WidgetType.QUICK_SCAN))
        assertTrue(values.contains(WidgetType.STATUS))
        assertTrue(values.contains(WidgetType.COMBINED))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `WidgetType valueOf throws for invalid name`() {
        WidgetType.valueOf("INVALID_TYPE")
    }

    @Test
    fun `WidgetType name returns correct string`() {
        assertEquals("QUICK_SCAN", WidgetType.QUICK_SCAN.name)
        assertEquals("STATUS", WidgetType.STATUS.name)
        assertEquals("COMBINED", WidgetType.COMBINED.name)
    }

    // ==================== WidgetConfig defaults tests ====================

    @Test
    fun `WidgetConfig default type is QUICK_SCAN`() {
        val config = WidgetConfig()
        assertEquals(WidgetType.QUICK_SCAN, config.type)
    }

    @Test
    fun `WidgetConfig default showPendingCount is true`() {
        val config = WidgetConfig()
        assertTrue(config.showPendingCount)
    }

    @Test
    fun `WidgetConfig default showServerStatus is false`() {
        val config = WidgetConfig()
        assertEquals(false, config.showServerStatus)
    }

    // ==================== WidgetConfig equality tests ====================

    @Test
    fun `WidgetConfig equality with same values`() {
        val config1 = WidgetConfig(
            type = WidgetType.STATUS,
            showPendingCount = false,
            showServerStatus = true
        )
        val config2 = WidgetConfig(
            type = WidgetType.STATUS,
            showPendingCount = false,
            showServerStatus = true
        )
        assertEquals(config1, config2)
    }

    @Test
    fun `WidgetConfig inequality with different type`() {
        val config1 = WidgetConfig(type = WidgetType.QUICK_SCAN)
        val config2 = WidgetConfig(type = WidgetType.STATUS)
        assertNotEquals(config1, config2)
    }

    @Test
    fun `WidgetConfig inequality with different showPendingCount`() {
        val config1 = WidgetConfig(showPendingCount = true)
        val config2 = WidgetConfig(showPendingCount = false)
        assertNotEquals(config1, config2)
    }

    @Test
    fun `WidgetConfig inequality with different showServerStatus`() {
        val config1 = WidgetConfig(showServerStatus = true)
        val config2 = WidgetConfig(showServerStatus = false)
        assertNotEquals(config1, config2)
    }

    @Test
    fun `WidgetConfig copy with modified type`() {
        val original = WidgetConfig(type = WidgetType.QUICK_SCAN)
        val copied = original.copy(type = WidgetType.COMBINED)

        assertEquals(WidgetType.COMBINED, copied.type)
        assertEquals(original.showPendingCount, copied.showPendingCount)
        assertEquals(original.showServerStatus, copied.showServerStatus)
    }

    @Test
    fun `WidgetConfig copy preserves unchanged fields`() {
        val original = WidgetConfig(
            type = WidgetType.STATUS,
            showPendingCount = false,
            showServerStatus = true
        )
        val copied = original.copy(type = WidgetType.COMBINED)

        assertEquals(WidgetType.COMBINED, copied.type)
        assertEquals(false, copied.showPendingCount)
        assertEquals(true, copied.showServerStatus)
    }

    @Test
    fun `WidgetConfig hashCode is consistent for equal objects`() {
        val config1 = WidgetConfig(
            type = WidgetType.COMBINED,
            showPendingCount = true,
            showServerStatus = true
        )
        val config2 = WidgetConfig(
            type = WidgetType.COMBINED,
            showPendingCount = true,
            showServerStatus = true
        )
        assertEquals(config1.hashCode(), config2.hashCode())
    }

    @Test
    fun `WidgetConfig with all WidgetType values`() {
        // Verify WidgetConfig can be created with every WidgetType
        for (type in WidgetType.entries) {
            val config = WidgetConfig(type = type)
            assertEquals(type, config.type)
        }
    }
}
