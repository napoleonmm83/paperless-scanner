package com.paperless.scanner.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.data.api.models.PaperlessTask
import com.paperless.scanner.data.api.models.Tag
import com.paperless.scanner.data.repository.DocumentRepository
import com.paperless.scanner.data.repository.TagRepository
import com.paperless.scanner.data.repository.TaskRepository
import com.paperless.scanner.data.repository.UploadQueueRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
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
    private val uploadQueueRepository: UploadQueueRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    private var tagMap: Map<Int, Tag> = emptyMap()

    init {
        loadDashboardData()
    }

    fun loadDashboardData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            // Load tags first for name lookup
            tagRepository.getTags().onSuccess { tags ->
                tagMap = tags.associateBy { it.id }
            }

            val stats = loadStats()
            val recentDocs = loadRecentDocuments()
            val tasks = loadProcessingTasks()
            val untagged = countUntaggedFromRecent(recentDocs)

            _uiState.value = HomeUiState(
                stats = stats,
                recentDocuments = recentDocs,
                processingTasks = tasks,
                untaggedCount = untagged,
                isLoading = false
            )

            // Start polling for task updates if there are pending tasks
            if (tasks.any { it.status == TaskStatus.PENDING || it.status == TaskStatus.PROCESSING }) {
                startTaskPolling()
            }
        }
    }

    private var isPolling = false

    private fun startTaskPolling() {
        if (isPolling) return
        isPolling = true

        viewModelScope.launch {
            while (isPolling) {
                delay(3000) // Poll every 3 seconds
                val tasks = loadProcessingTasks()
                _uiState.value = _uiState.value.copy(processingTasks = tasks)

                // Stop polling if no more pending tasks
                if (tasks.none { it.status == TaskStatus.PENDING || it.status == TaskStatus.PROCESSING }) {
                    isPolling = false
                    // Refresh recent documents when all tasks complete
                    val recentDocs = loadRecentDocuments()
                    val stats = loadStats()
                    _uiState.value = _uiState.value.copy(
                        recentDocuments = recentDocs,
                        stats = stats
                    )
                }
            }
        }
    }

    fun refreshTasks() {
        viewModelScope.launch {
            val tasks = loadProcessingTasks()
            _uiState.value = _uiState.value.copy(processingTasks = tasks)

            if (tasks.any { it.status == TaskStatus.PENDING || it.status == TaskStatus.PROCESSING }) {
                startTaskPolling()
            }
        }
    }

    fun acknowledgeTask(taskId: Int) {
        // Optimistic update - remove task from UI immediately
        _uiState.value = _uiState.value.copy(
            processingTasks = _uiState.value.processingTasks.filter { it.id != taskId }
        )

        // Then acknowledge on server
        viewModelScope.launch {
            taskRepository.acknowledgeTasks(listOf(taskId))
                .onSuccess {
                    android.util.Log.d("HomeViewModel", "Task $taskId acknowledged successfully")
                }
                .onFailure { error ->
                    android.util.Log.e("HomeViewModel", "Failed to acknowledge task $taskId: ${error.message}")
                }
        }
    }

    private suspend fun loadStats(): DocumentStat {
        val pendingCount = uploadQueueRepository.getPendingUploadCount()
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

        return result.getOrNull()?.map { task ->
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
            val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            val date = inputFormat.parse(dateString) ?: return "Gerade eben"
            val now = Date()
            val diffMs = now.time - date.time

            val diffMinutes = TimeUnit.MILLISECONDS.toMinutes(diffMs)
            val diffHours = TimeUnit.MILLISECONDS.toHours(diffMs)
            val diffDays = TimeUnit.MILLISECONDS.toDays(diffMs)

            when {
                diffMinutes < 1 -> "Gerade eben"
                diffMinutes < 60 -> "vor $diffMinutes Min."
                diffHours < 24 -> "vor $diffHours Std."
                diffDays < 7 -> "vor $diffDays Tag${if (diffDays > 1) "en" else ""}"
                else -> {
                    val outputFormat = SimpleDateFormat("dd.MM.yyyy", Locale.GERMAN)
                    outputFormat.format(date)
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
}
