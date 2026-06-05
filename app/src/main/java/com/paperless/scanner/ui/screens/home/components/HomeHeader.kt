package com.paperless.scanner.ui.screens.home.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.paperless.scanner.R
import kotlinx.coroutines.delay

/**
 * Dashboard header: "Welcome back / Your archive" title plus the last-synced
 * indicator in the top-right corner.
 */
@Composable
fun HomeHeaderSection(
    lastSyncedAt: Long?,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 24.dp, bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column {
                Text(
                    text = stringResource(R.string.home_welcome_back),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.home_your_archive).uppercase(),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.ExtraBold
                )
            }

            // Last synced indicator in top right
            LastSyncedIndicator(
                lastSyncedAt = lastSyncedAt,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}

@Composable
private fun LastSyncedIndicator(
    lastSyncedAt: Long?,
    modifier: Modifier = Modifier
) {
    if (lastSyncedAt == null) return

    // Tick the relative time live: re-read the clock once a minute so the
    // "x min ago" label advances without waiting for an unrelated recomposition.
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(lastSyncedAt) {
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000L)
        }
    }

    // Calculate relative time
    val diffMinutes = ((now - lastSyncedAt) / 60_000).toInt()
    val diffHours = diffMinutes / 60

    val timeText = when {
        diffMinutes < 1 -> stringResource(R.string.home_last_synced_just_now)
        diffMinutes < 60 -> stringResource(R.string.home_last_synced_minutes_ago, diffMinutes)
        diffHours < 24 -> stringResource(R.string.home_last_synced_hours_ago, diffHours)
        else -> stringResource(R.string.home_last_synced_long_ago)
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            imageVector = Icons.Filled.Refresh,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
        Text(
            text = stringResource(R.string.home_last_synced, timeText),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )
    }
}

@Preview
@Composable
private fun HomeHeaderSectionPreview() {
    MaterialTheme {
        HomeHeaderSection(lastSyncedAt = System.currentTimeMillis() - 120_000)
    }
}
