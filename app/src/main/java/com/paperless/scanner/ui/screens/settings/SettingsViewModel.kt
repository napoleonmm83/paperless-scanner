package com.paperless.scanner.ui.screens.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.R
import com.paperless.scanner.data.analytics.AnalyticsEvent
import com.paperless.scanner.data.analytics.AnalyticsService
import com.paperless.scanner.data.billing.BillingManager
import com.paperless.scanner.data.billing.PremiumFeatureManager
import com.paperless.scanner.data.billing.PurchaseResult
import com.paperless.scanner.data.billing.RestoreResult
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.ui.theme.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import retrofit2.HttpException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class UploadQuality(val key: String, @StringRes val displayNameRes: Int) {
    AUTO("auto", R.string.upload_quality_auto),
    LOW("low", R.string.upload_quality_low),
    MEDIUM("medium", R.string.upload_quality_medium),
    HIGH("high", R.string.upload_quality_high)
}

data class SettingsUiState(
    val serverUrl: String = "",
    val serverVersion: String? = null,  // Server version from /api/status/ (null if not loaded or no permission)
    val isConnected: Boolean = false,
    val showUploadNotifications: Boolean = true,
    val uploadQuality: UploadQuality = UploadQuality.AUTO,
    val analyticsEnabled: Boolean = false,
    // Theme
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    // Premium / Subscription
    val isPremiumActive: Boolean = false,
    val premiumExpiryDate: String? = null,
    val aiSuggestionsEnabled: Boolean = true,
    val aiNewTagsEnabled: Boolean = true,
    val aiWifiOnly: Boolean = false,
    // Debug mode (unlocked by 7x tap on version)
    val aiDebugModeEnabled: Boolean = false,
    // App-Lock
    val appLockEnabled: Boolean = false,
    val appLockBiometricEnabled: Boolean = false,
    val appLockTimeout: com.paperless.scanner.util.AppLockTimeout = com.paperless.scanner.util.AppLockTimeout.IMMEDIATE,
    // Subscription Management
    val subscriptionInfo: com.paperless.scanner.data.billing.SubscriptionInfo? = null
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val tokenManager: TokenManager,
    private val api: PaperlessApi,
    private val analyticsService: AnalyticsService,
    private val billingManager: BillingManager,
    private val premiumFeatureManager: PremiumFeatureManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
        loadServerVersion()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            // Load initial values
            val serverUrl = tokenManager.serverUrl.first() ?: ""
            val token = tokenManager.token.first()
            val uploadNotifications = tokenManager.uploadNotificationsEnabled.first()
            val qualityKey = tokenManager.uploadQuality.first()
            val quality = UploadQuality.entries.find { it.key == qualityKey } ?: UploadQuality.AUTO
            val analyticsConsent = tokenManager.analyticsConsent.first() ?: false
            val themeModeKey = tokenManager.themeMode.first()
            val themeMode = ThemeMode.entries.find { it.key == themeModeKey } ?: ThemeMode.SYSTEM
            val isPremiumActive = billingManager.isSubscriptionActiveSync()
            val aiSuggestionsEnabled = tokenManager.aiSuggestionsEnabled.first()
            val aiNewTagsEnabled = tokenManager.aiNewTagsEnabled.first()
            val aiWifiOnly = tokenManager.aiWifiOnly.first()
            val aiDebugModeEnabled = tokenManager.aiDebugModeEnabled.first()
            val appLockEnabled = tokenManager.isAppLockEnabledSync()
            val appLockBiometricEnabled = tokenManager.isAppLockBiometricEnabled()
            val appLockTimeout = tokenManager.getAppLockTimeout()

            _uiState.value = SettingsUiState(
                serverUrl = serverUrl,
                isConnected = !token.isNullOrBlank(),
                showUploadNotifications = uploadNotifications,
                uploadQuality = quality,
                analyticsEnabled = analyticsConsent,
                themeMode = themeMode,
                isPremiumActive = isPremiumActive,
                premiumExpiryDate = null, // TODO: Get from BillingManager when available
                aiSuggestionsEnabled = aiSuggestionsEnabled,
                aiNewTagsEnabled = aiNewTagsEnabled,
                aiWifiOnly = aiWifiOnly,
                aiDebugModeEnabled = aiDebugModeEnabled,
                appLockEnabled = appLockEnabled,
                appLockBiometricEnabled = appLockBiometricEnabled,
                appLockTimeout = appLockTimeout
            )

            // Observe Premium status changes
            launch {
                billingManager.isSubscriptionActive.collect { isPremium ->
                    _uiState.update { it.copy(isPremiumActive = isPremium) }
                }
            }

            // Observe AI preferences changes
            launch {
                tokenManager.aiSuggestionsEnabled.collect { enabled ->
                    _uiState.update { it.copy(aiSuggestionsEnabled = enabled) }
                }
            }

            launch {
                tokenManager.aiNewTagsEnabled.collect { enabled ->
                    _uiState.update { it.copy(aiNewTagsEnabled = enabled) }
                }
            }

            launch {
                tokenManager.aiWifiOnly.collect { wifiOnly ->
                    _uiState.update { it.copy(aiWifiOnly = wifiOnly) }
                }
            }

            // Observe Theme mode changes
            launch {
                tokenManager.themeMode.collect { modeKey ->
                    val mode = ThemeMode.entries.find { it.key == modeKey } ?: ThemeMode.SYSTEM
                    _uiState.update { it.copy(themeMode = mode) }
                }
            }

            // Observe AI debug mode changes
            launch {
                tokenManager.aiDebugModeEnabled.collect { enabled ->
                    _uiState.update { it.copy(aiDebugModeEnabled = enabled) }
                }
            }

            // Observe App-Lock changes
            launch {
                tokenManager.isAppLockEnabled().collect { enabled ->
                    _uiState.update { it.copy(appLockEnabled = enabled) }
                }
            }

            launch {
                tokenManager.isAppLockBiometricEnabledFlow().collect { enabled ->
                    _uiState.update { it.copy(appLockBiometricEnabled = enabled) }
                }
            }

            launch {
                tokenManager.getAppLockTimeoutFlow().collect { timeout ->
                    _uiState.update { it.copy(appLockTimeout = timeout) }
                }
            }
        }
    }

    fun setShowUploadNotifications(enabled: Boolean) {
        _uiState.update { it.copy(showUploadNotifications = enabled) }
        viewModelScope.launch {
            tokenManager.setUploadNotificationsEnabled(enabled)
        }
    }

    fun setUploadQuality(quality: UploadQuality) {
        _uiState.update { it.copy(uploadQuality = quality) }
        viewModelScope.launch {
            tokenManager.setUploadQuality(quality.key)
        }
    }

    fun setAnalyticsEnabled(enabled: Boolean) {
        _uiState.update { it.copy(analyticsEnabled = enabled) }
        viewModelScope.launch {
            tokenManager.setAnalyticsConsent(enabled)
            analyticsService.setEnabled(enabled)
            analyticsService.trackEvent(AnalyticsEvent.AnalyticsConsentChanged(granted = enabled))
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        _uiState.update { it.copy(themeMode = mode) }
        viewModelScope.launch {
            tokenManager.setThemeMode(mode.key)
        }
    }

    fun logout() {
        viewModelScope.launch {
            tokenManager.clearCredentials()
        }
    }

    // Premium / Subscription Methods

    fun setAiSuggestionsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            tokenManager.setAiSuggestionsEnabled(enabled)
        }
    }

    fun setAiNewTagsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            tokenManager.setAiNewTagsEnabled(enabled)
        }
    }

    fun setAiWifiOnly(enabled: Boolean) {
        viewModelScope.launch {
            tokenManager.setAiWifiOnly(enabled)
        }
    }

    /**
     * Enable or disable AI debug mode.
     * NOTE: This no longer grants Premium access (PHASE 2 active).
     * AI features require active subscription regardless of debug mode.
     * Kept for backwards compatibility with existing preferences.
     */
    fun setAiDebugModeEnabled(enabled: Boolean) {
        viewModelScope.launch {
            tokenManager.setAiDebugModeEnabled(enabled)
            // No longer needs to refresh premium access - subscription determines access
        }
    }

    suspend fun launchPurchaseFlow(activity: android.app.Activity, productId: String): PurchaseResult {
        return billingManager.launchPurchaseFlow(activity, productId)
    }

    suspend fun restorePurchases(): RestoreResult {
        return billingManager.restorePurchases()
    }

    /**
     * Load subscription information for display in subscription management sheet.
     */
    fun loadSubscriptionInfo() {
        viewModelScope.launch {
            val info = billingManager.getSubscriptionInfo()
            _uiState.update { it.copy(subscriptionInfo = info) }
        }
    }

    /**
     * Open Google Play subscription management.
     * Returns Intent for launching Play Store subscription management page.
     */
    fun getSubscriptionManagementIntent(context: android.content.Context): android.content.Intent {
        // Google Play subscription management deep link
        // Format: https://play.google.com/store/account/subscriptions?package=<package_name>&sku=<product_id>
        val packageName = context.packageName

        // Try to use the current subscription product ID, fallback to monthly if unknown
        val productId = _uiState.value.subscriptionInfo?.productId
            ?: com.paperless.scanner.data.billing.BillingManager.PRODUCT_ID_MONTHLY

        val subscriptionUrl = "https://play.google.com/store/account/subscriptions?package=$packageName&sku=$productId"

        return android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            data = android.net.Uri.parse(subscriptionUrl)
        }
    }

    // App-Lock Methods

    fun setAppLockEnabled(enabled: Boolean) {
        viewModelScope.launch {
            tokenManager.setAppLockEnabled(enabled)
        }
    }

    fun setAppLockBiometricEnabled(enabled: Boolean) {
        viewModelScope.launch {
            tokenManager.setAppLockBiometricEnabled(enabled)
        }
    }

    fun setAppLockTimeout(timeout: com.paperless.scanner.util.AppLockTimeout) {
        viewModelScope.launch {
            tokenManager.setAppLockTimeout(timeout)
        }
    }

    /**
     * Load server version from /api/status/ endpoint.
     * Requires admin permissions - silently fails if user is not admin (403).
     */
    private fun loadServerVersion() {
        viewModelScope.launch {
            try {
                val serverUrl = tokenManager.serverUrl.first()
                if (serverUrl.isNullOrEmpty()) {
                    // No server configured - skip version check
                    return@launch
                }

                val response = api.getServerStatus()
                if (!response.isSuccessful) {
                    throw retrofit2.HttpException(response)
                }

                val body = response.body()
                // Extract version from x-version header if not in body
                val headerVersion = response.headers()["x-version"]?.takeIf { it.isNotBlank() }
                val version = body?.paperlessVersion?.takeIf { it.isNotBlank() } ?: headerVersion

                _uiState.update { it.copy(serverVersion = version) }
            } catch (e: HttpException) {
                when (e.code()) {
                    403 -> {
                        // User is not admin - silently fail (don't show error)
                        // Version remains null and won't be displayed in UI
                        android.util.Log.d("SettingsViewModel", "Server version not available (no admin permission)")
                    }
                    404 -> {
                        // Old Paperless version without /api/status/ endpoint
                        android.util.Log.d("SettingsViewModel", "Server version not available (endpoint not found)")
                    }
                    else -> {
                        // Other HTTP errors - log but don't crash
                        android.util.Log.w("SettingsViewModel", "Failed to load server version: ${e.code()}")
                    }
                }
            } catch (e: Exception) {
                // Network error or other exception - silently fail
                android.util.Log.w("SettingsViewModel", "Failed to load server version", e)
            }
        }
    }
}
