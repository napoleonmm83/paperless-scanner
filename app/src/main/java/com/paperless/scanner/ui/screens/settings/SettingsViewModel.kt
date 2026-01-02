package com.paperless.scanner.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.data.datastore.TokenManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val serverUrl: String = "",
    val isConnected: Boolean = false,
    val showUploadNotifications: Boolean = true,
    val darkMode: Boolean = false
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

            _uiState.value = SettingsUiState(
                serverUrl = serverUrl,
                isConnected = !token.isNullOrBlank(),
                showUploadNotifications = true, // TODO: Store in DataStore
                darkMode = false // TODO: Store in DataStore
            )
        }
    }

    fun setShowUploadNotifications(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(showUploadNotifications = enabled)
        // TODO: Persist to DataStore
    }

    fun setDarkMode(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(darkMode = enabled)
        // TODO: Persist to DataStore and apply theme
    }

    fun logout() {
        viewModelScope.launch {
            tokenManager.clearCredentials()
        }
    }
}
