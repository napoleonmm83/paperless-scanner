package com.paperless.scanner.ui.screens.home.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.paperless.scanner.R

/**
 * CompactStatsRow - Three compact stat cards in a row.
 * Replaces the old 2-row layout with a cleaner single row.
 *
 * Dark Tech Precision Pro Style Guide:
 * - Surface background (#141414)
 * - 1dp border with outline (#27272A)
 * - No elevation
 * - 20dp corner radius, 12dp padding
 */
@Composable
fun CompactStatsRow(
    syncActiveCount: Int,
    syncFailedCount: Int,
    untaggedCount: Int,
    trashCount: Int,
    trashExpiresInDays: Int?,
    onSyncClick: () -> Unit,
    onTagsClick: (() -> Unit)?,
    onTrashClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Sync Center Card
        CompactStatCard(
            icon = Icons.Filled.Refresh,
            value = syncActiveCount,
            label = stringResource(R.string.home_stat_sync),
            badgeCount = syncFailedCount,
            showErrorBadge = syncFailedCount > 0,
            onClick = onSyncClick,
            modifier = Modifier.weight(1f)
        )

        // Tags Card
        CompactStatCard(
            icon = Icons.Filled.AutoAwesome,
            value = untaggedCount,
            label = stringResource(R.string.home_stat_smart_tagging),
            badgeCount = 0,
            showErrorBadge = false,
            onClick = onTagsClick,
            modifier = Modifier.weight(1f)
        )

        // Trash Card
        CompactStatCard(
            icon = Icons.Filled.Delete,
            value = trashCount,
            label = stringResource(R.string.trash_title),
            badgeCount = 0,
            showErrorBadge = false,
            subtitle = if (trashCount > 0 && trashExpiresInDays != null) {
                stringResource(R.string.trash_expires_in_days, trashExpiresInDays)
            } else null,
            onClick = onTrashClick,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * CompactStatCard - Individual compact stat card for the stats row.
 */
@Composable
private fun CompactStatCard(
    icon: ImageVector,
    value: Int,
    label: String,
    badgeCount: Int,
    showErrorBadge: Boolean,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    subtitle: String? = null
) {
    Card(
        onClick = onClick ?: {},
        enabled = onClick != null,
        modifier = modifier.border(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline,
            shape = RoundedCornerShape(20.dp)
        ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = value.toString(),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = label.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                // Optional subtitle (e.g., "🕐 27 days")
                if (subtitle != null) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.AccessTime,
                            contentDescription = null,
                            modifier = Modifier.size(10.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            // Error badge (red dot)
            if (showErrorBadge && badgeCount > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (badgeCount > 9) "9+" else badgeCount.toString(),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onError
                    )
                }
            }
        }
    }
}

@Preview
@Composable
private fun CompactStatsRowPreview() {
    MaterialTheme {
        CompactStatsRow(
            syncActiveCount = 2,
            syncFailedCount = 1,
            untaggedCount = 5,
            trashCount = 3,
            trashExpiresInDays = 27,
            onSyncClick = {},
            onTagsClick = {},
            onTrashClick = {}
        )
    }
}
