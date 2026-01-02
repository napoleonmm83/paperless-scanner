package com.paperless.scanner.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.data.repository.DocumentRepository
import com.paperless.scanner.data.repository.UploadQueueRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DocumentStat(
    val totalDocuments: Int = 0,
    val thisMonth: Int = 0,
    val pendingUploads: Int = 0
)

data class RecentDocument(
    val id: Int,
    val title: String,
    val timeAgo: String,
    val tagName: String?,
    val tagColor: Long?
)

data class HomeUiState(
    val stats: DocumentStat = DocumentStat(),
    val recentDocuments: List<RecentDocument> = emptyList(),
    val untaggedCount: Int = 0,
    val isLoading: Boolean = true
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val documentRepository: DocumentRepository,
    private val uploadQueueRepository: UploadQueueRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadDashboardData()
    }

    fun loadDashboardData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            try {
                // Load stats
                val stats = loadStats()
                val recentDocs = loadRecentDocuments()
                val untagged = loadUntaggedCount()

                _uiState.value = HomeUiState(
                    stats = stats,
                    recentDocuments = recentDocs,
                    untaggedCount = untagged,
                    isLoading = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    private suspend fun loadStats(): DocumentStat {
        val pendingCount = uploadQueueRepository.getPendingUploadCount()

        // For now, use placeholder values - in a real app, these would come from API
        // TODO: Implement document stats API call
        return DocumentStat(
            totalDocuments = 0,
            thisMonth = 0,
            pendingUploads = pendingCount
        )
    }

    private suspend fun loadRecentDocuments(): List<RecentDocument> {
        // TODO: Implement recent documents API call
        return emptyList()
    }

    private suspend fun loadUntaggedCount(): Int {
        // TODO: Implement untagged count API call
        return 0
    }
}
