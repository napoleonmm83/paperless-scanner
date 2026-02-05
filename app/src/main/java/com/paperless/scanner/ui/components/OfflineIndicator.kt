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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.paperless.scanner.R

/**
 * Offline Mode Indicator Banner
 *
 * Shows the current offline status and number of pending changes.
 *
 * States:
 * - Offline (Red): CloudOff Icon, "Offline mode"
 * - Syncing (Blue): CloudQueue Icon, "Syncing... (X pending)"
 * - Online: Not visible
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
                        stringResource(R.string.cd_offline)
                    } else {
                        stringResource(R.string.syncing)
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
                        stringResource(R.string.offline_mode_tap_details)
                    } else if (pendingChanges > 0) {
                        stringResource(R.string.syncing_pending_tap_details, pendingChanges)
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
