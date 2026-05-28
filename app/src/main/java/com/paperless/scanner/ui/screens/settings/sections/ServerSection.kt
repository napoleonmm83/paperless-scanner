package com.paperless.scanner.ui.screens.settings.sections

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.paperless.scanner.R
import com.paperless.scanner.ui.screens.settings.components.SettingsClickableItem
import com.paperless.scanner.ui.screens.settings.components.SettingsInfoItem
import com.paperless.scanner.ui.screens.settings.components.SettingsSection

@Composable
fun ServerSection(
    serverUrl: String,
    serverVersion: String?,
    onNavigateToDiagnostics: () -> Unit,
    onNavigateToEditServer: () -> Unit
) {
    SettingsSection(title = stringResource(R.string.settings_section_server)) {
        SettingsInfoItem(
            icon = Icons.Filled.Cloud,
            title = stringResource(R.string.settings_server_url),
            value = serverUrl.ifEmpty { stringResource(R.string.settings_not_configured) }
        )

        serverVersion?.let { version ->
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            SettingsInfoItem(
                icon = Icons.Filled.Info,
                title = stringResource(R.string.settings_server_version),
                value = version
            )
        }

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )

        SettingsClickableItem(
            icon = Icons.Filled.Analytics,
            title = stringResource(R.string.settings_diagnostics),
            value = stringResource(R.string.settings_diagnostics_subtitle),
            onClick = onNavigateToDiagnostics
        )

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )

        SettingsClickableItem(
            icon = Icons.Filled.Settings,
            title = stringResource(R.string.settings_change_server),
            value = stringResource(R.string.settings_change_server_subtitle),
            onClick = onNavigateToEditServer
        )
    }
}

@Preview
@Composable
private fun ServerSectionWithVersionPreview() {
    MaterialTheme {
        ServerSection(
            serverUrl = "https://paperless.example.com",
            serverVersion = "2.10.2",
            onNavigateToDiagnostics = {},
            onNavigateToEditServer = {}
        )
    }
}

@Preview
@Composable
private fun ServerSectionNoVersionPreview() {
    MaterialTheme {
        ServerSection(
            serverUrl = "",
            serverVersion = null,
            onNavigateToDiagnostics = {},
            onNavigateToEditServer = {}
        )
    }
}
