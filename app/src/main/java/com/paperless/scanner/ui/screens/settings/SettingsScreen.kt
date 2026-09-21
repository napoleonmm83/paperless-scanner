package com.paperless.scanner.ui.screens.settings

import android.app.Activity
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
import com.paperless.scanner.ui.screens.settings.dialogs.DiagnosticReportDialog
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
import com.paperless.scanner.util.DiagnosticReportSender
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

    var showLogoutDialog by remember { mutableStateOf(false) }
    var showQualityDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showLicensesDialog by remember { mutableStateOf(false) }
    var showAppLockTimeoutDialog by remember { mutableStateOf(false) }
    var showPremiumUpgradeSheet by remember { mutableStateOf(false) }
    var showSubscriptionManagementSheet by remember { mutableStateOf(false) }
    var purchaseResultMessage by remember { mutableStateOf<String?>(null) }
    var showDiagnosticReportDialog by remember { mutableStateOf(false) }

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
            launchPromoActive = uiState.launchPromoActive,
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
            uploadUnmeteredOnly = uiState.uploadUnmeteredOnly,
            analyticsEnabled = uiState.analyticsEnabled,
            themeMode = uiState.themeMode,
            onShowNotificationsChange = viewModel::setShowUploadNotifications,
            onUploadUnmeteredOnlyChange = viewModel::setUploadUnmeteredOnly,
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
            // 7-tap Easter egg DISABLED in production — no backdoor to Premium features.
            onVersionClick = { },
            onLicensesClick = { showLicensesDialog = true },
            onDiagnosticReportClick = { showDiagnosticReportDialog = true }
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
                            is PurchaseResult.Pending -> {
                                purchaseResultMessage = context.getString(R.string.premium_purchase_pending)
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

    if (showDiagnosticReportDialog) {
        DiagnosticReportDialog(
            onSend = {
                showDiagnosticReportDialog = false
                coroutineScope.launch {
                    val result = viewModel.sendDiagnosticReport()
                    // Only the two SILENT outcomes need a toast, and they say different
                    // things: claiming a clipboard copy that did not happen sends the
                    // user looking for a report that is not there.
                    val message = when (result) {
                        DiagnosticReportSender.Result.COPIED_TO_CLIPBOARD ->
                            R.string.diagnostic_report_copied
                        DiagnosticReportSender.Result.NO_TARGET ->
                            R.string.diagnostic_report_no_target
                        else -> null
                    }
                    message?.let {
                        Toast.makeText(context, context.getString(it), Toast.LENGTH_LONG).show()
                    }
                }
            },
            onCopy = {
                showDiagnosticReportDialog = false
                // Through the shared helper rather than a hand-rolled cast: this used to
                // do `as ClipboardManager` with no catch, so an OEM build without the
                // service crashed the app on the FALLBACK path.
                coroutineScope.launch {
                    val text = viewModel.copyDiagnosticReport()
                    val message = when (DiagnosticReportSender.copyToClipboard(context, text)) {
                        DiagnosticReportSender.Result.COPIED_TO_CLIPBOARD ->
                            R.string.auth_debug_report_copied
                        else -> R.string.diagnostic_report_no_target
                    }
                    Toast.makeText(context, context.getString(message), Toast.LENGTH_SHORT).show()
                }
            },
            onDismiss = { showDiagnosticReportDialog = false }
        )
    }
}
