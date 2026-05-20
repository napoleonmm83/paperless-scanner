package com.paperless.scanner.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.data.repository.DocumentCountRepository
import com.paperless.scanner.data.repository.TrashRepository
import com.paperless.scanner.util.asUiResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the trash-overview surface of the Home screen.
 *
 * Owns the untagged-documents count (Smart-Tagging card), the trashed-documents
 * count (Trash card), and the oldest-deleted timestamp that drives the
 * "Expires in X days" countdown.
 */
data class TrashOverviewUiState(
    val untaggedCount: Int = 0,
    val deletedCount: Int = 0,
    val oldestDeletedTimestamp: Long? = null,
)

sealed class TrashOverviewError {
    data class LoadFailed(val source: String, val cause: Throwable) : TrashOverviewError()
}

/**
 * Owns trash-overview indicators for HomeScreen: untagged count, trash count,
 * oldest-deleted timestamp, and the full trash sync that reconciles local Room
 * state with the server.
 *
 * Phase 5/5 of the [HomeViewModel] god-VM decomposition (issue #72). Closes
 * #72 and unblocks the production-promote gate.
 *
 * Triggers an initial server sync in [init] (mirrors the pre-extraction
 * loadDashboardData() behavior) so counts are current on cold start. The 4
 * sub-VMs (Server/Processing/Tags/RecentDocs/TrashOverview) share no direct
 * reference; the screen layer is the wiring point.
 */
@HiltViewModel
class TrashOverviewViewModel @Inject constructor(
    private val documentCountRepository: DocumentCountRepository,
    private val trashRepository: TrashRepository,
) : ViewModel() {

    companion object {
        private const val TRASH_PAGE_SIZE = 100
    }

    private val _uiState = MutableStateFlow(TrashOverviewUiState())
    val uiState: StateFlow<TrashOverviewUiState> = _uiState.asStateFlow()

    private val _error = MutableStateFlow<TrashOverviewError?>(null)
    val error: StateFlow<TrashOverviewError?> = _error.asStateFlow()

    init {
        observeUntaggedCountReactively()
        observeDeletedCountReactively()
        observeOldestDeletedTimestampReactively()
        // Initial HTTP sync: matches the pre-extraction loadDashboardData() —
        // refresh untagged count from API + sync first page of trash so the
        // Room cache is current on cold start.
        refreshTrashOverview(fullTrashSync = false)
    }

    private fun observeUntaggedCountReactively() {
        viewModelScope.launch {
            documentCountRepository.observeUntaggedDocumentsCount()
                .asUiResult()
                .collect { result ->
                    result.onSuccess { count ->
                        _uiState.update { it.copy(untaggedCount = count) }
                    }.onFailure { e ->
                        _error.value = TrashOverviewError.LoadFailed("untaggedCount", e)
                    }
                }
        }
    }

    private fun observeDeletedCountReactively() {
        viewModelScope.launch {
            trashRepository.observeTrashedDocumentsCount()
                .asUiResult()
                .collect { result ->
                    result.onSuccess { count ->
                        _uiState.update { it.copy(deletedCount = count) }
                    }.onFailure { e ->
                        _error.value = TrashOverviewError.LoadFailed("deletedCount", e)
                    }
                }
        }
    }

    private fun observeOldestDeletedTimestampReactively() {
        viewModelScope.launch {
            trashRepository.observeOldestDeletedTimestamp()
                .asUiResult()
                .collect { result ->
                    result.onSuccess { timestamp ->
                        _uiState.update { it.copy(oldestDeletedTimestamp = timestamp) }
                    }.onFailure { e ->
                        _error.value = TrashOverviewError.LoadFailed("deletedTimestamp", e)
                    }
                }
        }
    }

    /**
     * Refresh the trash-overview counters from the server.
     *
     * - `fullTrashSync = false` (init): only page 1 of trash + untagged count.
     *   Quick hydration for cold start.
     * - `fullTrashSync = true` (pull-to-refresh / reconnect / ON_RESUME-after-
     *   debounce): all pages + orphan cleanup. Same semantics as the
     *   pre-extraction syncTrashDocuments().
     *
     * Reactive Room observers above re-emit when the cache updates.
     */
    fun refreshTrashOverview(fullTrashSync: Boolean = true) {
        viewModelScope.launch {
            // Refresh untagged count from API.
            documentCountRepository.getUntaggedCount()

            if (fullTrashSync) {
                syncAllTrashDocuments()
            } else {
                // Page 1 only — quick init sync.
                trashRepository.getTrashDocuments(page = 1, pageSize = TRASH_PAGE_SIZE)
            }
        }
    }

    /**
     * Full trash sync: fetches all pages from server and cleans up orphaned
     * local docs. Was [HomeViewModel.syncTrashDocuments] before Phase 5.
     */
    private suspend fun syncAllTrashDocuments() {
        var page = 1
        var hasMore = true
        val serverTrashIds = mutableSetOf<Int>()

        while (hasMore) {
            trashRepository.getTrashDocuments(page = page, pageSize = TRASH_PAGE_SIZE)
                .onSuccess { response ->
                    serverTrashIds.addAll(response.results.map { it.id })
                    hasMore = response.next != null && response.results.isNotEmpty()
                    page++
                }
                .onFailure {
                    hasMore = false
                }
        }

        if (serverTrashIds.isNotEmpty()) {
            trashRepository.cleanupOrphanedTrashDocs(serverTrashIds)
        }
    }

    fun clearError() {
        _error.value = null
    }
}
