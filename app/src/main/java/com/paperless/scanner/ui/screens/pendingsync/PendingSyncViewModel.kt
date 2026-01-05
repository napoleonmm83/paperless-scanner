package com.paperless.scanner.ui.screens.pendingsync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.data.database.PendingUpload
import com.paperless.scanner.data.database.entities.PendingChange
import com.paperless.scanner.data.repository.UploadQueueRepository
import com.paperless.scanner.data.database.dao.PendingChangeDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PendingSyncUiState(
    val pendingUploads: List<PendingUpload> = emptyList(),
    val pendingChanges: List<PendingChange> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class PendingSyncViewModel @Inject constructor(
    private val uploadQueueRepository: UploadQueueRepository,
    private val pendingChangeDao: PendingChangeDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(PendingSyncUiState())
    val uiState: StateFlow<PendingSyncUiState> = _uiState.asStateFlow()

    init {
        loadPendingItems()
    }

    fun loadPendingItems() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            try {
                // Collect both flows
                uploadQueueRepository.allPendingUploads.collect { uploads ->
                    val changes = pendingChangeDao.getAll()
                    _uiState.update {
                        it.copy(
                            pendingUploads = uploads,
                            pendingChanges = changes,
                            isLoading = false
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        error = e.message ?: "Fehler beim Laden",
                        isLoading = false
                    )
                }
            }
        }
    }

    fun retryUpload(uploadId: Long) {
        viewModelScope.launch {
            try {
                uploadQueueRepository.markAsUploading(uploadId)
                // UploadWorker will pick it up automatically
                loadPendingItems()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun deleteUpload(uploadId: Long) {
        viewModelScope.launch {
            try {
                uploadQueueRepository.deleteUpload(uploadId)
                loadPendingItems()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun deletePendingChange(changeId: Long) {
        viewModelScope.launch {
            try {
                val change = pendingChangeDao.getById(changeId)
                if (change != null) {
                    pendingChangeDao.delete(change)
                }
                loadPendingItems()
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message) }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
}
