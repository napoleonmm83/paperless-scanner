package com.paperless.scanner.ui.screens.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Analytics
import androidx.compose.material.icons.filled.CardMembership
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import android.widget.Toast
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import android.app.Activity
import kotlinx.coroutines.launch
import com.paperless.scanner.data.billing.PurchaseResult
import com.paperless.scanner.data.billing.RestoreResult
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import com.paperless.scanner.BuildConfig
import com.paperless.scanner.R
import com.paperless.scanner.ui.theme.ThemeMode

@Composable
fun SettingsScreen(
    onLogout: () -> Unit,
    onNavigateToSetupAppLock: (isChangingPassword: Boolean) -> Unit = { },
    onNavigateToEditServer: () -> Unit = { },
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var showLogoutDialog by remember { mutableStateOf(false) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showLicensesDialog by remember { mutableStateOf(false) }
    var showAppLockTimeoutDialog by remember { mutableStateOf(false) }
    var showPremiumUpgradeSheet by remember { mutableStateOf(false) }
    var showSubscriptionManagementSheet by remember { mutableStateOf(false) }
    var purchaseResultMessage by remember { mutableStateOf<String?>(null) }

    // 7-tap Easter egg for AI debug mode activation
    var versionTapCount by remember { mutableIntStateOf(0) }
    var lastTapTime by remember { mutableLongStateOf(0L) }
    val tapTimeoutMs = 2000L // Reset counter after 2 seconds of inactivity
    val requiredTaps = 7
    // Reusable Toast for instant feedback (cancel previous before showing new)
    val developerModeToast = remember { mutableStateOf<Toast?>(null) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // Header with Profile
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 24.dp, bottom = 16.dp)
        ) {
            Text(
                text = stringResource(R.string.settings_title),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Profile Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Person,
                            contentDescription = stringResource(R.string.cd_person),
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                    }

                    Spacer(modifier = Modifier.width(16.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.settings_paperless_ngx),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = uiState.serverUrl.ifEmpty { stringResource(R.string.settings_not_connected) },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }

                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(
                                if (uiState.isConnected)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.error
                            )
                    )
                }
            }
        }

        // Premium / AI Assistant Section
        SettingsSection(title = stringResource(R.string.premium_section_title)) {
            // Premium Status Card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        onClick = {
                            if (!uiState.isPremiumActive) {
                                showPremiumUpgradeSheet = true
                            }
                        }
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
                        if (uiState.isPremiumActive) {
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
                    }
                    Text(
                        text = if (uiState.isPremiumActive) {
                            if (uiState.premiumExpiryDate != null)
                                stringResource(R.string.premium_status_active, uiState.premiumExpiryDate!!)
                            else
                                stringResource(R.string.premium_status_active, "∞")
                        } else {
                            stringResource(R.string.premium_status_inactive)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                if (!uiState.isPremiumActive) {
                    Icon(
                        imageVector = Icons.Filled.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // AI Suggestions Toggle (only show if Premium active)
            if (uiState.isPremiumActive) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                SettingsToggleItem(
                    icon = Icons.Filled.AutoAwesome,
                    title = stringResource(R.string.premium_settings_ai_suggestions),
                    subtitle = stringResource(R.string.premium_settings_ai_suggestions_desc),
                    checked = uiState.aiSuggestionsEnabled,
                    onCheckedChange = { viewModel.setAiSuggestionsEnabled(it) }
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                SettingsToggleItem(
                    icon = Icons.Filled.Wifi,
                    title = stringResource(R.string.premium_settings_wifi_only),
                    subtitle = stringResource(R.string.premium_settings_wifi_only_desc),
                    checked = uiState.aiWifiOnly,
                    onCheckedChange = { viewModel.setAiWifiOnly(it) }
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                SettingsToggleItem(
                    icon = Icons.Filled.Description,
                    title = stringResource(R.string.premium_settings_new_tags),
                    subtitle = stringResource(R.string.premium_settings_new_tags_desc),
                    checked = uiState.aiNewTagsEnabled,
                    onCheckedChange = { viewModel.setAiNewTagsEnabled(it) }
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                SettingsClickableItem(
                    icon = Icons.Filled.CardMembership,
                    title = stringResource(R.string.premium_settings_manage_subscription),
                    value = "",
                    onClick = {
                        viewModel.loadSubscriptionInfo()
                        showSubscriptionManagementSheet = true
                    }
                )
            }
        }

        // Server Section
        SettingsSection(title = stringResource(R.string.settings_section_server)) {
            SettingsInfoItem(
                icon = Icons.Filled.Cloud,
                title = stringResource(R.string.settings_server_url),
                value = uiState.serverUrl.ifEmpty { stringResource(R.string.settings_not_configured) }
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

        // Security Section (App-Lock)
        SettingsSection(title = stringResource(R.string.settings_section_security)) {
            // App-Lock Enable/Disable Toggle
            SettingsToggleItem(
                icon = Icons.Filled.Lock,
                title = stringResource(R.string.app_lock_title),
                subtitle = stringResource(R.string.app_lock_subtitle),
                checked = uiState.appLockEnabled,
                onCheckedChange = { enabled ->
                    if (enabled) {
                        // Navigate to setup screen to create password
                        onNavigateToSetupAppLock(false)
                    } else {
                        // Disable app-lock
                        viewModel.setAppLockEnabled(false)
                    }
                }
            )

            // Show additional options only if app-lock is enabled
            if (uiState.appLockEnabled) {
                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                // Biometric Unlock Toggle
                SettingsToggleItem(
                    icon = Icons.Filled.Fingerprint,
                    title = stringResource(R.string.app_lock_biometric_unlock),
                    subtitle = stringResource(R.string.app_lock_biometric_unlock_subtitle),
                    checked = uiState.appLockBiometricEnabled,
                    onCheckedChange = { viewModel.setAppLockBiometricEnabled(it) }
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                // Timeout Selection
                SettingsClickableItem(
                    icon = Icons.Filled.Schedule,
                    title = stringResource(R.string.app_lock_timeout),
                    value = uiState.appLockTimeout.displayName,
                    onClick = { showAppLockTimeoutDialog = true }
                )

                HorizontalDivider(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                )

                // Change Password
                SettingsClickableItem(
                    icon = Icons.Filled.VpnKey,
                    title = stringResource(R.string.app_lock_change_password),
                    value = stringResource(R.string.app_lock_change_password_subtitle),
                    onClick = { onNavigateToSetupAppLock(true) }
                )
            }
        }

        // Upload Section
        SettingsSection(title = stringResource(R.string.settings_section_upload)) {
            SettingsToggleItem(
                icon = Icons.Filled.Notifications,
                title = stringResource(R.string.settings_upload_notifications),
                subtitle = stringResource(R.string.settings_upload_notifications_subtitle),
                checked = uiState.showUploadNotifications,
                onCheckedChange = { viewModel.setShowUploadNotifications(it) }
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            SettingsClickableItem(
                icon = Icons.Filled.HighQuality,
                title = stringResource(R.string.settings_upload_quality),
                value = stringResource(uiState.uploadQuality.displayNameRes),
                onClick = { showQualityDialog = true }
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            SettingsToggleItem(
                icon = Icons.Filled.Analytics,
                title = stringResource(R.string.analytics_settings_title),
                subtitle = stringResource(R.string.analytics_settings_subtitle),
                checked = uiState.analyticsEnabled,
                onCheckedChange = { viewModel.setAnalyticsEnabled(it) }
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            SettingsClickableItem(
                icon = Icons.Filled.Palette,
                title = stringResource(R.string.settings_theme),
                value = stringResource(uiState.themeMode.displayNameRes),
                onClick = { showThemeDialog = true }
            )
        }

        // About Section
        SettingsSection(title = stringResource(R.string.settings_section_about)) {
            // Version item (7-tap Easter egg DISABLED for production)
            SettingsClickableItem(
                icon = Icons.Filled.Info,
                title = stringResource(R.string.settings_app_version),
                value = if (uiState.aiDebugModeEnabled) {
                    "${BuildConfig.VERSION_NAME} (AI Debug)"
                } else {
                    BuildConfig.VERSION_NAME
                },
                onClick = {
                    // 7-tap Easter egg DISABLED
                    // Reason: Production release - no backdoor access to Premium features
                }
            )

            HorizontalDivider(
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
            )

            SettingsClickableItem(
                icon = Icons.Filled.Description,
                title = stringResource(R.string.settings_licenses),
                value = stringResource(R.string.settings_open_source_licenses),
                onClick = { showLicensesDialog = true }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Logout Button
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            onClick = { showLogoutDialog = true }
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

        Spacer(modifier = Modifier.height(32.dp))
    }

    // Logout Confirmation Dialog
    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text(stringResource(R.string.settings_logout)) },
            text = { Text(stringResource(R.string.settings_logout_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showLogoutDialog = false
                        viewModel.logout()
                        onLogout()
                    }
                ) {
                    Text(stringResource(R.string.settings_logout), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }

    // Quality Selection Dialog
    if (showQualityDialog) {
        AlertDialog(
            onDismissRequest = { showQualityDialog = false },
            title = { Text(stringResource(R.string.settings_upload_quality)) },
            text = {
                Column {
                    UploadQuality.entries.forEach { quality ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setUploadQuality(quality)
                                    showQualityDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(quality.displayNameRes),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            if (quality == uiState.uploadQuality) {
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
                TextButton(onClick = { showQualityDialog = false }) {
                    Text(stringResource(R.string.settings_close))
                }
            }
        )
    }

    // Theme Selection Dialog
    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text(stringResource(R.string.settings_theme)) },
            text = {
                Column {
                    ThemeMode.entries.forEach { mode ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setThemeMode(mode)
                                    showThemeDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(mode.displayNameRes),
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            if (mode == uiState.themeMode) {
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
                TextButton(onClick = { showThemeDialog = false }) {
                    Text(stringResource(R.string.settings_close))
                }
            }
        )
    }

    // App-Lock Timeout Selection Dialog
    if (showAppLockTimeoutDialog) {
        AlertDialog(
            onDismissRequest = { showAppLockTimeoutDialog = false },
            title = { Text(stringResource(R.string.app_lock_timeout)) },
            text = {
                Column {
                    com.paperless.scanner.util.AppLockTimeout.entries.forEach { timeout ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    viewModel.setAppLockTimeout(timeout)
                                    showAppLockTimeoutDialog = false
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = timeout.displayName,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.weight(1f)
                            )
                            if (timeout == uiState.appLockTimeout) {
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
                TextButton(onClick = { showAppLockTimeoutDialog = false }) {
                    Text(stringResource(R.string.settings_close))
                }
            }
        )
    }

    // Licenses Dialog
    if (showLicensesDialog) {
        AlertDialog(
            onDismissRequest = { showLicensesDialog = false },
            title = { Text(stringResource(R.string.settings_open_source_licenses)) },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
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
                TextButton(onClick = { showLicensesDialog = false }) {
                    Text(stringResource(R.string.settings_close))
                }
            }
        )
    }

    // Premium Upgrade Sheet
    if (showPremiumUpgradeSheet) {
        PremiumUpgradeSheet(
            onDismiss = { showPremiumUpgradeSheet = false },
            onSubscribe = { productId ->
                android.util.Log.d("SettingsScreen", "════════════════════════════════════════════════")
                android.util.Log.d("SettingsScreen", "onSubscribe called with productId: $productId")
                val activity = context as? Activity
                if (activity != null) {
                    android.util.Log.d("SettingsScreen", "Activity found, launching coroutine...")
                    coroutineScope.launch {
                        android.util.Log.d("SettingsScreen", "Calling viewModel.launchPurchaseFlow()...")
                        when (val result = viewModel.launchPurchaseFlow(activity, productId)) {
                            is PurchaseResult.Success -> {
                                android.util.Log.d("SettingsScreen", "✓ Purchase Result: SUCCESS")
                                purchaseResultMessage = context.getString(R.string.premium_purchase_success)
                                showPremiumUpgradeSheet = false
                            }
                            is PurchaseResult.Cancelled -> {
                                android.util.Log.d("SettingsScreen", "✗ Purchase Result: CANCELLED")
                                // User cancelled, just close sheet (NO SUCCESS MESSAGE!)
                                showPremiumUpgradeSheet = false
                            }
                            is PurchaseResult.Error -> {
                                android.util.Log.e("SettingsScreen", "✗ Purchase Result: ERROR - ${result.message}")
                                purchaseResultMessage = context.getString(R.string.premium_purchase_error, result.message)
                            }
                        }
                        android.util.Log.d("SettingsScreen", "Purchase flow completed")
                        android.util.Log.d("SettingsScreen", "════════════════════════════════════════════════")
                    }
                } else {
                    android.util.Log.e("SettingsScreen", "✗ Activity is null! Cannot launch purchase")
                    purchaseResultMessage = "Unable to launch purchase flow"
                    showPremiumUpgradeSheet = false
                }
            },
            onRestore = {
                coroutineScope.launch {
                    when (val result = viewModel.restorePurchases()) {
                        is RestoreResult.Success -> {
                            purchaseResultMessage = context.getString(R.string.premium_restore_success, result.restoredCount)
                            showPremiumUpgradeSheet = false
                        }
                        is RestoreResult.NoPurchasesFound -> {
                            purchaseResultMessage = context.getString(R.string.premium_restore_none)
                        }
                        is RestoreResult.Error -> {
                            purchaseResultMessage = context.getString(R.string.premium_restore_error, result.message)
                        }
                    }
                }
            }
        )
    }

    // Purchase Result Dialog
    purchaseResultMessage?.let { message ->
        AlertDialog(
            onDismissRequest = { purchaseResultMessage = null },
            title = { Text(stringResource(R.string.premium_status)) },
            text = { Text(message) },
            confirmButton = {
                TextButton(onClick = { purchaseResultMessage = null }) {
                    Text(stringResource(R.string.ok))
                }
            }
        )
    }

    // Subscription Management Sheet
    if (showSubscriptionManagementSheet) {
        SubscriptionManagementSheet(
            subscriptionInfo = uiState.subscriptionInfo,
            onDismiss = { showSubscriptionManagementSheet = false },
            onOpenGooglePlay = {
                val intent = viewModel.getSubscriptionManagementIntent(context)
                context.startActivity(intent)
            },
            onRestore = {
                coroutineScope.launch {
                    when (val result = viewModel.restorePurchases()) {
                        is RestoreResult.Success -> {
                            purchaseResultMessage = context.getString(R.string.premium_restore_success, result.restoredCount)
                            viewModel.loadSubscriptionInfo() // Reload subscription info after restore
                        }
                        is RestoreResult.NoPurchasesFound -> {
                            purchaseResultMessage = context.getString(R.string.premium_restore_none)
                        }
                        is RestoreResult.Error -> {
                            purchaseResultMessage = context.getString(R.string.premium_restore_error, result.message)
                        }
                    }
                }
            }
        )
    }
}

@Composable
private fun LicenseItem(name: String, license: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = license,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(top = 16.dp)
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(bottom = 8.dp)
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
            Column {
                content()
            }
        }
    }
}

@Composable
private fun SettingsInfoItem(
    icon: ImageVector,
    title: String,
    value: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsClickableItem(
    icon: ImageVector,
    title: String,
    value: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SettingsToggleItem(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = title,
            modifier = Modifier.size(24.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.width(16.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SubscriptionManagementSheet(
    subscriptionInfo: com.paperless.scanner.data.billing.SubscriptionInfo?,
    onDismiss: () -> Unit,
    onOpenGooglePlay: () -> Unit,
    onRestore: () -> Unit
) {
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
        ) {
            // Header
            Text(
                text = stringResource(R.string.premium_settings_manage_subscription),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            // Subscription Info Card (if available)
            if (subscriptionInfo != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp)
                    ) {
                        // Product Name
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = subscriptionInfo.productName,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Price
                        SubscriptionInfoRow(
                            label = stringResource(R.string.subscription_price_label),
                            value = subscriptionInfo.price
                        )

                        // Renewal Date
                        subscriptionInfo.renewalDateMs?.let { renewalMs ->
                            val renewalDate = java.text.SimpleDateFormat(
                                "dd.MM.yyyy",
                                java.util.Locale.getDefault()
                            ).format(java.util.Date(renewalMs))

                            SubscriptionInfoRow(
                                label = stringResource(R.string.subscription_renewal_label),
                                value = renewalDate
                            )
                        }

                        // Status
                        val statusText = when (subscriptionInfo.status) {
                            com.paperless.scanner.data.billing.SubscriptionInfoStatus.ACTIVE ->
                                stringResource(R.string.subscription_status_active)
                            com.paperless.scanner.data.billing.SubscriptionInfoStatus.CANCELLED ->
                                stringResource(R.string.subscription_status_cancelled)
                            com.paperless.scanner.data.billing.SubscriptionInfoStatus.PAUSED ->
                                stringResource(R.string.subscription_status_paused)
                            com.paperless.scanner.data.billing.SubscriptionInfoStatus.EXPIRED ->
                                stringResource(R.string.subscription_status_expired)
                        }

                        val statusColor = when (subscriptionInfo.status) {
                            com.paperless.scanner.data.billing.SubscriptionInfoStatus.ACTIVE ->
                                MaterialTheme.colorScheme.primary
                            com.paperless.scanner.data.billing.SubscriptionInfoStatus.CANCELLED ->
                                MaterialTheme.colorScheme.error
                            com.paperless.scanner.data.billing.SubscriptionInfoStatus.PAUSED ->
                                MaterialTheme.colorScheme.tertiary
                            com.paperless.scanner.data.billing.SubscriptionInfoStatus.EXPIRED ->
                                MaterialTheme.colorScheme.error
                        }

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.subscription_status_label),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = statusText,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = statusColor
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Upgrade Hint (if monthly subscription)
                if (subscriptionInfo.isMonthly) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = stringResource(R.string.subscription_upgrade_hint),
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = stringResource(R.string.subscription_upgrade_savings),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // Action Buttons
            // Google Play Button (Primary)
            androidx.compose.material3.Button(
                onClick = {
                    onOpenGooglePlay()
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.subscription_open_google_play),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Restore Purchases Button (Secondary)
            androidx.compose.material3.OutlinedButton(
                onClick = {
                    onRestore()
                    onDismiss()
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Text(
                    text = stringResource(R.string.subscription_restore_purchases),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun SubscriptionInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}
