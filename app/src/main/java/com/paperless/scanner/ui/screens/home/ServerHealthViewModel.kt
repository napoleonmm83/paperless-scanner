package com.paperless.scanner.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.data.analytics.AnalyticsEvent
import com.paperless.scanner.data.analytics.AnalyticsService
import com.paperless.scanner.data.health.ServerHealthMonitor
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.data.repository.SyncHistoryRepository
import com.paperless.scanner.data.repository.UploadQueueRepository
import com.paperless.scanner.data.sync.SyncManager
import com.paperless.scanner.util.asUiResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject

/**
 * UI state for the network/server-health surface of the Home screen.
 *
 * Owns counters that are derived from the device's network reachability,
 * upload queue, and sync-history tables. Lifecycle-scoped to the Home
 * NavBackStackEntry alongside [HomeViewModel].
 */
data class ServerHealthUiState(
    val activeUploadsCount: Int = 0,
    val failedSyncCount: Int = 0,
)

sealed class ServerHealthError {
    data class LoadFailed(val source: String, val cause: Throwable) : ServerHealthError()
}

/**
 * Owns network, server-reachability, and sync-queue indicators for HomeScreen.
 *
 * Phase 1 of the [HomeViewModel] god-VM decomposition (issue #72).
 *
 * Coordinator hook: [onlineTransition] emits once on every offline -> online
 * transition. [HomeViewModel] subscribes to this in the screen layer and
 * triggers a dashboard refresh; the two ViewModels share no direct reference.
 */
@HiltViewModel
class ServerHealthViewModel @Inject constructor(
    private val networkMonitor: NetworkMonitor,
    private val serverHealthMonitor: ServerHealthMonitor,
    private val uploadQueueRepository: UploadQueueRepository,
    private val syncHistoryRepository: SyncHistoryRepository,
    private val syncManager: SyncManager,
    private val analyticsService: AnalyticsService,
) : ViewModel() {

    companion object {
        private val logger = Logger.getLogger(ServerHealthViewModel::class.java.name)
    }

    val isOnline: StateFlow<Boolean> = networkMonitor.isOnline

    val isServerReachable: StateFlow<Boolean> = serverHealthMonitor.isServerReachable

    val pendingChangesCount: StateFlow<Int> = combine(
        uploadQueueRepository.pendingCount,
        syncManager.pendingChangesCount,
    ) { uploadQueueCount, syncPendingCount ->
        val total = uploadQueueCount + syncPendingCount
        if (total > 0) {
            logger.log(
                Level.INFO,
                "Pending changes - Upload Queue: $uploadQueueCount, Sync Pending: $syncPendingCount, Total: $total",
            )
        }
        total
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _uiState = MutableStateFlow(ServerHealthUiState())
    val uiState: StateFlow<ServerHealthUiState> = _uiState.asStateFlow()

    private val _error = MutableStateFlow<ServerHealthError?>(null)
    val error: StateFlow<ServerHealthError?> = _error.asStateFlow()

    private val _onlineTransition = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val onlineTransition: SharedFlow<Unit> = _onlineTransition.asSharedFlow()

    private var wasOffline = false

    init {
        observeActiveUploadsCount()
        observeFailedSyncCount()
        observeNetworkTransitions()
    }

    private fun observeActiveUploadsCount() {
        viewModelScope.launch {
            uploadQueueRepository.pendingCount
                .asUiResult()
                .collect { result ->
                    result.onSuccess { count ->
                        _uiState.update { it.copy(activeUploadsCount = count) }
                    }.onFailure { e ->
                        _error.value = ServerHealthError.LoadFailed("activeUploads", e)
                    }
                }
        }
    }

    private fun observeFailedSyncCount() {
        viewModelScope.launch {
            syncHistoryRepository.observeFailedCount()
                .asUiResult()
                .collect { result ->
                    result.onSuccess { count ->
                        _uiState.update { it.copy(failedSyncCount = count) }
                    }.onFailure { e ->
                        _error.value = ServerHealthError.LoadFailed("failedSync", e)
                    }
                }
        }
    }

    private fun observeNetworkTransitions() {
        viewModelScope.launch {
            networkMonitor.isOnline.collect { currentlyOnline ->
                analyticsService.trackEvent(AnalyticsEvent.NetworkStatusChanged(isOnline = currentlyOnline))
                if (!currentlyOnline) {
                    analyticsService.trackEvent(AnalyticsEvent.OfflineModeUsed)
                }
                if (currentlyOnline && wasOffline) {
                    logger.log(Level.INFO, "Network reconnected - emitting onlineTransition")
                    _onlineTransition.tryEmit(Unit)
                }
                wasOffline = !currentlyOnline
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
