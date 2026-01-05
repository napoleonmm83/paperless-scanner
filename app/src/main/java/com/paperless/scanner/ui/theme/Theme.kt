package com.paperless.scanner.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Dark Tech Precision Color Scheme
 *
 * This is a DARK ONLY theme with:
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
 * Main theme composable for Paperless Scanner
 *
 * Always uses dark theme with the Dark Tech Precision design system
 */
@Composable
fun PaperlessScannerTheme(
    content: @Composable () -> Unit
) {
    // Configure status bar icons to be LIGHT (visible on dark background)
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            val insetsController = WindowCompat.getInsetsController(window, view)
            // Use LIGHT status bar icons (for dark background)
            insetsController.isAppearanceLightStatusBars = false
            // Use LIGHT navigation bar icons (for dark background)
            insetsController.isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = DarkTechColorScheme,
        typography = Typography,
        content = content
    )
}
