package com.paperless.scanner.ui.theme

import androidx.compose.ui.graphics.Color

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

// Outline/Border Colors
val DarkTechOutline = Color(0xFF27272A)
val DarkTechOutlineVariant = Color(0xFF3F3F46)

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
// Legacy Colors (kept for backwards compatibility)
// Will be removed during screen migration
// ============================================

@Deprecated("Use MaterialTheme.colorScheme.primary instead", ReplaceWith("MaterialTheme.colorScheme.primary"))
val BrandTeal = Color(0xFF31A2AC)

@Deprecated("Use MaterialTheme.colorScheme colors instead", ReplaceWith("MaterialTheme.colorScheme.surface"))
val PastelCyan = Color(0xFFB8E8EB)

@Deprecated("Use MaterialTheme.colorScheme colors instead", ReplaceWith("MaterialTheme.colorScheme.surface"))
val PastelGreen = Color(0xFFB8E8D0)

@Deprecated("Use MaterialTheme.colorScheme colors instead", ReplaceWith("MaterialTheme.colorScheme.surface"))
val PastelBlue = Color(0xFFB8D8EB)

@Deprecated("Use MaterialTheme.colorScheme colors instead", ReplaceWith("MaterialTheme.colorScheme.surface"))
val PastelPurple = Color(0xFFD8D0EB)

@Deprecated("Use MaterialTheme.colorScheme colors instead", ReplaceWith("MaterialTheme.colorScheme.surface"))
val PastelYellow = Color(0xFFF5ECD0)

@Deprecated("Use MaterialTheme.colorScheme colors instead", ReplaceWith("MaterialTheme.colorScheme.surface"))
val PastelOrange = Color(0xFFF5DCC0)

@Deprecated("Use MaterialTheme.colorScheme colors instead", ReplaceWith("MaterialTheme.colorScheme.surface"))
val PastelPink = Color(0xFFF5D0E0)
