package com.paperless.scanner.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Dark Tech Precision Color Scheme
 *
 * This is the DARK theme with:
 * - Deep black backgrounds (#0A0A0A, #141414)
 * - Neon yellow/green accents (#E1FF8D)
 * - Zero elevation (all depth via borders)
 * - High contrast for readability
 */
private val DarkTechColorScheme = darkColorScheme(
    // Primary - Neon Yellow/Green
    primary = md_theme_dark_primary,
    onPrimary = md_theme_dark_onPrimary,
    primaryContainer = md_theme_dark_primaryContainer,
    onPrimaryContainer = md_theme_dark_onPrimaryContainer,

    // Secondary - Uses primary color
    secondary = md_theme_dark_secondary,
    onSecondary = md_theme_dark_onSecondary,
    secondaryContainer = md_theme_dark_secondaryContainer,
    onSecondaryContainer = md_theme_dark_onSecondaryContainer,

    // Tertiary - Accent Blue
    tertiary = md_theme_dark_tertiary,
    onTertiary = md_theme_dark_onTertiary,
    tertiaryContainer = md_theme_dark_tertiaryContainer,
    onTertiaryContainer = md_theme_dark_onTertiaryContainer,

    // Error - Red
    error = md_theme_dark_error,
    onError = md_theme_dark_onError,
    errorContainer = md_theme_dark_errorContainer,
    onErrorContainer = md_theme_dark_onErrorContainer,

    // Background - Deep Black
    background = md_theme_dark_background,
    onBackground = md_theme_dark_onBackground,

    // Surface - Dark Gray variations
    surface = md_theme_dark_surface,
    onSurface = md_theme_dark_onSurface,
    surfaceVariant = md_theme_dark_surfaceVariant,
    onSurfaceVariant = md_theme_dark_onSurfaceVariant,

    // Outline/Border - Dark Gray
    outline = md_theme_dark_outline,
    outlineVariant = md_theme_dark_outlineVariant,

    // Inverse colors
    inverseSurface = md_theme_dark_inverseSurface,
    inverseOnSurface = md_theme_dark_inverseOnSurface,
    inversePrimary = md_theme_dark_inversePrimary,

    // Surface tint and scrim
    surfaceTint = md_theme_dark_surfaceTint,
    scrim = md_theme_dark_scrim
)

/**
 * Light Tech Precision Color Scheme
 *
 * This is the LIGHT theme with:
 * - Neon yellow/green backgrounds (#E1FF8D)
 * - Deep black accents (#0A0A0A)
 * - Inverted colors from dark theme
 * - High contrast for readability
 */
private val LightTechColorScheme = lightColorScheme(
    // Primary - Deep Black
    primary = md_theme_light_primary,
    onPrimary = md_theme_light_onPrimary,
    primaryContainer = md_theme_light_primaryContainer,
    onPrimaryContainer = md_theme_light_onPrimaryContainer,

    // Secondary
    secondary = md_theme_light_secondary,
    onSecondary = md_theme_light_onSecondary,
    secondaryContainer = md_theme_light_secondaryContainer,
    onSecondaryContainer = md_theme_light_onSecondaryContainer,

    // Tertiary - Accent Blue
    tertiary = md_theme_light_tertiary,
    onTertiary = md_theme_light_onTertiary,
    tertiaryContainer = md_theme_light_tertiaryContainer,
    onTertiaryContainer = md_theme_light_onTertiaryContainer,

    // Error - Red
    error = md_theme_light_error,
    onError = md_theme_light_onError,
    errorContainer = md_theme_light_errorContainer,
    onErrorContainer = md_theme_light_onErrorContainer,

    // Background - Neon Yellow/Green
    background = md_theme_light_background,
    onBackground = md_theme_light_onBackground,

    // Surface - Slightly darker yellow/green
    surface = md_theme_light_surface,
    onSurface = md_theme_light_onSurface,
    surfaceVariant = md_theme_light_surfaceVariant,
    onSurfaceVariant = md_theme_light_onSurfaceVariant,

    // Outline/Border - Dark
    outline = md_theme_light_outline,
    outlineVariant = md_theme_light_outlineVariant,

    // Inverse colors
    inverseSurface = md_theme_light_inverseSurface,
    inverseOnSurface = md_theme_light_inverseOnSurface,
    inversePrimary = md_theme_light_inversePrimary,

    // Surface tint and scrim
    surfaceTint = md_theme_light_surfaceTint,
    scrim = md_theme_light_scrim
)

/**
 * Main theme composable for Paperless Scanner
 *
 * @param themeMode The theme mode to use (System, Light, Dark)
 * @param content The content to display
 */
@Composable
fun PaperlessScannerTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    // Determine if we should use dark theme based on theme mode
    val useDarkTheme = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = if (useDarkTheme) DarkTechColorScheme else LightTechColorScheme

    // Configure status bar and navigation bar to match theme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val insetsController = WindowCompat.getInsetsController(window, view)

            // Set status bar and navigation bar colors to match app theme
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()

            // Use LIGHT icons on dark background, DARK icons on light background
            insetsController.isAppearanceLightStatusBars = !useDarkTheme
            insetsController.isAppearanceLightNavigationBars = !useDarkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
