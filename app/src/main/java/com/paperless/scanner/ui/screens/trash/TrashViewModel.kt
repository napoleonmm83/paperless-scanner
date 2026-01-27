package com.paperless.scanner.ui.screens.trash

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.R
import com.paperless.scanner.data.database.entities.CachedDocument
import com.paperless.scanner.data.repository.DocumentRepository
import com.paperless.scanner.util.DateFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * UI State for Trash Screen.
 */
data class TrashUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val documents: List<TrashDocumentItem> = emptyList(),
    val totalCount: Int = 0,
    val isRestoring: Boolean = false,
    val isDeleting: Boolean = false
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
    private val documentRepository: DocumentRepository
) : ViewModel() {

    companion object {
        private const val RETENTION_DAYS = 30 // Default trash retention (same as backend)
    }

    private val _uiState = MutableStateFlow(TrashUiState())
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
    }

    /**
     * BEST PRACTICE: Fetch trash documents from API and update Room cache.
     * Called on screen init to sync server state with local cache.
     * This ensures documents deleted via web interface are visible in app.
     */
    fun refreshTrash() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            documentRepository.getTrashDocuments()
                .onSuccess {
                    // Success - Room Flow will auto-update UI
                    _uiState.update { it.copy(isLoading = false) }
                }
                .onFailure { error ->
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = error.message ?: "Fehler beim Laden des Papierkorbs"
                        )
                    }
                }
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
