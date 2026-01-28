package com.paperless.scanner.ui.screens.home

import android.content.Context
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.R
import com.paperless.scanner.data.ai.SuggestionOrchestrator
import com.paperless.scanner.data.ai.models.SuggestionResult
import com.paperless.scanner.data.analytics.AnalyticsEvent
import com.paperless.scanner.data.analytics.AnalyticsService
import com.paperless.scanner.data.billing.PremiumFeature
import com.paperless.scanner.data.billing.PremiumFeatureManager
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.domain.model.PaperlessTask
import com.paperless.scanner.domain.model.Tag
import com.paperless.scanner.data.repository.DocumentRepository
import com.paperless.scanner.data.repository.SyncHistoryRepository
import com.paperless.scanner.data.repository.TagRepository
import com.paperless.scanner.data.repository.TaskRepository
import com.paperless.scanner.data.repository.UploadQueueRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
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

// Tag creation state
sealed class CreateTagState {
    data object Idle : CreateTagState()
    data object Creating : CreateTagState()
    data class Success(val tag: Tag) : CreateTagState()
    data class Error(val message: String) : CreateTagState()
}

data class HomeUiState(
    val stats: DocumentStat = DocumentStat(),
    val recentDocuments: List<RecentDocument> = emptyList(),
    val processingTasks: List<ProcessingTask> = emptyList(),
    val allProcessingTasksCount: Int = 0,      // Total count for Hero Card (unlimited)
    val showAllProcessingTasks: Boolean = false, // Toggle for "show more" in list
    val untaggedCount: Int = 0,
    val deletedCount: Int = 0,
    val oldestDeletedTimestamp: Long? = null, // For "Expires in X days" calculation
    val activeUploadsCount: Int = 0,           // NEW: Active uploads in queue
    val failedSyncCount: Int = 0,              // NEW: Failed sync operations
    val lastSyncedAt: Long? = null,            // NEW: Timestamp of last successful sync
    val isLoading: Boolean = true,
    val error: String? = null
) {
    companion object {
        const val PROCESSING_TASKS_DISPLAY_LIMIT = 10
    }

    /**
     * Total processing count for Hero Card progress indicator.
     * Combines active uploads + ALL Paperless server tasks (not limited).
     */
    val totalProcessingCount: Int
        get() = activeUploadsCount + allProcessingTasksCount

    /**
     * Number of hidden tasks (for "show X more" button).
     */
    val hiddenProcessingTasksCount: Int
        get() = if (showAllProcessingTasks) 0 else maxOf(0, processingTasks.size - PROCESSING_TASKS_DISPLAY_LIMIT)

    /**
     * Tasks to display in UI (limited or all based on toggle).
     */
    val displayedProcessingTasks: List<ProcessingTask>
        get() = if (showAllProcessingTasks) processingTasks else processingTasks.take(PROCESSING_TASKS_DISPLAY_LIMIT)
}

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val documentRepository: DocumentRepository,
    private val tagRepository: TagRepository,
    private val taskRepository: TaskRepository,
    private val uploadQueueRepository: UploadQueueRepository,
    private val syncHistoryRepository: SyncHistoryRepository,
    private val networkMonitor: com.paperless.scanner.data.network.NetworkMonitor,
    private val serverHealthMonitor: com.paperless.scanner.data.health.ServerHealthMonitor,
    private val syncManager: com.paperless.scanner.data.sync.SyncManager,
    private val analyticsService: AnalyticsService,
    private val suggestionOrchestrator: SuggestionOrchestrator,
    private val tokenManager: TokenManager,
    private val premiumFeatureManager: PremiumFeatureManager
) : ViewModel() {

    companion object {
        private val logger = Logger.getLogger(HomeViewModel::class.java.name)
        private const val POLLING_INTERVAL_MS = 3000L
        private const val NETWORK_CHECK_INTERVAL_MS = 1000L
        // Debounce: Only refresh if more than 30 seconds since last refresh
        private const val REFRESH_DEBOUNCE_MS = 30_000L
    }

    // Track last refresh timestamp for debounce
    private var lastRefreshTimestamp = 0L

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    // Live network status from NetworkMonitor
    val isOnline: StateFlow<Boolean> = networkMonitor.isOnline

    // Server reachability status (internet + server online)
    val isServerReachable: StateFlow<Boolean> = serverHealthMonitor.isServerReachable

    // Combined pending changes: Upload Queue + Sync Pending Changes
    val pendingChangesCount: StateFlow<Int> = kotlinx.coroutines.flow.combine(
        uploadQueueRepository.pendingCount,
        syncManager.pendingChangesCount
    ) { uploadQueueCount, syncPendingCount ->
        val total = uploadQueueCount + syncPendingCount
        if (total > 0) {
            logger.log(Level.INFO, "Pending changes - Upload Queue: $uploadQueueCount, Sync Pending: $syncPendingCount, Total: $total")
        }
        total
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), 0)

    private var tagMap: Map<Int, Tag> = emptyMap()
    private var wasOffline = false

    // Tag Suggestions Sheet state
    private val _tagSuggestionsState = MutableStateFlow(TagSuggestionsState())
    val tagSuggestionsState: StateFlow<TagSuggestionsState> = _tagSuggestionsState.asStateFlow()

    private val _showTagSuggestionsSheet = MutableStateFlow(false)
    val showTagSuggestionsSheet: StateFlow<Boolean> = _showTagSuggestionsSheet.asStateFlow()

    // Available tags for the tag picker
    val availableTags: StateFlow<List<Tag>> = tagRepository.observeTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Check if AI is available (premium feature)
    val isAiAvailable: StateFlow<Boolean> = premiumFeatureManager.isAiEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // Tag creation state
    private val _createTagState = MutableStateFlow<CreateTagState>(CreateTagState.Idle)
    val createTagState: StateFlow<CreateTagState> = _createTagState.asStateFlow()

    init {
        analyticsService.trackEvent(AnalyticsEvent.AppOpened)
        loadDashboardData()
        startNetworkMonitoring()
        observePendingUploads()
        observeTagsReactively()
        observeRecentDocumentsReactively()
        observeProcessingTasksReactively()
        observeUntaggedCountReactively()
        observeDeletedCountReactively()
        observeOldestDeletedTimestampReactively()
        observeActiveUploadsCountReactively()
        observeFailedSyncCountReactively()
    }

    /**
     * BEST PRACTICE: Reactive Flow for tags.
     * Automatically updates tagMap when tags are added/modified/deleted.
     */
    private fun observeTagsReactively() {
        viewModelScope.launch {
            tagRepository.observeTags().collect { tags ->
                tagMap = tags.associateBy { it.id }
            }
        }
    }

    /**
     * BEST PRACTICE: Reactive Flow for recent documents.
     * Automatically updates UI when documents are added/modified/deleted in DB.
     */
    private fun observeRecentDocumentsReactively() {
        viewModelScope.launch {
            documentRepository.observeDocuments(
                page = 1,
                pageSize = 5
            ).collect { documents ->
                val recentDocs = documents.map { doc ->
                    val firstTagId = doc.tags.firstOrNull()
                    val tag = firstTagId?.let { tagMap[it] }

                    RecentDocument(
                        id = doc.id,
                        title = doc.title,
                        timeAgo = formatTimeAgo(doc.added),
                        tagName = tag?.name,
                        tagColor = tag?.color?.let { parseColorToLong(it) }
                    )
                }

                _uiState.update { currentState ->
                    currentState.copy(
                        recentDocuments = recentDocs,
                        isLoading = false
                    )
                }
            }
        }
    }

    /**
     * BEST PRACTICE: Reactive Flow with multi-table observation.
     * Uses @RawQuery with observedEntities=[CachedTask, CachedDocument]
     * to ensure immediate UI updates when documents are deleted.
     *
     * The magic happens at DAO level:
     * - observeUnacknowledgedTasksExcludingDeleted() uses @RawQuery
     * - observedEntities tells Room to watch BOTH tables
     * - When document.isDeleted changes, Room automatically re-emits Flow
     * - No manual refresh, no navigation required!
     *
     * This is THE correct way to do reactive multi-table queries in Room.
     */
    private fun observeProcessingTasksReactively() {
        viewModelScope.launch {
            taskRepository.observeUnacknowledgedTasksExcludingDeleted().collect { tasks ->
                val processingTasks = tasks
                    // Only show document processing tasks, not system tasks like train_classifier
                    .filter { task -> task.taskFileName != null }
                    .map { task ->
                        ProcessingTask(
                            id = task.id,
                            taskId = task.taskId,
                            fileName = task.taskFileName ?: context.getString(R.string.document_unknown),
                            status = mapTaskStatus(task.status),
                            timeAgo = formatTimeAgo(task.dateCreated),
                            resultMessage = task.result,
                            documentId = task.relatedDocument?.toIntOrNull()
                        )
                    }
                    .sortedByDescending { it.id }

                // Count active tasks for Hero Card (unlimited count)
                val activeTasksCount = processingTasks.count {
                    it.status == TaskStatus.PENDING || it.status == TaskStatus.PROCESSING
                }

                // Track newly completed tasks for document sync
                val previousTasks = _uiState.value.processingTasks
                syncCompletedDocuments(previousTasks, processingTasks)

                _uiState.update { currentState ->
                    currentState.copy(
                        processingTasks = processingTasks,
                        allProcessingTasksCount = activeTasksCount,
                        isLoading = false
                    )
                }

                // Start/stop polling based on task status
                if (activeTasksCount > 0) {
                    startTaskPolling()
                } else {
                    stopTaskPolling()
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
            documentRepository.observeUntaggedDocumentsCount().collect { count ->
                _uiState.update { it.copy(untaggedCount = count) }
            }
        }
    }

    /**
     * BEST PRACTICE: Reactive Flow for trash count.
     * Automatically updates UI when documents are deleted/restored.
     */
    private fun observeDeletedCountReactively() {
        viewModelScope.launch {
            documentRepository.observeTrashedDocumentsCount().collect { count ->
                _uiState.update { it.copy(deletedCount = count) }
            }
        }
    }

    /**
     * BEST PRACTICE: Reactive Flow for oldest deleted document timestamp.
     * Automatically updates UI for "Expires in X days" countdown on TrashCard.
     */
    private fun observeOldestDeletedTimestampReactively() {
        viewModelScope.launch {
            documentRepository.observeOldestDeletedTimestamp().collect { timestamp ->
                _uiState.update { it.copy(oldestDeletedTimestamp = timestamp) }
            }
        }
    }

    /**
     * BEST PRACTICE: Reactive Flow for active uploads count.
     * Automatically updates UI when uploads are queued/completed.
     * Used by Hero Card to show processing progress.
     */
    private fun observeActiveUploadsCountReactively() {
        viewModelScope.launch {
            uploadQueueRepository.pendingCount.collect { count ->
                _uiState.update { it.copy(activeUploadsCount = count) }
            }
        }
    }

    /**
     * BEST PRACTICE: Reactive Flow for failed sync count.
     * Automatically updates UI when sync operations fail/succeed.
     * Used by Stats Row to show red badge.
     */
    private fun observeFailedSyncCountReactively() {
        viewModelScope.launch {
            syncHistoryRepository.observeFailedCount().collect { count ->
                _uiState.update { it.copy(failedSyncCount = count) }
            }
        }
    }

    fun loadDashboardData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val stats = loadStats()

            // Force refresh tasks from API to populate cache (triggers reactive Flow update)
            taskRepository.getTasks(forceRefresh = true)

            // Refresh untagged count from API
            var untaggedCount = 0
            documentRepository.getUntaggedCount().onSuccess { count ->
                untaggedCount = count
            }

            // Refresh recent documents from API (updates Room cache, triggers reactive Flow)
            documentRepository.getDocuments(
                page = 1,
                pageSize = 10,
                ordering = "-added",
                forceRefresh = true
            )

            // Sync trash documents (page 1 for quick init, full sync on pull-to-refresh)
            documentRepository.getTrashDocuments(page = 1, pageSize = 100)

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

    private var pollingJob: Job? = null

    private fun startTaskPolling() {
        // Cancel existing job if any
        if (pollingJob?.isActive == true) return

        pollingJob = viewModelScope.launch {
            while (isActive) {
                delay(POLLING_INTERVAL_MS)

                // Refresh tasks from API (triggers reactive Flow update automatically)
                taskRepository.getTasks(forceRefresh = true)

                // Refresh stats when polling (in case new documents were created)
                val stats = loadStats()
                _uiState.update { it.copy(stats = stats) }

                // Stop polling if no more pending tasks
                val currentTasks = _uiState.value.processingTasks
                if (currentTasks.none { it.status == TaskStatus.PENDING || it.status == TaskStatus.PROCESSING }) {
                    break
                }
            }
        }
    }

    /**
     * Sync completed documents to local DB so they appear in Recent Documents automatically.
     * When a task changes from PROCESSING → SUCCESS and has a documentId,
     * fetch the document from API and save to local DB.
     */
    private suspend fun syncCompletedDocuments(
        previousTasks: List<ProcessingTask>,
        currentTasks: List<ProcessingTask>
    ) {
        // Find tasks that just completed successfully
        val newlyCompletedTasks = currentTasks.filter { currentTask ->
            currentTask.status == TaskStatus.SUCCESS &&
            currentTask.documentId != null &&
            previousTasks.none { prevTask ->
                prevTask.taskId == currentTask.taskId && prevTask.status == TaskStatus.SUCCESS
            }
        }

        // Fetch and cache each completed document
        newlyCompletedTasks.forEach { task ->
            task.documentId?.let { docId ->
                logger.log(Level.INFO, "Syncing completed document $docId to local DB")
                documentRepository.getDocument(docId, forceRefresh = true)
                    .onSuccess {
                        logger.log(Level.INFO, "Document $docId synced successfully")
                    }
                    .onFailure { error ->
                        logger.log(Level.WARNING, "Failed to sync document $docId: ${error.message}")
                    }
            }
        }
    }

    private fun stopTaskPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun refreshTasks() {
        viewModelScope.launch {
            // Force refresh tasks from API (triggers reactive Flow update automatically)
            taskRepository.getTasks(forceRefresh = true)

            // Polling will be started/stopped automatically by observeProcessingTasksReactively()
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

            // Refresh processing tasks from API (triggers reactive Flow update automatically)
            taskRepository.getTasks(forceRefresh = true)

            // Refresh untagged count from API
            documentRepository.getUntaggedCount().onSuccess { count ->
                _uiState.update { it.copy(untaggedCount = count) }
            }

            // Refresh recent documents from API (updates Room cache, triggers reactive Flow)
            documentRepository.getDocuments(
                page = 1,
                pageSize = 10,
                ordering = "-added",
                forceRefresh = true
            )

            // Full trash sync: fetch all pages and cleanup orphans
            syncTrashDocuments()

            // Polling will be started/stopped automatically by observeProcessingTasksReactively()
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
            documentRepository.getTrashDocuments(page = page, pageSize = 100)
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
            documentRepository.cleanupOrphanedTrashDocs(serverTrashIds)
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

    fun acknowledgeTask(taskId: Int) {
        // Optimistic update - remove task from UI immediately
        _uiState.update { state ->
            state.copy(processingTasks = state.processingTasks.filter { it.id != taskId })
        }

        // Then acknowledge on server
        viewModelScope.launch {
            taskRepository.acknowledgeTasks(listOf(taskId))
                .onSuccess {
                    logger.log(Level.FINE, "Task $taskId acknowledged successfully")
                }
                .onFailure { error ->
                    logger.log(Level.WARNING, "Failed to acknowledge task $taskId: ${error.message}")
                }
        }
    }

    /**
     * Toggle showing all processing tasks vs. limited display.
     */
    fun toggleShowAllProcessingTasks() {
        _uiState.update { state ->
            state.copy(showAllProcessingTasks = !state.showAllProcessingTasks)
        }
    }

    private suspend fun loadStats(forceRefresh: Boolean = true): DocumentStat {
        val pendingCount = pendingChangesCount.value // Use live flow value
        var totalDocuments = 0
        var thisMonth = 0

        // BEST PRACTICE: Always fetch stats from server (forceRefresh = true by default)
        // to ensure accurate counts in multi-client scenarios (web + mobile)
        documentRepository.getDocumentCount(forceRefresh = forceRefresh).onSuccess { count ->
            totalDocuments = count
        }

        // Get this month's document count
        documentRepository.getDocuments(
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


    private fun mapTaskStatus(status: String): TaskStatus {
        return when (status) {
            PaperlessTask.STATUS_PENDING -> TaskStatus.PENDING
            PaperlessTask.STATUS_STARTED -> TaskStatus.PROCESSING
            PaperlessTask.STATUS_SUCCESS -> TaskStatus.SUCCESS
            PaperlessTask.STATUS_FAILURE -> TaskStatus.FAILURE
            else -> TaskStatus.PENDING
        }
    }

    private fun formatTimeAgo(dateString: String): String {
        return try {
            // Try parsing with timezone offset first (API format: "2026-01-03T10:05:00.156005+01:00")
            // Then fall back to local datetime format for tests
            val localDateTime = try {
                val zonedDateTime = ZonedDateTime.parse(dateString, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                zonedDateTime.withZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime()
            } catch (e: Exception) {
                // Fallback for datetime without timezone (test format: "2026-01-03T10:05:00")
                LocalDateTime.parse(dateString, DateTimeFormatter.ISO_LOCAL_DATE_TIME)
            }

            val now = LocalDateTime.now()
            val duration = Duration.between(localDateTime, now)

            val diffMinutes = duration.toMinutes()
            val diffHours = duration.toHours()
            val diffDays = duration.toDays()

            when {
                diffMinutes < 1 -> context.getString(R.string.time_just_now)
                diffMinutes < 60 -> context.getString(R.string.time_minutes_ago, diffMinutes.toInt())
                diffHours < 24 -> context.getString(R.string.time_hours_ago, diffHours.toInt())
                diffDays < 7 -> context.getString(R.string.time_days_ago, diffDays.toInt())
                else -> {
                    val outputFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
                    localDateTime.format(outputFormatter)
                }
            }
        } catch (e: Exception) {
            context.getString(R.string.time_unknown)
        }
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
        stopTaskPolling()
        _uiState.update { HomeUiState() }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    private fun startNetworkMonitoring() {
        viewModelScope.launch {
            networkMonitor.isOnline.collect { currentlyOnline ->
                // Track network status changes
                analyticsService.trackEvent(AnalyticsEvent.NetworkStatusChanged(isOnline = currentlyOnline))

                if (!currentlyOnline) {
                    analyticsService.trackEvent(AnalyticsEvent.OfflineModeUsed)
                }

                // Auto-refresh when coming back online
                if (currentlyOnline && wasOffline) {
                    logger.log(Level.INFO, "Network reconnected - auto-refreshing data")
                    loadDashboardData()
                }

                wasOffline = !currentlyOnline
            }
        }
    }

    private fun observePendingUploads() {
        viewModelScope.launch {
            pendingChangesCount.collect { count ->
                // Update stats with new pending count
                _uiState.update { currentState ->
                    currentState.copy(
                        stats = currentState.stats.copy(pendingUploads = count)
                    )
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopTaskPolling()
    }

    // ==================== TAG SUGGESTIONS SHEET ====================

    fun openTagSuggestionsSheet() {
        _showTagSuggestionsSheet.value = true
        loadUntaggedDocuments()
    }

    /**
     * Load untagged documents for the SmartTaggingScreen.
     * This is called when navigating to the full-screen tagging experience.
     */
    fun loadUntaggedDocumentsForScreen() {
        loadUntaggedDocuments()
    }

    fun closeTagSuggestionsSheet() {
        _showTagSuggestionsSheet.value = false
        _tagSuggestionsState.value = TagSuggestionsState()
    }

    private fun loadUntaggedDocuments() {
        viewModelScope.launch {
            _tagSuggestionsState.update { it.copy(isLoading = true) }

            // Get ALL untagged documents from local cache (no limit)
            documentRepository.getUntaggedDocuments().onSuccess { documents ->
                val serverUrl = tokenManager.serverUrl.first() ?: ""
                val authToken = tokenManager.token.first() ?: ""

                // Map to UI model
                val untaggedDocs = documents.map { doc ->
                    UntaggedDocument(
                        id = doc.id,
                        title = doc.title,
                        thumbnailUrl = if (serverUrl.isNotEmpty() && authToken.isNotEmpty()) {
                            "$serverUrl/api/documents/${doc.id}/thumb/"
                        } else null,
                        analysisState = UntaggedDocAnalysisState.Idle
                    )
                }

                _tagSuggestionsState.update {
                    it.copy(
                        documents = untaggedDocs,
                        isLoading = false
                    )
                }

                // Pre-load thumbnails for all documents in background
                if (serverUrl.isNotEmpty() && authToken.isNotEmpty()) {
                    untaggedDocs.forEach { doc ->
                        loadThumbnailForDocument(doc.id, serverUrl, authToken)
                    }
                }
            }.onFailure { error ->
                logger.log(Level.WARNING, "Failed to load untagged documents: ${error.message}")
                _tagSuggestionsState.update {
                    it.copy(isLoading = false)
                }
            }
        }
    }

    private fun loadThumbnailForDocument(documentId: Int, serverUrl: String, authToken: String) {
        viewModelScope.launch {
            val thumbnailUrl = "$serverUrl/api/documents/$documentId/thumb/"
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    val connection = URL(thumbnailUrl).openConnection()
                    connection.setRequestProperty("Authorization", "Token $authToken")
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000
                    connection.getInputStream().use { inputStream ->
                        BitmapFactory.decodeStream(inputStream)
                    }
                } catch (e: Exception) {
                    logger.log(Level.WARNING, "Failed to load thumbnail for doc $documentId: ${e.message}")
                    null
                }
            }

            if (bitmap != null) {
                _tagSuggestionsState.update { state ->
                    state.copy(
                        documents = state.documents.map { doc ->
                            if (doc.id == documentId) {
                                doc.copy(thumbnailBitmap = bitmap)
                            } else doc
                        }
                    )
                }
            }
        }
    }

    fun analyzeDocument(documentId: Int) {
        viewModelScope.launch {
            // Update state to loading
            updateDocumentState(documentId, UntaggedDocAnalysisState.LoadingThumbnail)

            try {
                val serverUrl = tokenManager.serverUrl.first() ?: ""
                val authToken = tokenManager.token.first() ?: ""

                if (serverUrl.isEmpty() || authToken.isEmpty()) {
                    updateDocumentState(documentId, UntaggedDocAnalysisState.Error(
                        context.getString(R.string.error_not_authenticated)
                    ))
                    return@launch
                }

                // Download thumbnail for AI analysis
                val thumbnailUrl = "$serverUrl/api/documents/$documentId/thumb/"
                val bitmap = withContext(Dispatchers.IO) {
                    try {
                        val connection = URL(thumbnailUrl).openConnection()
                        connection.setRequestProperty("Authorization", "Token $authToken")
                        connection.connectTimeout = 10000
                        connection.readTimeout = 10000
                        connection.getInputStream().use { inputStream ->
                            BitmapFactory.decodeStream(inputStream)
                        }
                    } catch (e: Exception) {
                        logger.log(Level.WARNING, "Failed to download thumbnail: ${e.message}")
                        null
                    }
                }

                if (bitmap == null) {
                    updateDocumentState(documentId, UntaggedDocAnalysisState.Error(
                        context.getString(R.string.tag_suggestions_error_thumbnail)
                    ))
                    return@launch
                }

                // Update document with bitmap and change state to analyzing
                _tagSuggestionsState.update { state ->
                    state.copy(
                        documents = state.documents.map { doc ->
                            if (doc.id == documentId) {
                                doc.copy(
                                    thumbnailBitmap = bitmap,
                                    analysisState = UntaggedDocAnalysisState.Analyzing
                                )
                            } else doc
                        }
                    )
                }

                // Run AI analysis using the orchestrator
                val result = suggestionOrchestrator.getSuggestions(
                    bitmap = bitmap,
                    documentId = documentId
                )

                when (result) {
                    is SuggestionResult.Success -> {
                        // Separate suggested tags into existing and new
                        val existingTags = tagMap.values.toList()

                        // Find tags that exist in the system and pre-select them
                        val preSelectedIds = result.analysis.suggestedTags
                            .mapNotNull { suggestion ->
                                suggestion.tagId ?: existingTags.find {
                                    it.name.equals(suggestion.tagName, ignoreCase = true)
                                }?.id
                            }
                            .toSet()

                        // Find tags that DON'T exist in the system (new tag suggestions)
                        val newTagSuggestions = result.analysis.suggestedTags
                            .filter { suggestion ->
                                val hasTagId = suggestion.tagId != null
                                val existsInSystem = existingTags.any {
                                    it.name.equals(suggestion.tagName, ignoreCase = true)
                                }
                                !hasTagId && !existsInSystem
                            }

                        _tagSuggestionsState.update { state ->
                            state.copy(
                                documents = state.documents.map { doc ->
                                    if (doc.id == documentId) {
                                        doc.copy(
                                            analysisState = UntaggedDocAnalysisState.Success(result.analysis),
                                            suggestions = result.analysis,
                                            selectedTagIds = preSelectedIds,
                                            suggestedNewTags = newTagSuggestions
                                        )
                                    } else doc
                                }
                            )
                        }

                        analyticsService.trackEvent(AnalyticsEvent.AiFeatureUsed(
                            featureType = "tag_suggestions_home",
                            inputTokens = 0, // Not tracked at this level
                            outputTokens = 0,
                            estimatedCostUsd = 0.0,
                            subscriptionType = "unknown",
                            success = true
                        ))
                    }
                    is SuggestionResult.Error -> {
                        updateDocumentState(documentId, UntaggedDocAnalysisState.Error(result.message))
                    }
                    is SuggestionResult.Loading -> {
                        // Already in loading state, no action needed
                    }
                    is SuggestionResult.WiFiRequired -> {
                        // WiFi-only restriction does not apply to HomeScreen analysis
                        // (documents are already uploaded, only analyzing existing thumbnails)
                        // This case should not occur here, but handle gracefully
                        logger.log(Level.WARNING, "WiFiRequired in HomeViewModel - should not happen")
                        updateDocumentState(documentId, UntaggedDocAnalysisState.Idle)
                    }
                }
            } catch (e: Exception) {
                logger.log(Level.WARNING, "Error analyzing document $documentId: ${e.message}")
                updateDocumentState(documentId, UntaggedDocAnalysisState.Error(
                    e.message ?: context.getString(R.string.error_unknown)
                ))
            }
        }
    }

    private fun updateDocumentState(documentId: Int, state: UntaggedDocAnalysisState) {
        _tagSuggestionsState.update { currentState ->
            currentState.copy(
                documents = currentState.documents.map { doc ->
                    if (doc.id == documentId) {
                        doc.copy(analysisState = state)
                    } else doc
                }
            )
        }
    }

    fun applyTagsToDocument(documentId: Int, tagIds: List<Int>) {
        viewModelScope.launch {
            try {
                // Update document via repository
                documentRepository.updateDocument(documentId, tags = tagIds)
                    .onSuccess {
                        logger.log(Level.INFO, "Applied tags $tagIds to document $documentId")

                        // Mark as tagged and increment counter
                        _tagSuggestionsState.update { state ->
                            state.copy(
                                documents = state.documents.map { doc ->
                                    if (doc.id == documentId) {
                                        doc.copy(isTagged = true)
                                    } else doc
                                },
                                taggedCount = state.taggedCount + 1
                            )
                        }

                        analyticsService.trackEvent(AnalyticsEvent.AiSuggestionAccepted(
                            featureType = "tag_suggestions_home",
                            suggestionCount = tagIds.size
                        ))
                    }
                    .onFailure { error ->
                        logger.log(Level.WARNING, "Failed to apply tags: ${error.message}")
                        updateDocumentState(documentId, UntaggedDocAnalysisState.Error(
                            error.message ?: context.getString(R.string.error_unknown)
                        ))
                    }
            } catch (e: Exception) {
                logger.log(Level.WARNING, "Error applying tags: ${e.message}")
            }
        }
    }

    fun skipDocument(documentId: Int) {
        _tagSuggestionsState.update { state ->
            state.copy(
                documents = state.documents.map { doc ->
                    if (doc.id == documentId) {
                        doc.copy(isSkipped = true)
                    } else doc
                }
            )
        }
    }

    // Tag Picker methods
    fun openTagPicker(documentId: Int) {
        _tagSuggestionsState.update {
            it.copy(showTagPicker = true, tagPickerDocumentId = documentId)
        }
    }

    fun closeTagPicker() {
        _tagSuggestionsState.update {
            it.copy(showTagPicker = false, tagPickerDocumentId = null)
        }
    }

    fun toggleTagInPicker(documentId: Int, tagId: Int) {
        _tagSuggestionsState.update { state ->
            state.copy(
                documents = state.documents.map { doc ->
                    if (doc.id == documentId) {
                        val newSelectedIds = if (doc.selectedTagIds.contains(tagId)) {
                            doc.selectedTagIds - tagId
                        } else {
                            doc.selectedTagIds + tagId
                        }
                        doc.copy(selectedTagIds = newSelectedIds)
                    } else doc
                }
            )
        }
    }

    fun applyPickerTags(documentId: Int) {
        val document = _tagSuggestionsState.value.documents.find { it.id == documentId }
        if (document != null && document.selectedTagIds.isNotEmpty()) {
            applyTagsToDocument(documentId, document.selectedTagIds.toList())
            closeTagPicker()
        }
    }

    // ==================== TAG CREATION ====================

    fun createTag(name: String, color: String? = null) {
        viewModelScope.launch {
            _createTagState.update { CreateTagState.Creating }

            tagRepository.createTag(name = name, color = color)
                .onSuccess { newTag ->
                    logger.log(Level.INFO, "Tag created: ${newTag.name}")
                    analyticsService.trackEvent(AnalyticsEvent.TagCreated)
                    // BEST PRACTICE: Tag list updates automatically via observeTagsReactively()
                    _createTagState.update { CreateTagState.Success(newTag) }
                }
                .onFailure { e ->
                    logger.log(Level.WARNING, "Failed to create tag: ${e.message}")
                    // Handle duplicate tag error
                    if (e.message?.contains("unique constraint") == true ||
                        e.message?.contains("already exists") == true) {
                        // Try to find existing tag
                        val existingTag = tagRepository.observeTags().first()
                            .find { it.name.equals(name, ignoreCase = true) }
                        if (existingTag != null) {
                            _createTagState.update { CreateTagState.Success(existingTag) }
                        } else {
                            _createTagState.update { CreateTagState.Error(
                                context.getString(R.string.error_create_tag)
                            ) }
                        }
                    } else {
                        _createTagState.update { CreateTagState.Error(
                            e.message ?: context.getString(R.string.error_create_tag)
                        ) }
                    }
                }
        }
    }

    fun resetCreateTagState() {
        _createTagState.update { CreateTagState.Idle }
    }

    /**
     * Creates a suggested new tag and automatically selects it for the specified document.
     * Also removes it from the suggestedNewTags list.
     */
    fun createSuggestedTag(documentId: Int, tagName: String) {
        viewModelScope.launch {
            _createTagState.update { CreateTagState.Creating }

            tagRepository.createTag(name = tagName, color = null)
                .onSuccess { newTag ->
                    logger.log(Level.INFO, "Suggested tag created: ${newTag.name}")
                    analyticsService.trackEvent(AnalyticsEvent.TagCreated)

                    // Auto-select the newly created tag for this document
                    // and remove it from suggestedNewTags
                    _tagSuggestionsState.update { state ->
                        state.copy(
                            documents = state.documents.map { doc ->
                                if (doc.id == documentId) {
                                    doc.copy(
                                        selectedTagIds = doc.selectedTagIds + newTag.id,
                                        suggestedNewTags = doc.suggestedNewTags.filter {
                                            !it.tagName.equals(tagName, ignoreCase = true)
                                        }
                                    )
                                } else doc
                            }
                        )
                    }

                    _createTagState.update { CreateTagState.Success(newTag) }
                }
                .onFailure { e ->
                    logger.log(Level.WARNING, "Failed to create suggested tag: ${e.message}")
                    // Handle duplicate tag error - tag might have been created meanwhile
                    if (e.message?.contains("unique constraint") == true ||
                        e.message?.contains("already exists") == true) {
                        val existingTag = tagRepository.observeTags().first()
                            .find { it.name.equals(tagName, ignoreCase = true) }
                        if (existingTag != null) {
                            // Auto-select the existing tag
                            _tagSuggestionsState.update { state ->
                                state.copy(
                                    documents = state.documents.map { doc ->
                                        if (doc.id == documentId) {
                                            doc.copy(
                                                selectedTagIds = doc.selectedTagIds + existingTag.id,
                                                suggestedNewTags = doc.suggestedNewTags.filter {
                                                    !it.tagName.equals(tagName, ignoreCase = true)
                                                }
                                            )
                                        } else doc
                                    }
                                )
                            }
                            _createTagState.update { CreateTagState.Success(existingTag) }
                        } else {
                            _createTagState.update { CreateTagState.Error(
                                context.getString(R.string.error_create_tag)
                            ) }
                        }
                    } else {
                        _createTagState.update { CreateTagState.Error(
                            e.message ?: context.getString(R.string.error_create_tag)
                        ) }
                    }
                }
        }
    }
}
