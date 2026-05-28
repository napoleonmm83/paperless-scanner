package com.paperless.scanner.ui.screens.settings

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.paperless.scanner.BuildConfig
import com.paperless.scanner.R
import com.paperless.scanner.data.billing.PurchaseResult
import com.paperless.scanner.data.billing.RestoreResult
import com.paperless.scanner.ui.screens.settings.dialogs.AppLockTimeoutDialog
import com.paperless.scanner.ui.screens.settings.dialogs.AuthDebugReportDialog
import com.paperless.scanner.ui.screens.settings.dialogs.LicensesDialog
import com.paperless.scanner.ui.screens.settings.dialogs.LogoutConfirmationDialog
import com.paperless.scanner.ui.screens.settings.dialogs.PurchaseResultDialog
import com.paperless.scanner.ui.screens.settings.dialogs.ThemeModeDialog
import com.paperless.scanner.ui.screens.settings.dialogs.UploadQualityDialog
import com.paperless.scanner.ui.screens.settings.sections.AboutSection
import com.paperless.scanner.ui.screens.settings.sections.LogoutButton
import com.paperless.scanner.ui.screens.settings.sections.PremiumSection
import com.paperless.scanner.ui.screens.settings.sections.ProfileHeader
import com.paperless.scanner.ui.screens.settings.sections.SecuritySection
import com.paperless.scanner.ui.screens.settings.sections.ServerSection
import com.paperless.scanner.ui.screens.settings.sections.UploadSection
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    onLogout: () -> Unit,
    onNavigateToSetupAppLock: (isChangingPassword: Boolean) -> Unit = { },
    onNavigateToEditServer: () -> Unit = { },
    onNavigateToDiagnostics: () -> Unit = { },
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val authDebugReport by viewModel.hasAuthDebugReport.collectAsState()

    var showLogoutDialog by remember { mutableStateOf(false) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showLicensesDialog by remember { mutableStateOf(false) }
    var showAppLockTimeoutDialog by remember { mutableStateOf(false) }
    var showPremiumUpgradeSheet by remember { mutableStateOf(false) }
    var showSubscriptionManagementSheet by remember { mutableStateOf(false) }
    var purchaseResultMessage by remember { mutableStateOf<String?>(null) }
    var showAuthDebugReportDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        ProfileHeader(
            serverUrl = uiState.serverUrl,
            isConnected = uiState.isConnected
        )

        PremiumSection(
            isPremiumActive = uiState.isPremiumActive,
            premiumExpiryDate = uiState.premiumExpiryDate,
            aiSuggestionsEnabled = uiState.aiSuggestionsEnabled,
            aiWifiOnly = uiState.aiWifiOnly,
            aiNewTagsEnabled = uiState.aiNewTagsEnabled,
            onPremiumUpgradeClick = { showPremiumUpgradeSheet = true },
            onManageSubscriptionClick = {
                viewModel.loadSubscriptionInfo()
                showSubscriptionManagementSheet = true
            },
            onAiSuggestionsChange = viewModel::setAiSuggestionsEnabled,
            onAiWifiOnlyChange = viewModel::setAiWifiOnly,
            onAiNewTagsChange = viewModel::setAiNewTagsEnabled
        )

        ServerSection(
            serverUrl = uiState.serverUrl,
            serverVersion = uiState.serverVersion,
            onNavigateToDiagnostics = onNavigateToDiagnostics,
            onNavigateToEditServer = onNavigateToEditServer
        )

        SecuritySection(
            appLockEnabled = uiState.appLockEnabled,
            appLockBiometricEnabled = uiState.appLockBiometricEnabled,
            appLockTimeout = uiState.appLockTimeout,
            onAppLockEnabledChange = { enabled ->
                if (enabled) {
                    onNavigateToSetupAppLock(false)
                } else {
                    viewModel.setAppLockEnabled(false)
                }
            },
            onBiometricEnabledChange = viewModel::setAppLockBiometricEnabled,
            onTimeoutClick = { showAppLockTimeoutDialog = true },
            onChangePasswordClick = { onNavigateToSetupAppLock(true) }
        )

        UploadSection(
            showUploadNotifications = uiState.showUploadNotifications,
            uploadQuality = uiState.uploadQuality,
            analyticsEnabled = uiState.analyticsEnabled,
            themeMode = uiState.themeMode,
            onShowNotificationsChange = viewModel::setShowUploadNotifications,
            onUploadQualityClick = { showQualityDialog = true },
            onAnalyticsEnabledChange = viewModel::setAnalyticsEnabled,
            onThemeClick = { showThemeDialog = true }
        )

        AboutSection(
            appVersionLabel = if (uiState.aiDebugModeEnabled) {
                "${BuildConfig.VERSION_NAME} (AI Debug)"
            } else {
                BuildConfig.VERSION_NAME
            },
            hasAuthDebugReport = authDebugReport != null,
            // 7-tap Easter egg DISABLED in production — no backdoor to Premium features.
            onVersionClick = { },
            onLicensesClick = { showLicensesDialog = true },
            onAuthDebugReportClick = { showAuthDebugReportDialog = true }
        )

        Spacer(modifier = Modifier.height(16.dp))

        LogoutButton(onClick = { showLogoutDialog = true })

        Spacer(modifier = Modifier.height(32.dp))
    }

    if (showLogoutDialog) {
        LogoutConfirmationDialog(
            onConfirm = {
                showLogoutDialog = false
                viewModel.logout()
                onLogout()
            },
            onDismiss = { showLogoutDialog = false }
        )
    }

    if (showQualityDialog) {
        UploadQualityDialog(
            selected = uiState.uploadQuality,
            onSelect = viewModel::setUploadQuality,
            onDismiss = { showQualityDialog = false }
        )
    }

    if (showThemeDialog) {
        ThemeModeDialog(
            selected = uiState.themeMode,
            onSelect = viewModel::setThemeMode,
            onDismiss = { showThemeDialog = false }
        )
    }

    if (showAppLockTimeoutDialog) {
        AppLockTimeoutDialog(
            selected = uiState.appLockTimeout,
            onSelect = viewModel::setAppLockTimeout,
            onDismiss = { showAppLockTimeoutDialog = false }
        )
    }

    if (showLicensesDialog) {
        LicensesDialog(onDismiss = { showLicensesDialog = false })
    }

    if (showPremiumUpgradeSheet) {
        PremiumUpgradeSheet(
            onDismiss = { showPremiumUpgradeSheet = false },
            onSubscribe = { productId ->
                val activity = context as? Activity
                if (activity != null) {
                    coroutineScope.launch {
                        when (val result = viewModel.launchPurchaseFlow(activity, productId)) {
                            is PurchaseResult.Success -> {
                                purchaseResultMessage = context.getString(R.string.premium_purchase_success)
                                showPremiumUpgradeSheet = false
                            }
                            is PurchaseResult.Cancelled -> {
                                showPremiumUpgradeSheet = false
                            }
                            is PurchaseResult.Error -> {
                                purchaseResultMessage = context.getString(R.string.premium_purchase_error, result.message)
                            }
                        }
                    }
                } else {
                    purchaseResultMessage = context.getString(R.string.error_unable_launch_purchase)
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

    purchaseResultMessage?.let { message ->
        PurchaseResultDialog(
            message = message,
            onDismiss = { purchaseResultMessage = null }
        )
    }

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
                            viewModel.loadSubscriptionInfo()
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

    if (showAuthDebugReportDialog) {
        AuthDebugReportDialog(
            onCopy = {
                val shareableReport = viewModel.getShareableAuthDebugReport()
                val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Auth Debug Report", shareableReport)
                clipboardManager.setPrimaryClip(clip)
                Toast.makeText(
                    context,
                    context.getString(R.string.auth_debug_report_copied),
                    Toast.LENGTH_SHORT
                ).show()
                viewModel.clearAuthDebugReport()
                showAuthDebugReportDialog = false
            },
            onDismiss = { showAuthDebugReportDialog = false }
        )
    }
}
