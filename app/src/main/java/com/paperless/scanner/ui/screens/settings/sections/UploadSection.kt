package com.paperless.scanner.ui.screens.settings.sections

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.paperless.scanner.R
import com.paperless.scanner.ui.screens.settings.UploadQuality
import com.paperless.scanner.ui.screens.settings.components.SettingsClickableItem
import com.paperless.scanner.ui.screens.settings.components.SettingsSection
import com.paperless.scanner.ui.screens.settings.components.SettingsToggleItem
import com.paperless.scanner.ui.theme.ThemeMode

@Composable
fun UploadSection(
    showUploadNotifications: Boolean,
    uploadQuality: UploadQuality,
    analyticsEnabled: Boolean,
    themeMode: ThemeMode,
    onShowNotificationsChange: (Boolean) -> Unit,
    onUploadQualityClick: () -> Unit,
    onAnalyticsEnabledChange: (Boolean) -> Unit,
    onThemeClick: () -> Unit
) {
    SettingsSection(title = stringResource(R.string.settings_section_upload)) {
        SettingsToggleItem(
            icon = Icons.Filled.Notifications,
            title = stringResource(R.string.settings_upload_notifications),
            subtitle = stringResource(R.string.settings_upload_notifications_subtitle),
            checked = showUploadNotifications,
            onCheckedChange = onShowNotificationsChange
        )

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )

        SettingsClickableItem(
            icon = Icons.Filled.HighQuality,
            title = stringResource(R.string.settings_upload_quality),
            value = stringResource(uploadQuality.displayNameRes),
            onClick = onUploadQualityClick
        )

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )

        SettingsToggleItem(
            icon = Icons.Filled.Analytics,
            title = stringResource(R.string.analytics_settings_title),
            subtitle = stringResource(R.string.analytics_settings_subtitle),
            checked = analyticsEnabled,
            onCheckedChange = onAnalyticsEnabledChange
        )

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )

        SettingsClickableItem(
            icon = Icons.Filled.Palette,
            title = stringResource(R.string.settings_theme),
            value = stringResource(themeMode.displayNameRes),
            onClick = onThemeClick
        )
    }
}

@Preview
@Composable
private fun UploadSectionPreview() {
    MaterialTheme {
        UploadSection(
            showUploadNotifications = true,
            uploadQuality = UploadQuality.AUTO,
            analyticsEnabled = false,
            themeMode = ThemeMode.SYSTEM,
            onShowNotificationsChange = {},
            onUploadQualityClick = {},
            onAnalyticsEnabledChange = {},
            onThemeClick = {}
        )
    }
}
