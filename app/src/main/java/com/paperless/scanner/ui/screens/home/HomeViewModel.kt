package com.paperless.scanner.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.data.analytics.AnalyticsEvent
import com.paperless.scanner.data.analytics.AnalyticsService
import com.paperless.scanner.data.repository.DocumentCountRepository
import com.paperless.scanner.data.repository.DocumentListRepository
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
    val lastSyncedAt: Long? = null,
    val isLoading: Boolean = true,
)

/**
 * Stats orchestrator for the Home dashboard.
 *
 * Issue #72 god-VM decomposition is complete after Phase 5/5 — the original
 * 1313-LOC HomeViewModel is now distributed across 5 focused ViewModels:
 * - [ServerHealthViewModel]      (Phase 1, PR #237) — network + reachability
 * - [ProcessingTasksViewModel]   (Phase 2, PR #243) — upload-task polling
 * - [TagSuggestionsViewModel]    (Phase 3, PR #244) — AI tag suggestions
 * - [RecentDocumentsViewModel]   (Phase 4, PR #245) — recent docs + delete/undo
 * - [TrashOverviewViewModel]     (Phase 5, this PR) — trash + untagged counts
 *
 * HomeViewModel keeps: hero stats ([DocumentStat]), pending-changes count,
 * lastSyncedAt timestamp, and the refresh-debounce orchestration that fans
 * out to sibling VMs via [refreshDashboardIfNeeded]'s Boolean return.
 *
 * The 5 sub-VMs share no direct reference; HomeScreen wires them via
 * `LaunchedEffect` (pattern locked in by PR #237).
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val documentListRepository: DocumentListRepository,
    private val documentCountRepository: DocumentCountRepository,
    private val uploadQueueRepository: UploadQueueRepository,
    private val syncManager: com.paperless.scanner.data.sync.SyncManager,
    private val analyticsService: AnalyticsService,
) : ViewModel() {

    companion object {
        private val logger = Logger.getLogger(HomeViewModel::class.java.name)
        // Debounce: Only refresh if more than 30 seconds since last refresh.
        private const val REFRESH_DEBOUNCE_MS = 30_000L
    }

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
    }

    /**
     * Trigger an auto-refresh of the stats when [ServerHealthViewModel] observes
     * an offline -> online transition. Wired in HomeScreen.
     */
    fun onNetworkReconnected() {
        logger.log(Level.INFO, "Network reconnected - auto-refreshing stats")
        loadDashboardData()
    }

    private var pollingRefreshJob: Job? = null

    /**
     * Refresh dashboard stats while [ProcessingTasksViewModel] is polling for
     * task completion. Guarded so overlapping ticks don't queue concurrent
     * network calls that could let an older response overwrite a newer one.
     */
    fun onPollingTick() {
        if (pollingRefreshJob?.isActive == true) return
        pollingRefreshJob = viewModelScope.launch {
            val stats = loadStats()
            // Preserve current pendingUploads — observePendingUploads is the
            // authoritative source and may have emitted a newer count during
            // the network calls above (CR R3.1 catch, same pattern as
            // loadDashboardData / refreshDashboard).
            _uiState.update { currentState ->
                currentState.copy(stats = stats.copy(pendingUploads = currentState.stats.pendingUploads))
            }
        }
    }

    fun loadDashboardData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val stats = loadStats()
            lastRefreshTimestamp = System.currentTimeMillis()

            // Preserve current pendingUploads: observePendingUploads is the
            // authoritative source and may have emitted a newer count during
            // the network calls above. Overwriting with loadStats()'s captured
            // value would clobber it until the next flow emission.
            _uiState.update { currentState ->
                currentState.copy(
                    stats = stats.copy(pendingUploads = currentState.stats.pendingUploads),
                    lastSyncedAt = lastRefreshTimestamp,
                    isLoading = false,
                )
            }
        }
    }

    /**
     * Refresh hero stats from the server. Trash counts and recent documents
     * are owned by sibling VMs — the screen layer calls each VM in parallel
     * on pull-to-refresh.
     *
     * For ON_RESUME events, use [refreshDashboardIfNeeded] for debounce.
     */
    fun refreshDashboard() {
        viewModelScope.launch {
            val stats = loadStats(forceRefresh = true)
            val now = System.currentTimeMillis()
            lastRefreshTimestamp = now
            // Preserve current pendingUploads (see loadDashboardData comment).
            _uiState.update { currentState ->
                currentState.copy(
                    stats = stats.copy(pendingUploads = currentState.stats.pendingUploads),
                    lastSyncedAt = now,
                )
            }
        }
    }

    /**
     * Debounced refresh for ON_RESUME events. Only refreshes if more than
     * 30 seconds since last refresh.
     *
     * Returns true when an actual refresh was kicked off so the screen layer
     * can fan the same decision out to sibling ViewModels
     * (e.g. [RecentDocumentsViewModel.refreshRecentDocuments],
     * [TrashOverviewViewModel.refreshTrashOverview]) that share the dashboard-
     * refresh contract.
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
        val pendingCount = pendingChangesCountInternal.value
        var totalDocuments = 0
        var thisMonth = 0

        // Always fetch stats from server (forceRefresh = true by default) to
        // ensure accurate counts in multi-client scenarios (web + mobile).
        // Failures log at WARNING — counts default to 0, matching the pre-
        // extraction behavior. Snackbar suppression here is intentional: the
        // hero stats are non-critical and not worth interrupting the user for.
        documentCountRepository.getDocumentCount(forceRefresh = forceRefresh)
            .onSuccess { count -> totalDocuments = count }
            .onFailure { e -> logger.log(Level.WARNING, "loadStats getDocumentCount failed: ${e.message}", e) }

        // This month's document count — approximation from response.count.
        documentListRepository.getDocuments(
            page = 1,
            pageSize = 1,
            ordering = "-added",
            forceRefresh = forceRefresh,
        )
            .onSuccess { response -> thisMonth = minOf(response.count, 30) }
            .onFailure { e -> logger.log(Level.WARNING, "loadStats getDocuments failed: ${e.message}", e) }

        return DocumentStat(
            totalDocuments = totalDocuments,
            thisMonth = thisMonth,
            pendingUploads = pendingCount,
        )
    }

    fun resetState() {
        _uiState.update { HomeUiState() }
    }

    fun clearHomeError() {
        _errorState.value = null
    }

    /**
     * Collect the raw upstream `combine` directly (NOT `pendingChangesCountInternal`)
     * so an upstream exception surfaces to this collector. `stateIn` would swallow
     * the upstream throw and leave the downstream collector blocked on the cached
     * initial value, hiding errors from the snackbar pipeline.
     */
    private fun observePendingUploads() {
        viewModelScope.launch {
            combine(
                uploadQueueRepository.pendingCount,
                syncManager.pendingChangesCount,
            ) { uploadQueueCount, syncPendingCount -> uploadQueueCount + syncPendingCount }
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
