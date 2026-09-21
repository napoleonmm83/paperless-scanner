package com.paperless.scanner.ui.screens.settings.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.paperless.scanner.R
import com.paperless.scanner.ui.screens.settings.UploadQuality
import com.paperless.scanner.ui.screens.settings.components.LicenseItem
import com.paperless.scanner.ui.theme.ThemeMode
import com.paperless.scanner.util.AppLockTimeout

@Composable
fun LogoutConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_logout)) },
        text = { Text(stringResource(R.string.settings_logout_confirm_message)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(
                    text = stringResource(R.string.settings_logout),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

@Composable
fun UploadQualityDialog(
    selected: UploadQuality,
    onSelect: (UploadQuality) -> Unit,
    onDismiss: () -> Unit
) {
    SelectionDialog(
        title = stringResource(R.string.settings_upload_quality),
        entries = UploadQuality.entries,
        isSelected = { it == selected },
        labelResId = { it.displayNameRes },
        onSelect = onSelect,
        onDismiss = onDismiss
    )
}

@Composable
fun ThemeModeDialog(
    selected: ThemeMode,
    onSelect: (ThemeMode) -> Unit,
    onDismiss: () -> Unit
) {
    SelectionDialog(
        title = stringResource(R.string.settings_theme),
        entries = ThemeMode.entries,
        isSelected = { it == selected },
        labelResId = { it.displayNameRes },
        onSelect = onSelect,
        onDismiss = onDismiss
    )
}

@Composable
fun AppLockTimeoutDialog(
    selected: AppLockTimeout,
    onSelect: (AppLockTimeout) -> Unit,
    onDismiss: () -> Unit
) {
    SelectionDialog(
        title = stringResource(R.string.app_lock_timeout),
        entries = AppLockTimeout.entries,
        isSelected = { it == selected },
        labelResId = { it.displayNameRes },
        onSelect = onSelect,
        onDismiss = onDismiss
    )
}

@Composable
private fun <T> SelectionDialog(
    title: String,
    entries: List<T>,
    isSelected: (T) -> Boolean,
    labelResId: (T) -> Int,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                entries.forEach { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(entry)
                                onDismiss()
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(labelResId(entry)),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.weight(1f)
                        )
                        if (isSelected(entry)) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = stringResource(R.string.cd_selected),
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_close))
            }
        }
    )
}

@Composable
fun LicensesDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_open_source_licenses)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                LicenseItem("Jetpack Compose", "Apache License 2.0")
                LicenseItem("Material 3", "Apache License 2.0")
                LicenseItem("Retrofit", "Apache License 2.0")
                LicenseItem("OkHttp", "Apache License 2.0")
                LicenseItem("Hilt", "Apache License 2.0")
                LicenseItem("MLKit Document Scanner", "Apache License 2.0")
                LicenseItem("Coil", "Apache License 2.0")
                LicenseItem("DataStore", "Apache License 2.0")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.settings_close))
            }
        }
    )
}

@Composable
fun PurchaseResultDialog(
    message: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.premium_status)) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.ok))
            }
        }
    )
}

/**
 * INTENTIONAL-UNTESTED: this project has no Compose-UI test harness (Roborazzi is
 * deferred as issue #391), so a dialog's button layout cannot be pinned from a unit
 * test. What the buttons DO is pinned where the logic lives —
 * `SettingsViewModel.sendDiagnosticReport` and `DiagnosticReportSender`.
 *
 * Sending is now the primary action and copying the secondary one: the report reached
 * us only if it was mailed, and the clipboard variant carries no log lines.
 */
@Composable
fun DiagnosticReportDialog(
    onSend: () -> Unit,
    onCopy: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        // Generic, not login-specific: the same report is now created for a failed
        // document download or a render failure, and the old text claimed "your recent
        // login attempt" for someone who had not logged in. The explain string says what
        // the report contains and what it does not — the one thing a user needs before
        // handing it over.
        title = { Text(stringResource(R.string.diagnostic_report_title)) },
        text = { Text(stringResource(R.string.diagnostic_report_explain)) },
        confirmButton = {
            TextButton(onClick = onSend) {
                Text(stringResource(R.string.diagnostic_report_send))
            }
        },
        dismissButton = {
            // Both remaining actions share the dismiss slot: AlertDialog offers exactly
            // two, and copying must stay reachable for a device with no mail app.
            Row {
                TextButton(onClick = onCopy) {
                    Text(stringResource(R.string.auth_debug_report_copy))
                }
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    )
}
