package com.paperless.scanner.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Icon types for typed snackbar visuals.
 * Using this enum instead of string matching ensures correct icons regardless of language.
 */
enum class SnackbarIcon(val imageVector: ImageVector) {
    SUCCESS(Icons.Default.CheckCircle),
    ERROR(Icons.Default.ErrorOutline),
    NETWORK(Icons.Default.WifiOff),
    UPLOAD(Icons.Default.CloudUpload),
    DELETE(Icons.Default.Delete),
    DELETE_FOREVER(Icons.Default.DeleteForever),
    RESTORE(Icons.Default.RestoreFromTrash),
    INFO(Icons.Default.Info)
}

/**
 * Typed snackbar visuals that carry an explicit icon instead of relying on string matching.
 */
data class TypedSnackbarVisuals(
    override val message: String,
    val icon: SnackbarIcon = SnackbarIcon.INFO,
    override val actionLabel: String? = null,
    override val duration: SnackbarDuration = SnackbarDuration.Short,
    override val withDismissAction: Boolean = false
) : SnackbarVisuals

/**
 * Extension to show a snackbar with an explicit icon type.
 */
suspend fun SnackbarHostState.showTypedSnackbar(
    message: String,
    icon: SnackbarIcon = SnackbarIcon.INFO,
    actionLabel: String? = null,
    duration: SnackbarDuration = SnackbarDuration.Short,
    withDismissAction: Boolean = false
): SnackbarResult = showSnackbar(
    TypedSnackbarVisuals(
        message = message,
        icon = icon,
        actionLabel = actionLabel,
        duration = duration,
        withDismissAction = withDismissAction
    )
)

/**
 * Custom Snackbar with Dark Tech Precision Pro Design
 *
 * Automatically adapts to Light and Dark mode using MaterialTheme.colorScheme:
 *
 * DARK MODE:
 * - Background: #141414 (dark surface)
 * - Text/Icons: #E1FF8D (neon-yellow primary)
 * - Border: #27272A (dark outline)
 *
 * LIGHT MODE:
 * - Background: Neon-yellow surface
 * - Text/Icons: #0A0A0A (deep black primary)
 * - Border: Dark outline
 *
 * Features:
 * - 20dp corner radius (vs standard 4dp)
 * - No elevation (flat design)
 * - Typed icon selection via TypedSnackbarVisuals (i18n-safe)
 * - Positioned at top (doesn't cover navigation)
 *
 * Usage in Scaffold:
 * ```kotlin
 * Box(modifier = Modifier.fillMaxSize()) {
 *     Scaffold(...) { ... }
 *     CustomSnackbarHost(
 *         hostState = snackbarHostState,
 *         modifier = Modifier.align(Alignment.TopCenter)
 *     )
 * }
 * ```
 */
@Composable
fun CustomSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    SnackbarHost(
        hostState = hostState,
        modifier = modifier,
        snackbar = { data ->
            CustomSnackbar(data = data)
        }
    )
}

@Composable
private fun CustomSnackbar(
    data: SnackbarData,
    modifier: Modifier = Modifier
) {
    val icon = (data.visuals as? TypedSnackbarVisuals)?.icon?.imageVector
        ?: Icons.Default.Info

    // Dark Mode: Green/primary background with dark text (inverted)
    // Light Mode: Dark surface background with green/primary text
    val isDarkTheme = isSystemInDarkTheme()
    val backgroundColor = if (isDarkTheme) {
        MaterialTheme.colorScheme.primary // Neon-yellow/green in dark mode
    } else {
        MaterialTheme.colorScheme.surface // Dark surface in light mode
    }
    val contentColor = if (isDarkTheme) {
        MaterialTheme.colorScheme.onPrimary // Dark text on green background
    } else {
        MaterialTheme.colorScheme.primary // Green text on dark background
    }
    val borderColor = if (isDarkTheme) {
        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.3f) // Subtle border in dark mode
    } else {
        MaterialTheme.colorScheme.outline // Normal outline in light mode
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        shape = RoundedCornerShape(20.dp), // Dark Tech Precision Pro: 20dp radius
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        ),
        border = BorderStroke(1.dp, borderColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp) // No shadow
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Start
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = data.visuals.message,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                modifier = Modifier.weight(1f)
            )

            // Action Button (e.g., "Undo")
            data.visuals.actionLabel?.let { actionLabel ->
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    onClick = { data.performAction() }
                ) {
                    Text(
                        text = actionLabel.uppercase(),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = contentColor
                    )
                }
            }
        }
    }
}
