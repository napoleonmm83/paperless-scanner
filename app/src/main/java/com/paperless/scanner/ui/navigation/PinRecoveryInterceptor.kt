package com.paperless.scanner.ui.navigation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.paperless.scanner.R
import com.paperless.scanner.data.network.PinRecoveryPhase

/** App-wide, lock-aware surface for pin-store recovery and manual re-enrollment. */
@Composable
fun PinRecoveryInterceptor(
    enabled: Boolean,
    onOpenServerSettings: () -> Unit = {},
    viewModel: PinRecoveryViewModel = hiltViewModel(),
) {
    val required by viewModel.recoveryRequired.collectAsState()
    val phase by viewModel.phase.collectAsState()
    val candidate by viewModel.firstTrust.collectAsState()
    val busy by viewModel.busy.collectAsState()
    val failed by viewModel.failed.collectAsState()
    val showIntro by viewModel.manualIntroVisible.collectAsState()
    if (!enabled) return

    if (required || phase == PinRecoveryPhase.RESET_PENDING) {
        AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.pin_recovery_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.pin_recovery_message))
                    if (failed) Text(
                        stringResource(R.string.pin_recovery_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = viewModel::reset, enabled = !busy) {
                    Text(stringResource(R.string.pin_recovery_reset))
                }
            },
        )
    } else if (candidate != null) {
        val current = candidate!!
        AlertDialog(
            onDismissRequest = { if (!busy) viewModel.decline(current.host, current.presentedPin) },
            title = { Text(stringResource(R.string.pin_first_trust_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.pin_first_trust_message, current.host))
                    Text(
                        current.presentedPin,
                        modifier = Modifier.fillMaxWidth(),
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (failed) Text(
                        stringResource(R.string.cert_pin_storage_failed),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { viewModel.confirm(current.host, current.presentedPin) },
                    enabled = !busy,
                ) { Text(stringResource(R.string.pin_first_trust_confirm)) }
            },
            dismissButton = {
                TextButton(
                    onClick = { viewModel.decline(current.host, current.presentedPin) },
                    enabled = !busy,
                ) { Text(stringResource(R.string.cancel)) }
            },
        )
    } else if (phase == PinRecoveryPhase.MANUAL_ENROLLMENT && showIntro) {
        AlertDialog(
            onDismissRequest = viewModel::dismissIntro,
            title = { Text(stringResource(R.string.pin_recovery_ready_title)) },
            text = { Text(stringResource(R.string.pin_recovery_ready_message)) },
            confirmButton = {
                TextButton(onClick = viewModel::dismissIntro) {
                    Text(stringResource(R.string.pin_recovery_continue))
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    viewModel.dismissIntro()
                    onOpenServerSettings()
                }) { Text(stringResource(R.string.pin_recovery_server_settings)) }
            },
        )
    }
}
