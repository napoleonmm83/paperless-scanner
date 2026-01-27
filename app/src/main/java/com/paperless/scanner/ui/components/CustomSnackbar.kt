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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

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
 * - Smart icon selection based on message content
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
    val message = data.visuals.message

    // Smart icon selection based on message content
    val icon = when {
        // Trash Operations
        message.contains("wiederhergestellt", ignoreCase = true) ||
        message.contains("restored", ignoreCase = true) -> Icons.Default.RestoreFromTrash

        message.contains("endgültig gelöscht", ignoreCase = true) ||
        message.contains("permanently deleted", ignoreCase = true) -> Icons.Default.DeleteForever

        message.contains("wird gelöscht", ignoreCase = true) ||
        message.contains("deleting", ignoreCase = true) -> Icons.Default.Delete

        // Success
        message.contains("erfolgreich", ignoreCase = true) ||
        message.contains("success", ignoreCase = true) ||
        message.contains("erstellt", ignoreCase = true) ||
        message.contains("created", ignoreCase = true) -> Icons.Default.CheckCircle

        // Error
        message.contains("fehler", ignoreCase = true) ||
        message.contains("error", ignoreCase = true) ||
        message.contains("fehlgeschlagen", ignoreCase = true) ||
        message.contains("failed", ignoreCase = true) -> Icons.Default.ErrorOutline

        // Network
        message.contains("internet", ignoreCase = true) ||
        message.contains("offline", ignoreCase = true) ||
        message.contains("verbindung", ignoreCase = true) ||
        message.contains("connection", ignoreCase = true) -> Icons.Default.WifiOff

        // Upload
        message.contains("upload", ignoreCase = true) ||
        message.contains("hochgeladen", ignoreCase = true) ||
        message.contains("wird", ignoreCase = true) -> Icons.Default.CloudUpload

        else -> Icons.Default.Info
    }

    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        shape = RoundedCornerShape(20.dp), // Dark Tech Precision Pro: 20dp radius
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface // Adapts to theme
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline), // Adapts to theme
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
                tint = MaterialTheme.colorScheme.primary, // Adapts to theme
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary, // Adapts to theme
                modifier = Modifier.weight(1f)
            )

            // Action Button (e.g., "Undo")
            data.visuals.actionLabel?.let { actionLabel ->
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    onClick = { data.performAction() },
                    modifier = Modifier.padding(end = 0.dp)
                ) {
                    Text(
                        text = actionLabel.uppercase(),
                        style = MaterialTheme.typography.labelMedium.copy(
                            fontWeight = FontWeight.Bold
                        ),
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
