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
import com.paperless.scanner.data.datastore.TokenManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
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
    val isConnected: Boolean = false,
    val showUploadNotifications: Boolean = true,
    val uploadQuality: UploadQuality = UploadQuality.AUTO,
    val analyticsEnabled: Boolean = false,
    // Premium / Subscription
    val isPremiumActive: Boolean = false,
    val premiumExpiryDate: String? = null,
    val aiSuggestionsEnabled: Boolean = true,
    val aiNewTagsEnabled: Boolean = true,
    val aiWifiOnly: Boolean = false
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val tokenManager: TokenManager,
    private val analyticsService: AnalyticsService,
    private val billingManager: BillingManager,
    private val premiumFeatureManager: PremiumFeatureManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
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
            val isPremiumActive = billingManager.isSubscriptionActiveSync()
            val aiSuggestionsEnabled = tokenManager.aiSuggestionsEnabled.first()
            val aiNewTagsEnabled = tokenManager.aiNewTagsEnabled.first()
            val aiWifiOnly = tokenManager.aiWifiOnly.first()

            _uiState.value = SettingsUiState(
                serverUrl = serverUrl,
                isConnected = !token.isNullOrBlank(),
                showUploadNotifications = uploadNotifications,
                uploadQuality = quality,
                analyticsEnabled = analyticsConsent,
                isPremiumActive = isPremiumActive,
                premiumExpiryDate = null, // TODO: Get from BillingManager when available
                aiSuggestionsEnabled = aiSuggestionsEnabled,
                aiNewTagsEnabled = aiNewTagsEnabled,
                aiWifiOnly = aiWifiOnly
            )

            // Observe Premium status changes
            launch {
                billingManager.isSubscriptionActive.collect { isPremium ->
                    _uiState.value = _uiState.value.copy(isPremiumActive = isPremium)
                }
            }

            // Observe AI preferences changes
            launch {
                tokenManager.aiSuggestionsEnabled.collect { enabled ->
                    _uiState.value = _uiState.value.copy(aiSuggestionsEnabled = enabled)
                }
            }

            launch {
                tokenManager.aiNewTagsEnabled.collect { enabled ->
                    _uiState.value = _uiState.value.copy(aiNewTagsEnabled = enabled)
                }
            }

            launch {
                tokenManager.aiWifiOnly.collect { wifiOnly ->
                    _uiState.value = _uiState.value.copy(aiWifiOnly = wifiOnly)
                }
            }
        }
    }

    fun setShowUploadNotifications(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(showUploadNotifications = enabled)
        viewModelScope.launch {
            tokenManager.setUploadNotificationsEnabled(enabled)
        }
    }

    fun setUploadQuality(quality: UploadQuality) {
        _uiState.value = _uiState.value.copy(uploadQuality = quality)
        viewModelScope.launch {
            tokenManager.setUploadQuality(quality.key)
        }
    }

    fun setAnalyticsEnabled(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(analyticsEnabled = enabled)
        viewModelScope.launch {
            tokenManager.setAnalyticsConsent(enabled)
            analyticsService.setEnabled(enabled)
            analyticsService.trackEvent(AnalyticsEvent.AnalyticsConsentChanged(granted = enabled))
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

    suspend fun launchPurchaseFlow(activity: android.app.Activity, productId: String): PurchaseResult {
        return billingManager.launchPurchaseFlow(activity, productId)
    }

    suspend fun restorePurchases(): RestoreResult {
        return billingManager.restorePurchases()
    }

    fun openSubscriptionManagement() {
        // TODO: Open Google Play subscription management
        // This would typically launch an Intent to the Play Store
    }
}
