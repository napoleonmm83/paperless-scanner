package com.paperless.scanner.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paperless.scanner.R
import com.paperless.scanner.data.api.ServerOfflineReason
import com.paperless.scanner.data.health.ServerStatus

/**
 * Server Offline Banner - Dark Tech Precision Pro Style
 *
 * Wiederverwendbare Banner-Komponente für Server-Offline-Warnung.
 * Zeigt spezifische Gründe für Server-Unerreichbarkeit an.
 *
 * Design:
 * - Card mit errorContainer background
 * - Border: 1dp, outline color
 * - Corner Radius: 20dp
 * - NO elevation (0.dp)
 * - Icon: CloudOff
 * - Zwei IconButtons: Refresh (Retry) + Settings
 *
 * @param reason Server offline reason from ServerStatus.Offline
 * @param onRetry Callback when user taps retry button
 * @param onSettings Callback when user taps settings button
 * @param modifier Optional modifier for layout customization
 */
@Composable
fun ServerOfflineBanner(
    reason: ServerStatus.Offline,
    onRetry: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Icon: CloudOff
            Icon(
                imageVector = Icons.Default.CloudOff,
                contentDescription = stringResource(R.string.server_offline_title),
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.error
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Content: Title + Message
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = stringResource(R.string.server_offline_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )

                Text(
                    text = when (reason.reason) {
                        ServerOfflineReason.NO_INTERNET ->
                            stringResource(R.string.server_offline_no_internet)
                        ServerOfflineReason.DNS_FAILURE ->
                            stringResource(R.string.server_offline_dns)
                        ServerOfflineReason.CONNECTION_REFUSED ->
                            stringResource(R.string.server_offline_connection_refused)
                        ServerOfflineReason.TIMEOUT ->
                            stringResource(R.string.server_offline_timeout)
                        ServerOfflineReason.SSL_ERROR ->
                            stringResource(R.string.server_offline_ssl_error)
                        ServerOfflineReason.VPN_REQUIRED ->
                            stringResource(R.string.server_offline_vpn_required)
                        ServerOfflineReason.UNKNOWN ->
                            stringResource(R.string.server_offline_unknown)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }

            // Action Buttons: Retry + Settings
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                IconButton(onClick = onRetry) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = stringResource(R.string.retry),
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                }

                IconButton(onClick = onSettings) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = stringResource(R.string.check_server_settings),
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
        }
    }
}
