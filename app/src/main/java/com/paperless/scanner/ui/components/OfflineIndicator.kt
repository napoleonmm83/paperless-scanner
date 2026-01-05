package com.paperless.scanner.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Offline-Modus Indikator Banner
 *
 * Zeigt den aktuellen Offline-Status und Anzahl ausstehender Änderungen an.
 *
 * States:
 * - Offline (Rot): CloudOff Icon, "Offline-Modus"
 * - Syncing (Blau): CloudQueue Icon, "Synchronisierung läuft... (X ausstehend)"
 * - Online: Nicht sichtbar
 */
@Composable
fun OfflineIndicator(
    isOnline: Boolean,
    pendingChanges: Int,
    modifier: Modifier = Modifier,
    onClickShowDetails: () -> Unit = {}
) {
    AnimatedVisibility(
        visible = !isOnline || pendingChanges > 0,
        enter = expandVertically(),
        exit = shrinkVertically(),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    if (!isOnline) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.primary
                    }
                )
                .clickable(onClick = onClickShowDetails)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = if (!isOnline) {
                        Icons.Default.CloudOff
                    } else {
                        Icons.Default.CloudQueue
                    },
                    contentDescription = if (!isOnline) {
                        "Offline"
                    } else {
                        "Synchronisierung läuft"
                    },
                    tint = if (!isOnline) {
                        MaterialTheme.colorScheme.onError
                    } else {
                        MaterialTheme.colorScheme.onPrimary
                    },
                    modifier = Modifier.padding(end = 8.dp)
                )

                Text(
                    text = if (!isOnline) {
                        "Offline-Modus - Tippen für Details"
                    } else if (pendingChanges > 0) {
                        "Synchronisierung läuft... ($pendingChanges ausstehend) - Tippen für Details"
                    } else {
                        ""
                    },
                    color = if (!isOnline) {
                        MaterialTheme.colorScheme.onError
                    } else {
                        MaterialTheme.colorScheme.onPrimary
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}
