package com.paperless.scanner.ui.screens.settings.sections

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CardMembership
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Wifi
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
import com.paperless.scanner.ui.screens.settings.components.SettingsToggleItem

@Composable
fun PremiumSection(
    isPremiumActive: Boolean,
    premiumExpiryDate: String?,
    launchPromoActive: Boolean = false,
    aiSuggestionsEnabled: Boolean,
    aiWifiOnly: Boolean,
    aiNewTagsEnabled: Boolean,
    onPremiumUpgradeClick: () -> Unit,
    onManageSubscriptionClick: () -> Unit,
    onAiSuggestionsChange: (Boolean) -> Unit,
    onAiWifiOnlyChange: (Boolean) -> Unit,
    onAiNewTagsChange: (Boolean) -> Unit
) {
    SettingsSection(title = stringResource(R.string.premium_section_title)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    enabled = !isPremiumActive,
                    onClick = onPremiumUpgradeClick
                )
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.AutoAwesome,
                contentDescription = stringResource(R.string.premium_section_title),
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.premium_section_title),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium
                    )
                    if (isPremiumActive) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .background(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.premium_badge),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                    if (!isPremiumActive && launchPromoActive) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .background(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = RoundedCornerShape(4.dp)
                                )
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.launch_promo_badge).uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        }
                    }
                }
                Text(
                    text = if (isPremiumActive) {
                        if (!premiumExpiryDate.isNullOrBlank())
                            stringResource(R.string.premium_status_active, premiumExpiryDate)
                        else
                            stringResource(R.string.premium_status_active_no_date)
                    } else {
                        stringResource(R.string.premium_status_inactive)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!isPremiumActive) {
                // Decorative: row already announces "Premium / inactive"; the lock is purely visual.
                Icon(
                    imageVector = Icons.Filled.Lock,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        if (isPremiumActive) {
            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            SettingsToggleItem(
                icon = Icons.Filled.AutoAwesome,
                title = stringResource(R.string.premium_settings_ai_suggestions),
                subtitle = stringResource(R.string.premium_settings_ai_suggestions_desc),
                checked = aiSuggestionsEnabled,
                onCheckedChange = onAiSuggestionsChange
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            SettingsToggleItem(
                icon = Icons.Filled.Wifi,
                title = stringResource(R.string.premium_settings_wifi_only),
                subtitle = stringResource(R.string.premium_settings_wifi_only_desc),
                checked = aiWifiOnly,
                onCheckedChange = onAiWifiOnlyChange
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            SettingsToggleItem(
                icon = Icons.Filled.Description,
                title = stringResource(R.string.premium_settings_new_tags),
                subtitle = stringResource(R.string.premium_settings_new_tags_desc),
                checked = aiNewTagsEnabled,
                onCheckedChange = onAiNewTagsChange
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant
            )

            SettingsClickableItem(
                icon = Icons.Filled.CardMembership,
                title = stringResource(R.string.premium_settings_manage_subscription),
                value = "",
                onClick = onManageSubscriptionClick
            )
        }
    }
}

@Preview
@Composable
private fun PremiumSectionInactivePreview() {
    MaterialTheme {
        PremiumSection(
            isPremiumActive = false,
            premiumExpiryDate = null,
            launchPromoActive = true,
            aiSuggestionsEnabled = false,
            aiWifiOnly = false,
            aiNewTagsEnabled = false,
            onPremiumUpgradeClick = {},
            onManageSubscriptionClick = {},
            onAiSuggestionsChange = {},
            onAiWifiOnlyChange = {},
            onAiNewTagsChange = {}
        )
    }
}

@Preview
@Composable
private fun PremiumSectionActivePreview() {
    MaterialTheme {
        PremiumSection(
            isPremiumActive = true,
            premiumExpiryDate = "31.12.2026",
            aiSuggestionsEnabled = true,
            aiWifiOnly = true,
            aiNewTagsEnabled = false,
            onPremiumUpgradeClick = {},
            onManageSubscriptionClick = {},
            onAiSuggestionsChange = {},
            onAiWifiOnlyChange = {},
            onAiNewTagsChange = {}
        )
    }
}
