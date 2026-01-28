package com.paperless.scanner.ui.screens.trash

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.R
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.data.repository.DocumentRepository
import com.paperless.scanner.util.DateFormatter
import com.paperless.scanner.worker.TrashDeleteWorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI Model for Trash Screen items.
 *
 * @param id Document ID
 * @param title Document title
 * @param deletedAt Formatted date string when document was deleted
 * @param daysUntilAutoDelete Days remaining until auto-delete (e.g., "5 days")
 */
data class TrashDocumentItem(
    val id: Int,
    val title: String,
    val deletedAt: String,
    val daysUntilAutoDelete: String
)

/**
 * State for a document pending permanent deletion with countdown.
 *
 * @param documentId The document ID
 * @param progress Progress from 1.0 (start) to 0.0 (delete)
 * @param secondsRemaining Seconds until deletion
 */
data class PendingDeleteState(
    val documentId: Int,
    val progress: Float = 1f,
    val secondsRemaining: Int = 30
)

/**
 * UI State for Trash Screen.
 */
data class TrashUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val documents: List<TrashDocumentItem> = emptyList(),
    val totalCount: Int = 0,
    val isRestoring: Boolean = false,
    val isDeleting: Boolean = false,
    val pendingDeletes: Map<Int, PendingDeleteState> = emptyMap()
)

/**
 * ViewModel for TrashScreen.
 *
 * BEST PRACTICE: Reactive Flow from Repository.
 * - observeTrashedDocuments() automatically updates UI on any DB change
 * - No manual refresh needed
 * - Lifecycle-aware via stateIn()
 */
@HiltViewModel
class TrashViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val documentRepository: DocumentRepository,
    private val tokenManager: TokenManager,
    private val trashDeleteWorkManager: TrashDeleteWorkManager
) : ViewModel() {

    companion object {
        private const val RETENTION_DAYS = 30 // Default trash retention (same as backend)
        private const val COUNTDOWN_SECONDS = 30
        private const val COUNTDOWN_STEP_MS = 100L
    }

    private val _uiState = MutableStateFlow(TrashUiState())

    // Track countdown jobs by document ID - runs in viewModelScope, independent of UI lifecycle
    private val pendingDeleteJobs = mutableMapOf<Int, Job>()

    // Track start times for pending deletes (for persistence/restore)
    // Map: documentId -> startTimeMillis
    private val pendingDeleteStartTimes = mutableMapOf<Int, Long>()
    val uiState: StateFlow<TrashUiState> = _uiState.asStateFlow()

    /**
     * BEST PRACTICE: Reactive Flow for Trash Documents.
     * Automatically updates when documents are deleted/restored.
     * Uses Room cache (CachedDocument) since trash is offline-first.
     */
    val trashedDocuments: StateFlow<List<TrashDocumentItem>> = documentRepository.observeTrashedDocuments()
        .map { documents ->
            documents.map { doc ->
                TrashDocumentItem(
                    id = doc.id,
                    title = doc.title,
                    deletedAt = DateFormatter.formatDateShort(doc.created),
                    daysUntilAutoDelete = calculateDaysUntilAutoDelete(doc.deletedAt)
                )
            }
        }
        .catch { e ->
            _uiState.update { it.copy(error = e.message, isLoading = false) }
            emit(emptyList())
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    init {
        observeDocuments()
        observeCount()
        refreshTrash() // Load trash from server on init
        restorePendingDeletes() // Restore pending deletes after AppLock
    }

    /**
     * Restore pending deletes from DataStore after AppLock/Process Death.
     * Uses TokenManager which persists independently of NavBackStackEntry lifecycle.
     * Calculates elapsed time and continues countdown from where it left off.
     * If countdown has expired, schedules immediate deletion via WorkManager.
     *
     * WorkManager handles the actual deletion, ensuring it completes even if
     * the user navigates away again.
     */
    private fun restorePendingDeletes() {
        val savedStartTimes: String? = tokenManager.getPendingTrashDeletesSync()
        if (savedStartTimes.isNullOrEmpty()) return

        val now = System.currentTimeMillis()
        val totalDurationMs = COUNTDOWN_SECONDS * 1000L

        // Parse saved start times: "docId:startTime,docId:startTime,..."
        savedStartTimes.split(",").forEach { entry ->
            val parts = entry.split(":")
            if (parts.size == 2) {
                val documentId = parts[0].toIntOrNull()
                val startTime = parts[1].toLongOrNull()

                if (documentId != null && startTime != null) {
                    val elapsedMs = now - startTime
                    val remainingMs = totalDurationMs - elapsedMs

                    if (remainingMs <= 0) {
                        // Countdown expired during AppLock - schedule immediate delete via WorkManager
                        trashDeleteWorkManager.scheduleImmediateDelete(documentId)
                    } else {
                        // Schedule WorkManager job with remaining time
                        val remainingSeconds = (remainingMs / 1000).toInt() + 1
                        trashDeleteWorkManager.schedulePendingDelete(documentId, remainingSeconds.toLong())
                        // Continue UI countdown from where it left off
                        startPendingDeleteWithRemainingTime(documentId, startTime, remainingMs)
                    }
                }
            }
        }
    }

    /**
     * Start pending delete UI countdown with remaining time (for restore after AppLock).
     * The actual deletion is handled by TrashDeleteWorker (scheduled in restorePendingDeletes).
     */
    private fun startPendingDeleteWithRemainingTime(
        documentId: Int,
        originalStartTime: Long,
        remainingMs: Long
    ) {
        // Cancel existing UI job if any
        pendingDeleteJobs[documentId]?.cancel()

        // Track start time
        pendingDeleteStartTimes[documentId] = originalStartTime
        savePendingDeletesToDataStore()

        // Calculate initial state
        val totalDurationMs = COUNTDOWN_SECONDS * 1000L
        val initialProgress = remainingMs.toFloat() / totalDurationMs
        val initialSeconds = (remainingMs / 1000).toInt() + 1

        // Add to pending deletes state for UI
        _uiState.update { state ->
            state.copy(
                pendingDeletes = state.pendingDeletes + (documentId to PendingDeleteState(
                    documentId = documentId,
                    progress = initialProgress,
                    secondsRemaining = initialSeconds
                ))
            )
        }

        // Start UI countdown job with remaining time
        // The actual deletion is handled by TrashDeleteWorker
        val remainingSteps = (remainingMs / COUNTDOWN_STEP_MS).toInt()
        val job = viewModelScope.launch {
            for (i in remainingSteps downTo 0) {
                val secondsRemaining = (i * COUNTDOWN_STEP_MS / 1000).toInt() + 1
                val progress = i.toFloat() / (totalDurationMs / COUNTDOWN_STEP_MS).toInt()

                _uiState.update { state ->
                    val currentPending = state.pendingDeletes[documentId]
                    if (currentPending != null) {
                        state.copy(
                            pendingDeletes = state.pendingDeletes + (documentId to currentPending.copy(
                                progress = progress,
                                secondsRemaining = secondsRemaining
                            ))
                        )
                    } else {
                        state
                    }
                }

                delay(COUNTDOWN_STEP_MS)
            }

            // UI countdown finished - just remove from UI state
            // The actual deletion is handled by TrashDeleteWorker
            // IMPORTANT: Do NOT remove from DataStore here - the Worker needs
            // to see it's still pending. The Worker handles DataStore cleanup.
            _uiState.update { state ->
                state.copy(pendingDeletes = state.pendingDeletes - documentId)
            }
            pendingDeleteJobs.remove(documentId)
            pendingDeleteStartTimes.remove(documentId)
            // Note: Don't call savePendingDeletesToDataStore() - Worker handles this
        }

        pendingDeleteJobs[documentId] = job
    }

    /**
     * Save pending deletes to DataStore for AppLock survival.
     * Uses TokenManager which persists independently of NavBackStackEntry lifecycle.
     * Format: "docId:startTime,docId:startTime,..."
     */
    private fun savePendingDeletesToDataStore() {
        val serialized = if (pendingDeleteStartTimes.isEmpty()) {
            null
        } else {
            pendingDeleteStartTimes.entries.joinToString(",") { "${it.key}:${it.value}" }
        }
        viewModelScope.launch {
            tokenManager.savePendingTrashDeletes(serialized)
        }
    }

    /**
     * Remove a pending delete from tracking.
     */
    private fun removePendingDelete(documentId: Int) {
        pendingDeleteJobs[documentId]?.cancel()
        pendingDeleteJobs.remove(documentId)
        pendingDeleteStartTimes.remove(documentId)
        savePendingDeletesToDataStore()

        _uiState.update { state ->
            state.copy(pendingDeletes = state.pendingDeletes - documentId)
        }
    }

    /**
     * BEST PRACTICE: Fetch ALL trash documents from API and update Room cache.
     * Called on screen init to sync server state with local cache.
     * This ensures documents deleted via web interface are visible in app.
     *
     * Fetches all pages from API (100 docs per page) to ensure complete sync.
     */
    fun refreshTrash() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

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
                    .onFailure { error ->
                        hasMore = false
                        _uiState.update {
                            it.copy(
                                isLoading = false,
                                error = error.message ?: "Fehler beim Laden des Papierkorbs"
                            )
                        }
                        return@launch
                    }
            }

            // Clean up local trash docs that no longer exist on server
            // (e.g., auto-expired after 30 days or permanently deleted elsewhere)
            documentRepository.cleanupOrphanedTrashDocs(serverTrashIds)

            // Success - Room Flow will auto-update UI
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    /**
     * Observe trashed documents and update UI state.
     */
    private fun observeDocuments() {
        viewModelScope.launch {
            trashedDocuments.collect { documents ->
                _uiState.update {
                    it.copy(
                        documents = documents,
                        isLoading = false,
                        error = null
                    )
                }
            }
        }
    }

    /**
     * Observe trash count and update UI state.
     */
    private fun observeCount() {
        viewModelScope.launch {
            documentRepository.observeTrashedDocumentsCount()
                .catch { e ->
                    _uiState.update { it.copy(error = e.message) }
                }
                .collect { count ->
                    _uiState.update { it.copy(totalCount = count) }
                }
        }
    }

    /**
     * Restore a single document from trash.
     *
     * @param documentId Document ID to restore
     */
    fun restoreDocument(documentId: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(isRestoring = true, error = null) }

            documentRepository.restoreDocument(documentId)
                .onSuccess {
                    // Success - Flow will auto-update UI
                    _uiState.update { it.copy(isRestoring = false) }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isRestoring = false,
                            error = error.message ?: context.getString(R.string.error_restore_document)
                        )
                    }
                }
        }
    }

    /**
     * Permanently delete a single document.
     *
     * @param documentId Document ID to delete
     */
    fun permanentlyDeleteDocument(documentId: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(isDeleting = true, error = null) }

            documentRepository.permanentlyDeleteDocument(documentId)
                .onSuccess {
                    // Success - Flow will auto-update UI
                    _uiState.update { it.copy(isDeleting = false) }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isDeleting = false,
                            error = error.message ?: context.getString(R.string.error_delete_document)
                        )
                    }
                }
        }
    }

    /**
     * Restore all documents from trash (bulk operation).
     */
    fun restoreAllDocuments() {
        viewModelScope.launch {
            _uiState.update { it.copy(isRestoring = true, error = null) }

            val documentIds = _uiState.value.documents.map { it.id }
            if (documentIds.isEmpty()) {
                _uiState.update { it.copy(isRestoring = false) }
                return@launch
            }

            documentRepository.restoreDocuments(documentIds)
                .onSuccess {
                    _uiState.update { it.copy(isRestoring = false) }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isRestoring = false,
                            error = error.message ?: context.getString(R.string.error_restore_all_documents)
                        )
                    }
                }
        }
    }

    /**
     * Permanently delete all documents (empty trash bulk operation).
     */
    fun emptyTrash() {
        viewModelScope.launch {
            _uiState.update { it.copy(isDeleting = true, error = null) }

            val documentIds = _uiState.value.documents.map { it.id }
            if (documentIds.isEmpty()) {
                _uiState.update { it.copy(isDeleting = false) }
                return@launch
            }

            documentRepository.permanentlyDeleteDocuments(documentIds)
                .onSuccess {
                    _uiState.update { it.copy(isDeleting = false) }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isDeleting = false,
                            error = error.message ?: context.getString(R.string.error_empty_trash)
                        )
                    }
                }
        }
    }

    /**
     * Clear error state.
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    /**
     * Start pending delete countdown for a document.
     *
     * This method does two things:
     * 1. Starts a UI countdown animation (runs in viewModelScope)
     * 2. Schedules a background WorkManager job to perform the actual deletion
     *
     * The WorkManager job ensures the deletion completes even if:
     * - User navigates away from TrashScreen
     * - AppLock is triggered
     * - App is killed/backgrounded
     *
     * BEST PRACTICE: Persists start time to DataStore (via TokenManager) for AppLock survival.
     *
     * @param documentId Document ID to start countdown for
     */
    fun startPendingDelete(documentId: Int) {
        // Cancel existing job if any
        pendingDeleteJobs[documentId]?.cancel()

        // Track start time for persistence
        val startTime = System.currentTimeMillis()
        pendingDeleteStartTimes[documentId] = startTime
        savePendingDeletesToDataStore()

        // Schedule background worker to perform the actual deletion after countdown
        // This runs independently of ViewModel lifecycle
        trashDeleteWorkManager.schedulePendingDelete(documentId, COUNTDOWN_SECONDS.toLong())

        // Add to pending deletes state for UI
        _uiState.update { state ->
            state.copy(
                pendingDeletes = state.pendingDeletes + (documentId to PendingDeleteState(documentId))
            )
        }

        // Start countdown job for UI animation only (viewModelScope)
        // The actual deletion is handled by TrashDeleteWorker
        val job = viewModelScope.launch {
            val totalSteps = (COUNTDOWN_SECONDS * 1000L / COUNTDOWN_STEP_MS).toInt()

            for (i in totalSteps downTo 0) {
                val secondsRemaining = (i * COUNTDOWN_STEP_MS / 1000).toInt() + 1
                val progress = i.toFloat() / totalSteps

                _uiState.update { state ->
                    val currentPending = state.pendingDeletes[documentId]
                    if (currentPending != null) {
                        state.copy(
                            pendingDeletes = state.pendingDeletes + (documentId to currentPending.copy(
                                progress = progress,
                                secondsRemaining = secondsRemaining
                            ))
                        )
                    } else {
                        state
                    }
                }

                delay(COUNTDOWN_STEP_MS)
            }

            // UI countdown finished - just remove from UI state
            // The actual deletion is handled by TrashDeleteWorker
            // IMPORTANT: Do NOT remove from DataStore here - the Worker needs
            // to see it's still pending. The Worker handles DataStore cleanup.
            _uiState.update { state ->
                state.copy(pendingDeletes = state.pendingDeletes - documentId)
            }
            pendingDeleteJobs.remove(documentId)
            pendingDeleteStartTimes.remove(documentId)
            // Note: Don't call savePendingDeletesToDataStore() - Worker handles this
        }

        pendingDeleteJobs[documentId] = job
    }

    /**
     * Cancel pending delete countdown for a document (undo).
     *
     * Cancels both the UI countdown animation AND the background WorkManager job.
     *
     * @param documentId Document ID to cancel countdown for
     */
    fun cancelPendingDelete(documentId: Int) {
        // Cancel background worker first
        trashDeleteWorkManager.cancelPendingDelete(documentId)
        // Then remove from UI state
        removePendingDelete(documentId)
    }

    /**
     * Calculate days until auto-delete based on deletion timestamp.
     *
     * @param deletedAt Deletion timestamp in milliseconds (nullable)
     * @return Human-readable string (e.g., "5 days left", "Expired")
     */
    private fun calculateDaysUntilAutoDelete(deletedAt: Long?): String {
        if (deletedAt == null) return context.getString(R.string.unknown)

        val now = System.currentTimeMillis()
        val deleteTime = deletedAt + (RETENTION_DAYS * 24 * 60 * 60 * 1000L)
        val remainingMs = deleteTime - now

        return when {
            remainingMs <= 0 -> context.getString(R.string.trash_expired)
            remainingMs < 24 * 60 * 60 * 1000L -> context.getString(R.string.trash_less_than_1_day)
            else -> {
                val days = (remainingMs / (24 * 60 * 60 * 1000L)).toInt()
                context.getString(R.string.trash_days_left, days)
            }
        }
    }
}
