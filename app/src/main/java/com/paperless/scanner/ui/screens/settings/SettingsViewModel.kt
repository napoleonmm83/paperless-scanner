package com.paperless.scanner.ui.screens.settings

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.R
import com.paperless.scanner.data.datastore.TokenManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    val uploadQuality: UploadQuality = UploadQuality.AUTO
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val tokenManager: TokenManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            val serverUrl = tokenManager.serverUrl.first() ?: ""
            val token = tokenManager.token.first()
            val uploadNotifications = tokenManager.uploadNotificationsEnabled.first()
            val qualityKey = tokenManager.uploadQuality.first()
            val quality = UploadQuality.entries.find { it.key == qualityKey } ?: UploadQuality.AUTO

            _uiState.value = SettingsUiState(
                serverUrl = serverUrl,
                isConnected = !token.isNullOrBlank(),
                showUploadNotifications = uploadNotifications,
                uploadQuality = quality
            )
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

    fun logout() {
        viewModelScope.launch {
            tokenManager.clearCredentials()
        }
    }
}
