package com.paperless.scanner.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.data.analytics.AnalyticsEvent
import com.paperless.scanner.data.analytics.AnalyticsService
import com.paperless.scanner.data.repository.DocumentCountRepository
import com.paperless.scanner.data.repository.DocumentListRepository
import com.paperless.scanner.data.repository.TrashRepository
import com.paperless.scanner.data.repository.UploadQueueRepository
import com.paperless.scanner.util.asUiResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.logging.Level
import java.util.logging.Logger
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

data class ProcessingTask(
    val id: Int,
    val taskId: String,
    val fileName: String,
    val status: TaskStatus,
    val timeAgo: String,
    val resultMessage: String? = null,
    val documentId: Int? = null
)

enum class TaskStatus {
    PENDING,
    PROCESSING,
    SUCCESS,
    FAILURE
}

/**
 * Tracks deleted document for undo functionality.
 */
data class DeletedDocumentInfo(
    val id: Int,
    val title: String
)

data class HomeUiState(
    val stats: DocumentStat = DocumentStat(),
    val untaggedCount: Int = 0,
    val deletedCount: Int = 0,
    val oldestDeletedTimestamp: Long? = null, // For "Expires in X days" calculation
    val lastSyncedAt: Long? = null,            // Timestamp of last successful sync
    val isLoading: Boolean = true
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val documentListRepository: DocumentListRepository,
    private val documentCountRepository: DocumentCountRepository,
    private val trashRepository: TrashRepository,
    private val uploadQueueRepository: UploadQueueRepository,
    private val syncManager: com.paperless.scanner.data.sync.SyncManager,
    private val analyticsService: AnalyticsService,
) : ViewModel() {

    companion object {
        private val logger = Logger.getLogger(HomeViewModel::class.java.name)
        // Debounce: Only refresh if more than 30 seconds since last refresh
        private const val REFRESH_DEBOUNCE_MS = 30_000L
    }

    // Track last refresh timestamp for debounce
    private var lastRefreshTimestamp = 0L

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /**
     * Combined pending-changes flow used internally by [observePendingUploads]
     * and [loadStats]. The screen consumes the public counterpart from
     * [ServerHealthViewModel.pendingChangesCount]; both flows are derived from
     * the same repositories so values stay aligned.
     */
    private val pendingChangesCountInternal: StateFlow<Int> = combine(
        uploadQueueRepository.pendingCount,
        syncManager.pendingChangesCount
    ) { uploadQueueCount, syncPendingCount ->
        uploadQueueCount + syncPendingCount
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    private val _errorState = MutableStateFlow<HomeError?>(null)
    val errorState: StateFlow<HomeError?> = _errorState.asStateFlow()

    init {
        analyticsService.trackEvent(AnalyticsEvent.AppOpened)
        loadDashboardData()
        observePendingUploads()
        observeUntaggedCountReactively()
        observeDeletedCountReactively()
        observeOldestDeletedTimestampReactively()
    }

    /**
     * Trigger an auto-refresh of the dashboard when [ServerHealthViewModel]
     * observes an offline -> online transition. Wired in HomeScreen.
     */
    fun onNetworkReconnected() {
        logger.log(Level.INFO, "Network reconnected - auto-refreshing data")
        loadDashboardData()
    }

    private var pollingRefreshJob: Job? = null

    /**
     * Refresh dashboard stats while [ProcessingTasksViewModel] is polling for
     * task completion. Wired via pollingTick in HomeScreen. Guarded so
     * overlapping ticks don't queue concurrent network calls that could let an
     * older response overwrite a newer one.
     */
    fun onPollingTick() {
        if (pollingRefreshJob?.isActive == true) return
        pollingRefreshJob = viewModelScope.launch {
            val stats = loadStats()
            _uiState.update { it.copy(stats = stats) }
        }
    }

    /**
     * BEST PRACTICE: Reactive Flow for untagged documents count.
     * Automatically updates when documents are tagged or new documents are added.
     */
    private fun observeUntaggedCountReactively() {
        viewModelScope.launch {
            documentCountRepository.observeUntaggedDocumentsCount()
                .asUiResult()
                .collect { result ->
                    result.onSuccess { count ->
                        _uiState.update { it.copy(untaggedCount = count) }
                    }.onFailure { e ->
                        _errorState.value = HomeError.LoadFailed("untaggedCount", e)
                    }
                }
        }
    }

    /**
     * BEST PRACTICE: Reactive Flow for trash count.
     * Automatically updates UI when documents are deleted/restored.
     */
    private fun observeDeletedCountReactively() {
        viewModelScope.launch {
            trashRepository.observeTrashedDocumentsCount()
                .asUiResult()
                .collect { result ->
                    result.onSuccess { count ->
                        _uiState.update { it.copy(deletedCount = count) }
                    }.onFailure { e ->
                        _errorState.value = HomeError.LoadFailed("deletedCount", e)
                    }
                }
        }
    }

    /**
     * BEST PRACTICE: Reactive Flow for oldest deleted document timestamp.
     * Automatically updates UI for "Expires in X days" countdown on TrashCard.
     */
    private fun observeOldestDeletedTimestampReactively() {
        viewModelScope.launch {
            trashRepository.observeOldestDeletedTimestamp()
                .asUiResult()
                .collect { result ->
                    result.onSuccess { timestamp ->
                        _uiState.update { it.copy(oldestDeletedTimestamp = timestamp) }
                    }.onFailure { e ->
                        _errorState.value = HomeError.LoadFailed("deletedTimestamp", e)
                    }
                }
        }
    }

    fun loadDashboardData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val stats = loadStats()

            // Refresh untagged count from API
            var untaggedCount = 0
            documentCountRepository.getUntaggedCount().onSuccess { count ->
                untaggedCount = count
            }

            // Sync trash documents (page 1 for quick init, full sync on pull-to-refresh)
            trashRepository.getTrashDocuments(page = 1, pageSize = 100)

            // Update lastSyncedAt on initial load too
            lastRefreshTimestamp = System.currentTimeMillis()

            _uiState.update { currentState ->
                currentState.copy(
                    stats = stats,
                    untaggedCount = untaggedCount,
                    lastSyncedAt = lastRefreshTimestamp,
                    isLoading = false
                )
            }
        }
    }

    /**
     * BEST PRACTICE: Refresh dashboard stats and trash from the server.
     *
     * Use this for pull-to-refresh, post-upload/delete, and reconnect.
     * Recently-added documents are owned by [RecentDocumentsViewModel] — the
     * screen layer calls both VMs in parallel on pull-to-refresh.
     *
     * For ON_RESUME events, use [refreshDashboardIfNeeded] to apply debounce.
     */
    fun refreshDashboard() {
        viewModelScope.launch {
            // Refresh stats from server (forceRefresh = true)
            val stats = loadStats(forceRefresh = true)

            // Update lastSyncedAt timestamp
            val now = System.currentTimeMillis()
            lastRefreshTimestamp = now

            _uiState.update { it.copy(stats = stats, lastSyncedAt = now) }

            // Refresh untagged count from API
            documentCountRepository.getUntaggedCount().onSuccess { count ->
                _uiState.update { it.copy(untaggedCount = count) }
            }

            // Full trash sync: fetch all pages and cleanup orphans
            syncTrashDocuments()
        }
    }

    /**
     * Full trash sync: fetches all pages from server and cleans up orphaned local docs.
     */
    private suspend fun syncTrashDocuments() {
        var page = 1
        var hasMore = true
        val serverTrashIds = mutableSetOf<Int>()

        while (hasMore) {
            trashRepository.getTrashDocuments(page = page, pageSize = 100)
                .onSuccess { response ->
                    serverTrashIds.addAll(response.results.map { it.id })
                    hasMore = response.next != null && response.results.isNotEmpty()
                    page++
                }
                .onFailure {
                    hasMore = false
                }
        }

        // Clean up local trash docs that no longer exist on server
        if (serverTrashIds.isNotEmpty()) {
            trashRepository.cleanupOrphanedTrashDocs(serverTrashIds)
        }
    }

    /**
     * BEST PRACTICE: Debounced refresh for ON_RESUME events.
     * Only refreshes if more than 30 seconds since last refresh.
     * Reduces server load and battery usage for quick app switches.
     *
     * Returns true when an actual refresh was kicked off so the screen layer
     * can fan the same decision out to sibling ViewModels
     * (e.g. [RecentDocumentsViewModel.refreshRecentDocuments]) that share the
     * dashboard-refresh contract.
     */
    fun refreshDashboardIfNeeded(): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastRefreshTimestamp > REFRESH_DEBOUNCE_MS) {
            refreshDashboard()
            return true
        }
        logger.log(Level.FINE, "Skipping refresh - last refresh was ${(now - lastRefreshTimestamp) / 1000}s ago")
        return false
    }

    private suspend fun loadStats(forceRefresh: Boolean = true): DocumentStat {
        val pendingCount = pendingChangesCountInternal.value // Use live flow value
        var totalDocuments = 0
        var thisMonth = 0

        // BEST PRACTICE: Always fetch stats from server (forceRefresh = true by default)
        // to ensure accurate counts in multi-client scenarios (web + mobile)
        documentCountRepository.getDocumentCount(forceRefresh = forceRefresh).onSuccess { count ->
            totalDocuments = count
        }

        // Get this month's document count
        documentListRepository.getDocuments(
            page = 1,
            pageSize = 1,
            ordering = "-added",
            forceRefresh = forceRefresh
        ).onSuccess { response ->
            // Count documents added this month from response.count
            // Note: A more accurate approach would use date filtering if API supports it
            thisMonth = minOf(response.count, 30) // Approximation
        }

        return DocumentStat(
            totalDocuments = totalDocuments,
            thisMonth = thisMonth,
            pendingUploads = pendingCount
        )
    }

    fun resetState() {
        _uiState.update { HomeUiState() }
    }

    fun clearHomeError() {
        _errorState.value = null
    }

    private fun observePendingUploads() {
        viewModelScope.launch {
            pendingChangesCountInternal
                .asUiResult()
                .collect { result ->
                    result.onSuccess { count ->
                        _uiState.update { currentState ->
                            currentState.copy(stats = currentState.stats.copy(pendingUploads = count))
                        }
                    }.onFailure { e ->
                        _errorState.value = HomeError.LoadFailed("pendingUploads", e)
                    }
                }
        }
    }
}
