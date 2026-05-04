package com.paperless.scanner.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * Dark Tech Precision Pro shape tokens.
 *
 * - `small`  (8dp)  → chips, badges, tag pills
 * - `medium` (20dp) → cards, surfaces, inline banners (style-guide default)
 * - `large`  (28dp) → bottom sheets, dialogs
 */
val PaperlessShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(32.dp),
)
