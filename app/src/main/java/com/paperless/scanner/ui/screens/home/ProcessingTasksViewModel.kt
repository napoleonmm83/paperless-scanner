package com.paperless.scanner.ui.screens.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.R
import com.paperless.scanner.data.repository.DocumentMetadataRepository
import com.paperless.scanner.data.repository.TaskRepository
import com.paperless.scanner.domain.model.PaperlessTask
import com.paperless.scanner.util.asUiResult
import com.paperless.scanner.util.formatTimeAgo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject

data class ProcessingTasksUiState(
    val tasks: List<ProcessingTask> = emptyList(),
    /** Count of PENDING/PROCESSING tasks — drives Hero Card progress indicator. */
    val activeCount: Int = 0,
    val showAll: Boolean = false,
) {
    companion object {
        const val DISPLAY_LIMIT = 10
    }

    val hiddenCount: Int
        get() = if (showAll) 0 else maxOf(0, tasks.size - DISPLAY_LIMIT)

    val displayed: List<ProcessingTask>
        get() = if (showAll) tasks else tasks.take(DISPLAY_LIMIT)

    val completedCount: Int
        get() = tasks.count { it.status == TaskStatus.SUCCESS || it.status == TaskStatus.FAILURE }

    val hasCompleted: Boolean
        get() = completedCount > 0
}

sealed class ProcessingTasksError {
    data class LoadFailed(val source: String, val cause: Throwable) : ProcessingTasksError()
}

/**
 * Owns Paperless processing-task observation, polling, and acknowledgment for HomeScreen.
 *
 * Phase 2 of the [HomeViewModel] god-VM decomposition (issue #72).
 *
 * Coordinator hook: [pollingTick] fires on every polling iteration so
 * [HomeViewModel] can refresh dashboard stats (totalDocuments / thisMonth)
 * while uploads complete. The two ViewModels share no direct reference.
 */
@HiltViewModel
class ProcessingTasksViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val taskRepository: TaskRepository,
    private val documentMetadataRepository: DocumentMetadataRepository,
) : ViewModel() {

    companion object {
        private val logger = Logger.getLogger(ProcessingTasksViewModel::class.java.name)
        private const val POLLING_INTERVAL_MS = 3000L
    }

    private val _uiState = MutableStateFlow(ProcessingTasksUiState())
    val uiState: StateFlow<ProcessingTasksUiState> = _uiState.asStateFlow()

    private val _error = MutableStateFlow<ProcessingTasksError?>(null)
    val error: StateFlow<ProcessingTasksError?> = _error.asStateFlow()

    private val _pollingTick = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val pollingTick: SharedFlow<Unit> = _pollingTick.asSharedFlow()

    private var pollingJob: Job? = null

    init {
        observeProcessingTasksReactively()
        // Initial HTTP sync — Room cache may be stale from a previous session;
        // mirrors the pre-extraction loadDashboardData() behavior.
        refreshTasks()
    }

    /**
     * Reactive Room flow over the unacknowledged-tasks-excluding-deleted view.
     * Triggers per-task syncCompletedDocuments() for SUCCESS transitions and
     * starts/stops the polling job based on active-task presence.
     */
    private fun observeProcessingTasksReactively() {
        viewModelScope.launch {
            taskRepository.observeUnacknowledgedTasksExcludingDeleted()
                .asUiResult()
                .collect { result ->
                    result.onSuccess { tasks ->
                        val processingTasks = tasks
                            .filter { task -> task.taskFileName != null }
                            .map { task ->
                                ProcessingTask(
                                    id = task.id,
                                    taskId = task.taskId,
                                    fileName = task.taskFileName ?: context.getString(R.string.document_unknown),
                                    status = mapTaskStatus(task.status),
                                    timeAgo = formatTimeAgo(context, task.dateCreated),
                                    resultMessage = task.result,
                                    documentId = task.relatedDocument?.toIntOrNull()
                                )
                            }
                            .sortedByDescending { it.id }

                        val activeTasksCount = processingTasks.count {
                            it.status == TaskStatus.PENDING || it.status == TaskStatus.PROCESSING
                        }

                        val previousTasks = _uiState.value.tasks
                        syncCompletedDocuments(previousTasks, processingTasks)

                        _uiState.update { it.copy(tasks = processingTasks, activeCount = activeTasksCount) }

                        if (activeTasksCount > 0) startTaskPolling() else stopTaskPolling()
                    }.onFailure { e ->
                        _error.value = ProcessingTasksError.LoadFailed("processingTasks", e)
                    }
                }
        }
    }

    private fun startTaskPolling() {
        if (pollingJob?.isActive == true) return

        pollingJob = viewModelScope.launch {
            while (isActive) {
                delay(POLLING_INTERVAL_MS)

                taskRepository.getTasks(forceRefresh = true)

                // Coordinator hook: let HomeViewModel refresh dashboard stats while
                // tasks are completing (totalDocuments / thisMonth may have changed).
                _pollingTick.tryEmit(Unit)

                val currentTasks = _uiState.value.tasks
                if (currentTasks.none { it.status == TaskStatus.PENDING || it.status == TaskStatus.PROCESSING }) {
                    break
                }
            }
        }
    }

    private fun stopTaskPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    /**
     * When a task transitions to SUCCESS and has a documentId, fetch the document
     * so it appears in Recent Documents without waiting for a manual refresh.
     */
    private suspend fun syncCompletedDocuments(
        previousTasks: List<ProcessingTask>,
        currentTasks: List<ProcessingTask>,
    ) {
        val newlyCompletedTasks = currentTasks.filter { currentTask ->
            currentTask.status == TaskStatus.SUCCESS &&
                currentTask.documentId != null &&
                previousTasks.none { prev ->
                    prev.taskId == currentTask.taskId && prev.status == TaskStatus.SUCCESS
                }
        }

        newlyCompletedTasks.forEach { task ->
            task.documentId?.let { docId ->
                logger.log(Level.INFO, "Syncing completed document $docId to local DB")
                documentMetadataRepository.getDocument(docId, forceRefresh = true)
                    .onSuccess { logger.log(Level.INFO, "Document $docId synced successfully") }
                    .onFailure { error ->
                        logger.log(Level.WARNING, "Failed to sync document $docId: ${error.message}")
                    }
            }
        }
    }

    fun refreshTasks() {
        viewModelScope.launch {
            taskRepository.getTasks(forceRefresh = true)
            // Polling start/stop is handled by observeProcessingTasksReactively().
        }
    }

    fun acknowledgeTask(taskId: Int) {
        // Optimistic update — remove from UI before the server call returns.
        _uiState.update { state ->
            val updatedTasks = state.tasks.filter { it.id != taskId }
            val acknowledgedTask = state.tasks.find { it.id == taskId }
            val wasActive = acknowledgedTask?.let {
                it.status == TaskStatus.PENDING || it.status == TaskStatus.PROCESSING
            } ?: false
            state.copy(
                tasks = updatedTasks,
                activeCount = if (wasActive) maxOf(0, state.activeCount - 1) else state.activeCount,
                showAll = if (updatedTasks.size <= ProcessingTasksUiState.DISPLAY_LIMIT) false else state.showAll,
            )
        }

        viewModelScope.launch {
            taskRepository.acknowledgeTasks(listOf(taskId))
                .onSuccess { logger.log(Level.FINE, "Task $taskId acknowledged successfully") }
                .onFailure { error ->
                    logger.log(Level.WARNING, "Failed to acknowledge task $taskId: ${error.message}")
                }
        }
    }

    fun acknowledgeCompletedTasks() {
        val completedTasks = _uiState.value.tasks.filter {
            it.status == TaskStatus.SUCCESS || it.status == TaskStatus.FAILURE
        }

        if (completedTasks.isEmpty()) {
            logger.log(Level.FINE, "No completed tasks to acknowledge")
            return
        }

        val taskIds = completedTasks.map { it.id }
        logger.log(Level.INFO, "Acknowledging ${taskIds.size} completed tasks: $taskIds")

        _uiState.update { state ->
            val remainingTasks = state.tasks.filter {
                it.status != TaskStatus.SUCCESS && it.status != TaskStatus.FAILURE
            }
            state.copy(
                tasks = remainingTasks,
                showAll = if (remainingTasks.size <= ProcessingTasksUiState.DISPLAY_LIMIT) false else state.showAll,
            )
        }

        viewModelScope.launch {
            taskRepository.acknowledgeTasks(taskIds)
                .onSuccess { logger.log(Level.INFO, "Successfully acknowledged ${taskIds.size} tasks") }
                .onFailure { error ->
                    logger.log(Level.WARNING, "Failed to acknowledge tasks: ${error.message}")
                    refreshTasks()
                }
        }
    }

    fun toggleShowAll() {
        _uiState.update { it.copy(showAll = !it.showAll) }
    }

    fun resetState() {
        stopTaskPolling()
        _uiState.value = ProcessingTasksUiState()
    }

    fun clearError() {
        _error.value = null
    }

    override fun onCleared() {
        super.onCleared()
        stopTaskPolling()
    }

    private fun mapTaskStatus(status: String): TaskStatus = when (status) {
        PaperlessTask.STATUS_PENDING -> TaskStatus.PENDING
        PaperlessTask.STATUS_STARTED -> TaskStatus.PROCESSING
        PaperlessTask.STATUS_SUCCESS -> TaskStatus.SUCCESS
        PaperlessTask.STATUS_FAILURE -> TaskStatus.FAILURE
        else -> TaskStatus.PENDING
    }
}
