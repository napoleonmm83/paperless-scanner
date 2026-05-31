package com.paperless.scanner.widget

import android.os.Build
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/**
 * Unit tests for [WidgetDeviceChecker].
 *
 * Focus (issue #112): the OnePlus rule must flag the legacy RemoteViews fallback across
 * the full Android 11–13 (API 30–33) range where the OxygenOS InvisibleActionTrampoline
 * quirk occurs, not only Android 11.
 *
 * `Build.MANUFACTURER` and `Build.VERSION.SDK_INT` are driven directly via
 * [ReflectionHelpers] (rather than per-test `@Config(sdk = …)`) so the version boundary
 * can be exercised without requiring a Robolectric android-all jar for every SDK level.
 * Originals are restored after each test to avoid cross-test leakage.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class WidgetDeviceCheckerTest {

    private var originalManufacturer: String? = null
    private var originalSdkInt: Int = 0

    @Before
    fun saveBuildFields() {
        originalManufacturer = Build.MANUFACTURER
        originalSdkInt = Build.VERSION.SDK_INT
    }

    @After
    fun restoreBuildFields() {
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", originalManufacturer)
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", originalSdkInt)
    }

    private fun setDevice(manufacturer: String, sdkInt: Int) {
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", manufacturer)
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", sdkInt)
    }

    @Test
    fun `OnePlus on Android 11 (R) uses the legacy widget`() {
        setDevice("OnePlus", Build.VERSION_CODES.R)
        assertTrue(WidgetDeviceChecker.shouldUseLegacyWidget())
    }

    @Test
    fun `OnePlus on Android 12 (S) uses the legacy widget`() {
        // Previously NOT flagged (only Android 11 matched) — this is the broadened range.
        setDevice("OnePlus", Build.VERSION_CODES.S)
        assertTrue(WidgetDeviceChecker.shouldUseLegacyWidget())
    }

    @Test
    fun `OnePlus on Android 13 (TIRAMISU) uses the legacy widget`() {
        setDevice("OnePlus", Build.VERSION_CODES.TIRAMISU)
        assertTrue(WidgetDeviceChecker.shouldUseLegacyWidget())
    }

    @Test
    fun `OnePlus on Android 14 does not use the legacy widget`() {
        // Above the affected range — must use the modern Glance widget.
        setDevice("OnePlus", Build.VERSION_CODES.TIRAMISU + 1)
        assertFalse(WidgetDeviceChecker.shouldUseLegacyWidget())
    }

    @Test
    fun `non-OnePlus device on Android 13 does not use the legacy widget`() {
        // Guards against a false positive: a Google/Pixel device on the same SDK
        // must not be flagged by the OnePlus rule.
        setDevice("Google", Build.VERSION_CODES.TIRAMISU)
        assertFalse(WidgetDeviceChecker.shouldUseLegacyWidget())
    }
}
