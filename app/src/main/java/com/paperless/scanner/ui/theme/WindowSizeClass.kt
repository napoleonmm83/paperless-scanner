package com.paperless.scanner.ui.theme

import androidx.compose.material3.windowsizeclass.WindowSizeClass
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * CompositionLocal to provide WindowSizeClass throughout the app.
 * Used for responsive layouts on tablets and different orientations.
 */
val LocalWindowSizeClass = staticCompositionLocalOf<WindowSizeClass> {
    error("No WindowSizeClass provided")
}

/**
 * Helper extension to check if the device is in compact width mode (phones in portrait).
 */
val WindowSizeClass.isCompact: Boolean
    get() = widthSizeClass == WindowWidthSizeClass.Compact

/**
 * Helper extension to check if the device is in medium width mode (tablets in portrait, phones in landscape).
 */
val WindowSizeClass.isMedium: Boolean
    get() = widthSizeClass == WindowWidthSizeClass.Medium

/**
 * Helper extension to check if the device is in expanded width mode (tablets in landscape).
 */
val WindowSizeClass.isExpanded: Boolean
    get() = widthSizeClass == WindowWidthSizeClass.Expanded
