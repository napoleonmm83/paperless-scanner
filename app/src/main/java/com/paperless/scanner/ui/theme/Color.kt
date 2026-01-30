package com.paperless.scanner.ui.theme

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import com.paperless.scanner.R

// ============================================
// Theme Mode Enum
// ============================================
enum class ThemeMode(val key: String, @StringRes val displayNameRes: Int) {
    SYSTEM("system", R.string.theme_mode_system),
    LIGHT("light", R.string.theme_mode_light),
    DARK("dark", R.string.theme_mode_dark)
}

// ============================================
// Dark Tech Precision Color Palette
// ============================================

// Primary Brand Color - Neon Yellow/Green
val DarkTechPrimary = Color(0xFFE1FF8D)
val DarkTechOnPrimary = Color(0xFF000000)

// Background Colors - Deep Blacks
val DarkTechBackground = Color(0xFF0A0A0A)
val DarkTechSurface = Color(0xFF141414)
val DarkTechSurfaceVariant = Color(0xFF1F1F1F)

// Text Colors
val DarkTechOnBackground = Color(0xFFFFFFFF)
val DarkTechOnSurface = Color(0xFFFFFFFF)
val DarkTechOnSurfaceMuted = Color(0xFFA1A1AA)

// Outline/Border Colors - Improved contrast for visibility
val DarkTechOutline = Color(0xFF3F3F46)        // Lightened from #27272A for better visibility
val DarkTechOutlineVariant = Color(0xFF52525B) // Slightly lighter for variant borders

// Accent Color
val DarkTechAccentBlue = Color(0xFF2E3A59)

// Status Colors
val DarkTechSuccess = Color(0xFF10B981)
val DarkTechWarning = Color(0xFFF59E0B)
val DarkTechError = Color(0xFFEF4444)
val DarkTechInfo = Color(0xFF3B82F6)

// ============================================
// Material 3 Dark Theme Color Scheme
// ============================================

val md_theme_dark_primary = DarkTechPrimary
val md_theme_dark_onPrimary = DarkTechOnPrimary
val md_theme_dark_primaryContainer = Color(0xFF2A3310)  // Dark variant of primary
val md_theme_dark_onPrimaryContainer = DarkTechPrimary

val md_theme_dark_secondary = DarkTechPrimary
val md_theme_dark_onSecondary = DarkTechOnPrimary
val md_theme_dark_secondaryContainer = DarkTechSurfaceVariant
val md_theme_dark_onSecondaryContainer = DarkTechOnSurface

val md_theme_dark_tertiary = DarkTechAccentBlue
val md_theme_dark_onTertiary = DarkTechOnSurface
val md_theme_dark_tertiaryContainer = Color(0xFF1E293F)
val md_theme_dark_onTertiaryContainer = DarkTechAccentBlue

val md_theme_dark_error = DarkTechError
val md_theme_dark_onError = Color.White
val md_theme_dark_errorContainer = Color(0xFF4C1D1D)
val md_theme_dark_onErrorContainer = DarkTechError

val md_theme_dark_background = DarkTechBackground
val md_theme_dark_onBackground = DarkTechOnBackground

val md_theme_dark_surface = DarkTechSurface
val md_theme_dark_onSurface = DarkTechOnSurface
val md_theme_dark_surfaceVariant = DarkTechSurfaceVariant
val md_theme_dark_onSurfaceVariant = DarkTechOnSurfaceMuted

val md_theme_dark_outline = DarkTechOutline
val md_theme_dark_outlineVariant = DarkTechOutlineVariant

val md_theme_dark_inverseSurface = DarkTechOnSurface
val md_theme_dark_inverseOnSurface = DarkTechSurface
val md_theme_dark_inversePrimary = DarkTechOnPrimary

val md_theme_dark_surfaceTint = DarkTechPrimary
val md_theme_dark_scrim = Color(0xFF000000)

// ============================================
// Light Tech Precision Color Palette
// Primary becomes background, dark becomes foreground
// ============================================

// Primary Brand Color remains the same for accents
val LightTechPrimary = DarkTechBackground  // Deep black for accents
val LightTechOnPrimary = DarkTechPrimary   // Neon yellow text on primary

// Background Colors - Neon Yellow/Green tones with improved contrast
val LightTechBackground = DarkTechPrimary  // #E1FF8D - Main background
val LightTechSurface = Color(0xFFC7E56E)   // Darker surface for better distinction from background
val LightTechSurfaceVariant = Color(0xFFB8D85E) // Even darker for clear hierarchy

// Text Colors - Dark for readability
val LightTechOnBackground = Color(0xFF0A0A0A)
val LightTechOnSurface = Color(0xFF0A0A0A)
val LightTechOnSurfaceMuted = Color(0xFF3F3F46)

// Outline/Border Colors - Black for contrast on yellow background
val LightTechOutline = Color(0xFF1A1A1A)       // Dark black for clear borders
val LightTechOutlineVariant = Color(0xFF2A2A2A) // Slightly lighter black

// ============================================
// Material 3 Light Theme Color Scheme
// ============================================

val md_theme_light_primary = LightTechPrimary
val md_theme_light_onPrimary = LightTechOnPrimary
val md_theme_light_primaryContainer = Color(0xFF1F1F1F)
val md_theme_light_onPrimaryContainer = DarkTechPrimary

val md_theme_light_secondary = LightTechPrimary
val md_theme_light_onSecondary = LightTechOnPrimary
val md_theme_light_secondaryContainer = LightTechSurfaceVariant
val md_theme_light_onSecondaryContainer = LightTechOnSurface

val md_theme_light_tertiary = Color(0xFF2E3A59)
val md_theme_light_onTertiary = Color.White
val md_theme_light_tertiaryContainer = Color(0xFF4A5A7A)
val md_theme_light_onTertiaryContainer = Color.White

val md_theme_light_error = DarkTechError
val md_theme_light_onError = Color.White
val md_theme_light_errorContainer = Color(0xFFFFDAD6)
val md_theme_light_onErrorContainer = Color(0xFF410002)

val md_theme_light_background = LightTechBackground
val md_theme_light_onBackground = LightTechOnBackground

val md_theme_light_surface = LightTechSurface
val md_theme_light_onSurface = LightTechOnSurface
val md_theme_light_surfaceVariant = LightTechSurfaceVariant
val md_theme_light_onSurfaceVariant = LightTechOnSurfaceMuted

val md_theme_light_outline = LightTechOutline
val md_theme_light_outlineVariant = LightTechOutlineVariant

val md_theme_light_inverseSurface = DarkTechSurface
val md_theme_light_inverseOnSurface = DarkTechOnSurface
val md_theme_light_inversePrimary = DarkTechPrimary

val md_theme_light_surfaceTint = LightTechPrimary
val md_theme_light_scrim = Color(0xFF000000)

