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
import com.paperless.scanner.domain.model.ServerFeature
import com.paperless.scanner.ui.screens.settings.components.SettingsClickableItem
import com.paperless.scanner.ui.screens.settings.components.SettingsInfoItem
import com.paperless.scanner.ui.screens.settings.components.SettingsSection

@Composable
fun ServerSection(
    serverUrl: String,
    serverVersion: String?,
    onNavigateToDiagnostics: () -> Unit,
    onNavigateToEditServer: () -> Unit,
    upgradeFeatures: List<ServerFeature> = emptyList(),
) {
    SettingsSection(title = stringResource(R.string.settings_section_server)) {
        SettingsInfoItem(
            icon = Icons.Filled.Cloud,
            title = stringResource(R.string.settings_server_url),
            value = serverUrl.ifEmpty { stringResource(R.string.settings_not_configured) }
        )

        if (serverUrl.isNotEmpty()) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            SettingsInfoItem(
                icon = Icons.Filled.Info,
                title = stringResource(R.string.settings_server_version),
                value = serverVersion ?: stringResource(R.string.settings_server_version_unknown)
            )
        }

        if (serverVersion != null && upgradeFeatures.isNotEmpty()) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
            SettingsInfoItem(
                icon = Icons.Filled.Info,
                title = stringResource(R.string.settings_server_update_features_title),
                value = stringResource(R.string.settings_server_update_features_description),
            )
            upgradeFeatures.forEach { feature ->
                SettingsInfoItem(
                    icon = Icons.Filled.Info,
                    title = stringResource(featureNameRes(feature.id)),
                    value = stringResource(R.string.settings_server_feature_minimum_version, feature.minimumVersion.toString()),
                )
            }
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

private fun featureNameRes(id: String): Int = when (id) {
    "share_links" -> R.string.server_feature_share_links
    "saved_views" -> R.string.server_feature_saved_views
    "bulk_edit" -> R.string.server_feature_bulk_edit
    "similar_documents" -> R.string.server_feature_similar_documents
    "custom_fields_search" -> R.string.server_feature_custom_fields_search
    "search_assistance" -> R.string.server_feature_search_assistance
    "pdf_edit" -> R.string.server_feature_pdf_edit
    "storage_paths" -> R.string.server_feature_storage_paths
    "file_versions" -> R.string.server_feature_file_versions
    else -> R.string.server_feature_additional_function
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
