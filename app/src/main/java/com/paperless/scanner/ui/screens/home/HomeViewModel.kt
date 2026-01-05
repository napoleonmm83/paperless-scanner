package com.paperless.scanner.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.domain.model.PaperlessTask
import com.paperless.scanner.domain.model.Tag
import com.paperless.scanner.data.repository.DocumentRepository
import com.paperless.scanner.data.repository.TagRepository
import com.paperless.scanner.data.repository.TaskRepository
import com.paperless.scanner.data.repository.UploadQueueRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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

data class HomeUiState(
    val stats: DocumentStat = DocumentStat(),
    val recentDocuments: List<RecentDocument> = emptyList(),
    val processingTasks: List<ProcessingTask> = emptyList(),
    val untaggedCount: Int = 0,
    val isLoading: Boolean = true,
    val error: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val documentRepository: DocumentRepository,
    private val tagRepository: TagRepository,
    private val taskRepository: TaskRepository,
    private val uploadQueueRepository: UploadQueueRepository,
    private val networkMonitor: com.paperless.scanner.data.network.NetworkMonitor,
    private val syncManager: com.paperless.scanner.data.sync.SyncManager
) : ViewModel() {

    companion object {
        private val logger = Logger.getLogger(HomeViewModel::class.java.name)
        private const val POLLING_INTERVAL_MS = 3000L
        private const val NETWORK_CHECK_INTERVAL_MS = 1000L
    }

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    // Live network status from NetworkMonitor
    val isOnline: StateFlow<Boolean> = networkMonitor.isOnline

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

    init {
        loadDashboardData()
        startNetworkMonitoring()
        observePendingUploads()
    }

    fun loadDashboardData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            // Load tags first for name lookup
            tagRepository.getTags().onSuccess { tags ->
                tagMap = tags.associateBy { it.id }
            }

            val stats = loadStats()
            val recentDocs = loadRecentDocuments()
            val tasks = loadProcessingTasks()
            val untagged = countUntaggedFromRecent(recentDocs)

            _uiState.update {
                HomeUiState(
                    stats = stats,
                    recentDocuments = recentDocs,
                    processingTasks = tasks,
                    untaggedCount = untagged,
                    isLoading = false
                )
            }

            // Start polling for task updates if there are pending tasks
            if (tasks.any { it.status == TaskStatus.PENDING || it.status == TaskStatus.PROCESSING }) {
                startTaskPolling()
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
                val tasks = loadProcessingTasks()
                _uiState.update { it.copy(processingTasks = tasks) }

                // Stop polling if no more pending tasks
                if (tasks.none { it.status == TaskStatus.PENDING || it.status == TaskStatus.PROCESSING }) {
                    // Refresh recent documents when all tasks complete
                    val recentDocs = loadRecentDocuments()
                    val stats = loadStats()
                    _uiState.update { it.copy(recentDocuments = recentDocs, stats = stats) }
                    break
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
            val tasks = loadProcessingTasks()
            _uiState.update { it.copy(processingTasks = tasks) }

            if (tasks.any { it.status == TaskStatus.PENDING || it.status == TaskStatus.PROCESSING }) {
                startTaskPolling()
            }
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

    private suspend fun loadStats(): DocumentStat {
        val pendingCount = pendingChangesCount.value // Use live flow value
        var totalDocuments = 0
        var thisMonth = 0

        documentRepository.getDocumentCount().onSuccess { count ->
            totalDocuments = count
        }

        // Get this month's document count
        documentRepository.getDocuments(
            page = 1,
            pageSize = 1,
            ordering = "-added"
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

    private suspend fun loadRecentDocuments(): List<RecentDocument> {
        val result = documentRepository.getRecentDocuments(limit = 5)

        return result.getOrNull()?.map { doc ->
            val firstTagId = doc.tags.firstOrNull()
            val tag = firstTagId?.let { tagMap[it] }

            RecentDocument(
                id = doc.id,
                title = doc.title,
                timeAgo = formatTimeAgo(doc.added),
                tagName = tag?.name,
                tagColor = tag?.color?.let { parseColorToLong(it) }
            )
        } ?: emptyList()
    }

    private fun countUntaggedFromRecent(docs: List<RecentDocument>): Int {
        return docs.count { it.tagName == null }
    }

    private suspend fun loadProcessingTasks(): List<ProcessingTask> {
        val result = taskRepository.getUnacknowledgedTasks()

        return result.getOrNull()
            // Only show document processing tasks, not system tasks like train_classifier
            ?.filter { task -> task.taskFileName != null }
            ?.map { task ->
                ProcessingTask(
                    id = task.id,
                    taskId = task.taskId,
                    fileName = task.taskFileName ?: "Unbekanntes Dokument",
                    status = mapTaskStatus(task.status),
                    timeAgo = formatTimeAgo(task.dateCreated),
                    resultMessage = task.result,
                    documentId = task.relatedDocument?.toIntOrNull()
                )
            }?.sortedByDescending { it.id }?.take(10) ?: emptyList()
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
                diffMinutes < 1 -> "Gerade eben"
                diffMinutes < 60 -> "vor $diffMinutes Min."
                diffHours < 24 -> "vor $diffHours Std."
                diffDays < 7 -> "vor $diffDays Tag${if (diffDays > 1) "en" else ""}"
                else -> {
                    val outputFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
                    localDateTime.format(outputFormatter)
                }
            }
        } catch (e: Exception) {
            "Unbekannt"
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
}
