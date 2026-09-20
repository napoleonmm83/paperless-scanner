package com.paperless.scanner.ui.screens.settings.sections

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.paperless.scanner.R
import com.paperless.scanner.ui.screens.settings.components.SettingsClickableItem
import com.paperless.scanner.ui.screens.settings.components.SettingsSection

@Composable
fun AboutSection(
    appVersionLabel: String,
    hasDiagnosticReport: Boolean,
    onVersionClick: () -> Unit,
    onLicensesClick: () -> Unit,
    onDiagnosticReportClick: () -> Unit
) {
    SettingsSection(title = stringResource(R.string.settings_section_about)) {
        SettingsClickableItem(
            icon = Icons.Filled.Info,
            title = stringResource(R.string.settings_app_version),
            value = appVersionLabel,
            onClick = onVersionClick
        )

        HorizontalDivider(
            modifier = Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )

        SettingsClickableItem(
            icon = Icons.Filled.Description,
            title = stringResource(R.string.settings_licenses),
            value = stringResource(R.string.settings_open_source_licenses),
            onClick = onLicensesClick
        )

        if (hasDiagnosticReport) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            SettingsClickableItem(
                icon = Icons.Filled.BugReport,
                // INTENTIONAL-UNTESTED: no Compose-UI test harness exists in this project
                // (Roborazzi is deferred as issue #391), so a string swap in a settings
                // row cannot be pinned from a unit test. What changes is which resource
                // id is read; the row's behaviour is untouched.
                //
                // Not "Login Debug Report" any more: a failed document download or a
                // render failure fills the same slot, and the login wording named a cause
                // the user may never have been near.
                title = stringResource(R.string.diagnostic_report_title),
                value = stringResource(R.string.diagnostic_report_subtitle),
                onClick = onDiagnosticReportClick
            )
        }
    }
}

@Composable
fun LogoutButton(onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Logout,
                contentDescription = stringResource(R.string.cd_logout),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.settings_logout),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Preview
@Composable
private fun AboutSectionWithDebugPreview() {
    MaterialTheme {
        AboutSection(
            appVersionLabel = "1.5.138",
            hasDiagnosticReport = true,
            onVersionClick = {},
            onLicensesClick = {},
            onDiagnosticReportClick = {}
        )
    }
}

@Preview
@Composable
private fun AboutSectionNoDebugPreview() {
    MaterialTheme {
        AboutSection(
            appVersionLabel = "1.5.138 (AI Debug)",
            hasDiagnosticReport = false,
            onVersionClick = {},
            onLicensesClick = {},
            onDiagnosticReportClick = {}
        )
    }
}

@Preview
@Composable
private fun LogoutButtonPreview() {
    MaterialTheme {
        LogoutButton(onClick = {})
    }
}
