package com.paperless.scanner.ui.screens.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.data.analytics.AnalyticsEvent
import com.paperless.scanner.data.analytics.AnalyticsService
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.domain.model.Tag
import com.paperless.scanner.data.repository.DocumentCountRepository
import com.paperless.scanner.data.repository.DocumentListRepository
import com.paperless.scanner.data.repository.TagRepository
import com.paperless.scanner.data.repository.TrashRepository
import com.paperless.scanner.data.repository.UploadQueueRepository
import com.paperless.scanner.util.asUiResult
import com.paperless.scanner.util.formatTimeAgo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
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
    val recentDocuments: List<RecentDocument> = emptyList(),
    val deletedDocument: DeletedDocumentInfo? = null, // For undo snackbar
    val untaggedCount: Int = 0,
    val deletedCount: Int = 0,
    val oldestDeletedTimestamp: Long? = null, // For "Expires in X days" calculation
    val lastSyncedAt: Long? = null,            // Timestamp of last successful sync
    val isLoading: Boolean = true
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val documentListRepository: DocumentListRepository,
    private val documentCountRepository: DocumentCountRepository,
    private val trashRepository: TrashRepository,
    private val tagRepository: TagRepository,
    private val uploadQueueRepository: UploadQueueRepository,
    private val syncManager: com.paperless.scanner.data.sync.SyncManager,
    private val analyticsService: AnalyticsService,
    private val tokenManager: TokenManager,
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
    private val pendingChangesCountInternal: StateFlow<Int> = kotlinx.coroutines.flow.combine(
        uploadQueueRepository.pendingCount,
        syncManager.pendingChangesCount
    ) { uploadQueueCount, syncPendingCount ->
        uploadQueueCount + syncPendingCount
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), 0)

    // Server URL for constructing thumbnail URLs
    val serverUrl: StateFlow<String> = tokenManager.serverUrl
        .map { it ?: "" }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ""
        )

    // Whether to show document thumbnails (user preference)
    val showThumbnails: StateFlow<Boolean> = tokenManager.showThumbnails
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    // Thread-safe tag cache. Written from observeTagsReactively, read by
    // observeRecentDocumentsReactively (via combine) and on-demand from
    // tag-suggestion code paths via .value. Atomic StateFlow setter replaces
    // the previous unsynchronized `var Map`.
    private val _tagMap = MutableStateFlow<Map<Int, Tag>>(emptyMap())

    private val _errorState = MutableStateFlow<HomeError?>(null)
    val errorState: StateFlow<HomeError?> = _errorState.asStateFlow()

    init {
        analyticsService.trackEvent(AnalyticsEvent.AppOpened)
        loadDashboardData()
        observePendingUploads()
        observeTagsReactively()
        observeRecentDocumentsReactively()
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
     * BEST PRACTICE: Reactive Flow for tags.
     * Atomically refreshes [_tagMap] when tags are added/modified/deleted.
     */
    private fun observeTagsReactively() {
        viewModelScope.launch {
            tagRepository.observeTags()
                .asUiResult()
                .collect { result ->
                    result.onSuccess { tags ->
                        _tagMap.value = tags.associateBy { it.id }
                    }.onFailure { e ->
                        _errorState.value = HomeError.LoadFailed("tags", e)
                    }
                }
        }
    }

    /**
     * BEST PRACTICE: Reactive Flow for recent documents.
     *
     * Combines the documents flow with [_tagMap] so a tag rename / recolor
     * propagates to the recently-added cards immediately, instead of waiting
     * for the next document update. Also removes the prior race where this
     * collector could read a partial [_tagMap] mid-refresh.
     */
    private fun observeRecentDocumentsReactively() {
        viewModelScope.launch {
            combine(
                documentListRepository.observeDocuments(page = 1, pageSize = 5),
                _tagMap
            ) { documents, currentTagMap ->
                documents.map { doc ->
                    val firstTagId = doc.tags.firstOrNull()
                    val tag = firstTagId?.let { currentTagMap[it] }
                    RecentDocument(
                        id = doc.id,
                        title = doc.title,
                        timeAgo = formatTimeAgo(context, doc.added),
                        tagName = tag?.name,
                        tagColor = tag?.color?.let { parseColorToLong(it) }
                    )
                }
            }
                .asUiResult()
                .collect { result ->
                    result.onSuccess { recentDocs ->
                        _uiState.update { it.copy(recentDocuments = recentDocs, isLoading = false) }
                    }.onFailure { e ->
                        _errorState.value = HomeError.LoadFailed("recentDocuments", e)
                    }
                }
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

            // Refresh recent documents from API (updates Room cache, triggers reactive Flow)
            documentListRepository.getDocuments(
                page = 1,
                pageSize = 10,
                ordering = "-added",
                forceRefresh = true
            )

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
     * BEST PRACTICE: Refresh both stats and tasks.
     * Use this for:
     * - Pull-to-refresh user actions (forceRefresh = true)
     * - After upload/delete operations (forceRefresh = true)
     * - Network reconnect scenarios (forceRefresh = true)
     *
     * For ON_RESUME events, use refreshDashboardIfNeeded() to apply debounce.
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

            // Refresh recent documents from API (updates Room cache, triggers reactive Flow)
            documentListRepository.getDocuments(
                page = 1,
                pageSize = 10,
                ordering = "-added",
                forceRefresh = true
            )

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
     */
    fun refreshDashboardIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastRefreshTimestamp > REFRESH_DEBOUNCE_MS) {
            refreshDashboard()
        } else {
            logger.log(Level.FINE, "Skipping refresh - last refresh was ${(now - lastRefreshTimestamp) / 1000}s ago")
        }
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


    private fun parseColorToLong(colorString: String): Long? {
        return try {
            if (colorString.startsWith("#")) {
                java.lang.Long.parseLong(colorString.removePrefix("#"), 16) or 0xFF000000
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun resetState() {
        _uiState.update { HomeUiState() }
    }

    fun clearHomeError() {
        _errorState.value = null
    }

    fun deleteRecentDocument(documentId: Int, documentTitle: String) {
        viewModelScope.launch {
            // Set deleted document info for undo snackbar
            _uiState.update {
                it.copy(deletedDocument = DeletedDocumentInfo(documentId, documentTitle))
            }

            try {
                trashRepository.deleteDocument(documentId).onSuccess {
                    // Recent documents list updates automatically via reactive Flow
                    analyticsService.trackEvent(AnalyticsEvent.DocumentDeleted)
                }.onFailure { error ->
                    _uiState.update { it.copy(deletedDocument = null) }
                    _errorState.value = HomeError.ActionFailed(
                        "deleteDocument",
                        error
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update { it.copy(deletedDocument = null) }
                _errorState.value = HomeError.ActionFailed(
                    "deleteDocument",
                    e
                )
            }
        }
    }

    /**
     * Undo the most recent document deletion.
     * Restores the document by clearing its soft-delete flag.
     */
    fun undoDelete() {
        val deletedDoc = _uiState.value.deletedDocument ?: return

        viewModelScope.launch {
            // Clear deleted document state immediately for responsive UX
            _uiState.update { it.copy(deletedDocument = null) }

            // Restore document via repository
            try {
                trashRepository.restoreDocument(deletedDoc.id).onFailure {
                    _errorState.value = HomeError.ActionFailed(
                        "restoreDocument",
                        it
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _errorState.value = HomeError.ActionFailed(
                    "restoreDocument",
                    e
                )
            }
        }
    }

    /**
     * Clear the deleted document info (when undo snackbar is dismissed).
     */
    fun clearDeletedDocument() {
        _uiState.update { it.copy(deletedDocument = null) }
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

    // onCleared() — no overrides needed after Phase 2 (polling lifecycle moved
    // to ProcessingTasksViewModel.onCleared()).
}
