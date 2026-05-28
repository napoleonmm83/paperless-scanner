package com.paperless.scanner.ui.screens.settings.sections

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.paperless.scanner.R
import com.paperless.scanner.ui.screens.settings.components.SettingsClickableItem
import com.paperless.scanner.ui.screens.settings.components.SettingsSection
import com.paperless.scanner.ui.screens.settings.components.SettingsToggleItem
import com.paperless.scanner.util.AppLockTimeout

@Composable
fun SecuritySection(
    appLockEnabled: Boolean,
    appLockBiometricEnabled: Boolean,
    appLockTimeout: AppLockTimeout,
    onAppLockEnabledChange: (Boolean) -> Unit,
    onBiometricEnabledChange: (Boolean) -> Unit,
    onTimeoutClick: () -> Unit,
    onChangePasswordClick: () -> Unit
) {
    SettingsSection(title = stringResource(R.string.settings_section_security)) {
        SettingsToggleItem(
            icon = Icons.Filled.Lock,
            title = stringResource(R.string.app_lock_title),
            subtitle = stringResource(R.string.app_lock_subtitle),
            checked = appLockEnabled,
            onCheckedChange = onAppLockEnabledChange
        )

        if (appLockEnabled) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            SettingsToggleItem(
                icon = Icons.Filled.Fingerprint,
                title = stringResource(R.string.app_lock_biometric_unlock),
                subtitle = stringResource(R.string.app_lock_biometric_unlock_subtitle),
                checked = appLockBiometricEnabled,
                onCheckedChange = onBiometricEnabledChange
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            SettingsClickableItem(
                icon = Icons.Filled.Schedule,
                title = stringResource(R.string.app_lock_timeout),
                value = stringResource(appLockTimeout.displayNameRes),
                onClick = onTimeoutClick
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            SettingsClickableItem(
                icon = Icons.Filled.VpnKey,
                title = stringResource(R.string.app_lock_change_password),
                value = stringResource(R.string.app_lock_change_password_subtitle),
                onClick = onChangePasswordClick
            )
        }
    }
}

@Preview
@Composable
private fun SecuritySectionDisabledPreview() {
    MaterialTheme {
        SecuritySection(
            appLockEnabled = false,
            appLockBiometricEnabled = false,
            appLockTimeout = AppLockTimeout.IMMEDIATE,
            onAppLockEnabledChange = {},
            onBiometricEnabledChange = {},
            onTimeoutClick = {},
            onChangePasswordClick = {}
        )
    }
}

@Preview
@Composable
private fun SecuritySectionEnabledPreview() {
    MaterialTheme {
        SecuritySection(
            appLockEnabled = true,
            appLockBiometricEnabled = true,
            appLockTimeout = AppLockTimeout.IMMEDIATE,
            onAppLockEnabledChange = {},
            onBiometricEnabledChange = {},
            onTimeoutClick = {},
            onChangePasswordClick = {}
        )
    }
}
