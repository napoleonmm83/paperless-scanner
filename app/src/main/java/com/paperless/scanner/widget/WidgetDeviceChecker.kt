package com.paperless.scanner.widget

import android.os.Build

/**
 * Detects devices known to have issues with Glance's InvisibleActionTrampolineActivity.
 *
 * Known problematic configurations:
 * - OnePlus devices with Android 11 (OxygenOS quirks)
 * - Some Samsung devices with One UI
 * - Certain Xiaomi/MIUI devices
 * - Custom ROMs with modified activity stack handling
 *
 * When a problematic device is detected, we use the legacy RemoteViews-based widget
 * instead of Glance to avoid the "List adapter activity trampoline invoked without
 * specifying target intent" crash.
 */
object WidgetDeviceChecker {

    /**
     * Check if the current device is known to have Glance trampoline issues.
     */
    fun shouldUseLegacyWidget(): Boolean {
        return isProblematicOnePlus() ||
                isProblematicSamsung() ||
                isProblematicXiaomi() ||
                isProblematicOppo() ||
                isProblematicRealme()
    }

    /**
     * OnePlus devices with Android 11 (API 30) have issues with
     * InvisibleActionTrampolineActivity due to OxygenOS modifications.
     */
    private fun isProblematicOnePlus(): Boolean {
        val isOnePlus = Build.MANUFACTURER.equals("OnePlus", ignoreCase = true)
        val isAndroid11 = Build.VERSION.SDK_INT == Build.VERSION_CODES.R
        return isOnePlus && isAndroid11
    }

    /**
     * Some Samsung devices with One UI have activity trampoline issues,
     * particularly on Android 11 and 12.
     */
    private fun isProblematicSamsung(): Boolean {
        val isSamsung = Build.MANUFACTURER.equals("Samsung", ignoreCase = true)
        val isProblematicVersion = Build.VERSION.SDK_INT in Build.VERSION_CODES.R..Build.VERSION_CODES.S_V2
        // Only flag Samsung if there have been reported crashes (can be narrowed down later)
        return isSamsung && isProblematicVersion && hasSamsungOneUI()
    }

    private fun hasSamsungOneUI(): Boolean {
        return try {
            // Check for One UI by looking at system properties
            val oneUiVersion = Build.VERSION.INCREMENTAL
            oneUiVersion.contains("ONE", ignoreCase = true) ||
                    Build.DISPLAY.contains("ONE", ignoreCase = true)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Xiaomi/MIUI devices sometimes have aggressive activity management
     * that interferes with trampoline activities.
     */
    private fun isProblematicXiaomi(): Boolean {
        val isXiaomi = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
                Build.BRAND.equals("Xiaomi", ignoreCase = true) ||
                Build.BRAND.equals("Redmi", ignoreCase = true) ||
                Build.BRAND.equals("POCO", ignoreCase = true)
        val isProblematicVersion = Build.VERSION.SDK_INT <= Build.VERSION_CODES.S
        return isXiaomi && isProblematicVersion
    }

    /**
     * Oppo/ColorOS devices have similar issues to OnePlus (same parent company).
     */
    private fun isProblematicOppo(): Boolean {
        val isOppo = Build.MANUFACTURER.equals("OPPO", ignoreCase = true)
        val isProblematicVersion = Build.VERSION.SDK_INT == Build.VERSION_CODES.R
        return isOppo && isProblematicVersion
    }

    /**
     * Realme devices (sub-brand of Oppo) inherit similar issues.
     */
    private fun isProblematicRealme(): Boolean {
        val isRealme = Build.MANUFACTURER.equals("realme", ignoreCase = true) ||
                Build.BRAND.equals("realme", ignoreCase = true)
        val isProblematicVersion = Build.VERSION.SDK_INT == Build.VERSION_CODES.R
        return isRealme && isProblematicVersion
    }

    /**
     * Get device info string for logging/debugging.
     */
    fun getDeviceInfo(): String {
        return "Manufacturer: ${Build.MANUFACTURER}, " +
                "Brand: ${Build.BRAND}, " +
                "Model: ${Build.MODEL}, " +
                "Android: ${Build.VERSION.SDK_INT}, " +
                "Display: ${Build.DISPLAY}"
    }
}
