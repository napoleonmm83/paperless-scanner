package com.paperless.scanner.ui.screens.synccenter

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.R
import com.paperless.scanner.data.database.PendingUpload
import com.paperless.scanner.data.database.dao.PendingChangeDao
import com.paperless.scanner.data.database.entities.PendingChange
import com.paperless.scanner.data.database.entities.SyncHistoryEntry
import com.paperless.scanner.data.repository.SyncHistoryRepository
import com.paperless.scanner.data.repository.UploadQueueRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI State for SyncCenter screen.
 *
 * Combines active operations (uploads, pending changes) with
 * history (completed and failed operations).
 */
data class SyncCenterUiState(
    // Active operations (in progress)
    val activeUploads: List<PendingUpload> = emptyList(),
    val pendingChanges: List<PendingChange> = emptyList(),

    // History
    val recentHistory: List<SyncHistoryEntry> = emptyList(),
    val failedItems: List<SyncHistoryEntry> = emptyList(),

    // UI State
    val isLoading: Boolean = true,
    val error: String? = null
) {
    /**
     * Total count of active operations (for badge display).
     */
    val activeCount: Int
        get() = activeUploads.size + pendingChanges.size

    /**
     * Count of failed items (for badge display).
     */
    val failedCount: Int
        get() = failedItems.size

    /**
     * Whether there are any items to display.
     */
    val isEmpty: Boolean
        get() = activeUploads.isEmpty() &&
                pendingChanges.isEmpty() &&
                recentHistory.isEmpty() &&
                failedItems.isEmpty()
}

/**
 * ViewModel for SyncCenter screen.
 *
 * Provides unified view of:
 * - Active operations (uploads, pending changes)
 * - Completed history
 * - Failed operations with retry capability
 */
@HiltViewModel
class SyncCenterViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val uploadQueueRepository: UploadQueueRepository,
    private val pendingChangeDao: PendingChangeDao,
    private val syncHistoryRepository: SyncHistoryRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SyncCenterUiState())
    val uiState: StateFlow<SyncCenterUiState> = _uiState.asStateFlow()

    init {
        observeAllData()
    }

    /**
     * Observe all data sources reactively using combine.
     */
    private fun observeAllData() {
        viewModelScope.launch {
            combine(
                uploadQueueRepository.allPendingUploads,
                pendingChangeDao.observePendingChanges(),
                syncHistoryRepository.observeRecentHistory(limit = 50),
                syncHistoryRepository.observeFailed()
            ) { uploads, changes, history, failed ->
                SyncCenterUiState(
                    activeUploads = uploads,
                    pendingChanges = changes,
                    recentHistory = history,
                    failedItems = failed,
                    isLoading = false
                )
            }.collect { state ->
                _uiState.value = state
            }
        }
    }

    // ==================== Upload Actions ====================

    /**
     * Retry a failed upload.
     * Marks as uploading so UploadWorker picks it up.
     */
    fun retryUpload(uploadId: Long) {
        viewModelScope.launch {
            try {
                uploadQueueRepository.markAsUploading(uploadId)
                // UploadWorker will pick it up automatically
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: context.getString(R.string.error_retry)) }
            }
        }
    }

    /**
     * Delete an upload from the queue (cancel it).
     */
    fun deleteUpload(uploadId: Long) {
        viewModelScope.launch {
            try {
                uploadQueueRepository.deleteUpload(uploadId)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: context.getString(R.string.error_delete)) }
            }
        }
    }

    // ==================== Pending Change Actions ====================

    /**
     * Delete a pending change from the queue.
     */
    fun deletePendingChange(changeId: Long) {
        viewModelScope.launch {
            try {
                val change = pendingChangeDao.getById(changeId)
                if (change != null) {
                    pendingChangeDao.delete(change)
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: context.getString(R.string.error_delete)) }
            }
        }
    }

    // ==================== History Actions ====================

    /**
     * Delete a single history entry.
     */
    fun deleteHistoryEntry(entryId: Long) {
        viewModelScope.launch {
            try {
                syncHistoryRepository.deleteEntry(entryId)
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: context.getString(R.string.error_delete)) }
            }
        }
    }

    /**
     * Clear all failed entries.
     */
    fun clearFailed() {
        viewModelScope.launch {
            try {
                syncHistoryRepository.clearFailed()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: context.getString(R.string.error_delete)) }
            }
        }
    }

    /**
     * Clear all history entries.
     */
    fun clearHistory() {
        viewModelScope.launch {
            try {
                syncHistoryRepository.clearAll()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: context.getString(R.string.error_delete)) }
            }
        }
    }

    // ==================== Error Handling ====================

    /**
     * Clear error message.
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
