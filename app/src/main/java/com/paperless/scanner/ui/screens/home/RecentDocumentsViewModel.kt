package com.paperless.scanner.ui.screens.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.data.analytics.AnalyticsEvent
import com.paperless.scanner.data.analytics.AnalyticsService
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.data.repository.DocumentListRepository
import com.paperless.scanner.data.repository.TagRepository
import com.paperless.scanner.data.repository.TrashRepository
import com.paperless.scanner.domain.model.Tag
import com.paperless.scanner.util.asUiResult
import com.paperless.scanner.util.formatTimeAgo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * UI state for the recent-documents surface of the Home screen.
 *
 * Owns the top-N recently-added documents (with resolved tag metadata) and
 * the transient deleted-document info that drives the undo snackbar.
 */
data class RecentDocumentsUiState(
    val recentDocuments: List<RecentDocument> = emptyList(),
    val deletedDocument: DeletedDocumentInfo? = null,
    val isLoading: Boolean = true,
)

sealed class RecentDocumentsError {
    data class LoadFailed(val source: String, val cause: Throwable) : RecentDocumentsError()
    data class ActionFailed(val action: String, val cause: Throwable) : RecentDocumentsError()
}

/**
 * Owns recently-added document observation, deletion, and undo for HomeScreen.
 *
 * Phase 4 of the [HomeViewModel] god-VM decomposition (issue #72).
 *
 * Holds its own tag cache because the recent-documents flow needs tag metadata
 * (name, color) to render the per-card badge. [HomeViewModel] and the other
 * sub-VMs share no direct reference; the screen layer is the wiring point.
 *
 * Triggers an initial HTTP refresh in [init] (mirrors the pre-extraction
 * loadDashboardData() behavior) so the Room cache is current on cold start.
 */
@HiltViewModel
class RecentDocumentsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val documentListRepository: DocumentListRepository,
    private val tagRepository: TagRepository,
    private val trashRepository: TrashRepository,
    private val tokenManager: TokenManager,
    private val analyticsService: AnalyticsService,
) : ViewModel() {

    companion object {
        private const val RECENT_PAGE_SIZE = 5
        private const val REFRESH_PAGE_SIZE = 10
    }

    private val _uiState = MutableStateFlow(RecentDocumentsUiState())
    val uiState: StateFlow<RecentDocumentsUiState> = _uiState.asStateFlow()

    private val _error = MutableStateFlow<RecentDocumentsError?>(null)
    val error: StateFlow<RecentDocumentsError?> = _error.asStateFlow()

    // Thread-safe tag cache. Written from observeTagsReactively, read by
    // observeRecentDocumentsReactively via combine() so a tag rename / recolor
    // propagates to already-emitted cards without needing a fresh document
    // emission. Atomic StateFlow setter — no unsynchronized `var Map`.
    private val _tagMap = MutableStateFlow<Map<Int, Tag>>(emptyMap())

    val serverUrl: StateFlow<String> = tokenManager.serverUrl
        .map { it ?: "" }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = "",
        )

    val showThumbnails: StateFlow<Boolean> = tokenManager.showThumbnails
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true,
        )

    init {
        observeTagsReactively()
        observeRecentDocumentsReactively()
        // Initial HTTP sync — Room cache may be stale from a previous session;
        // mirrors the pre-extraction loadDashboardData() behavior.
        refreshRecentDocuments()
    }

    private fun observeTagsReactively() {
        viewModelScope.launch {
            tagRepository.observeTags()
                .asUiResult()
                .collect { result ->
                    result.onSuccess { tags ->
                        _tagMap.value = tags.associateBy { it.id }
                    }.onFailure { e ->
                        _error.value = RecentDocumentsError.LoadFailed("tags", e)
                    }
                }
        }
    }

    private fun observeRecentDocumentsReactively() {
        viewModelScope.launch {
            combine(
                documentListRepository.observeDocuments(page = 1, pageSize = RECENT_PAGE_SIZE),
                _tagMap,
            ) { documents, currentTagMap ->
                documents.map { doc ->
                    val firstTagId = doc.tags.firstOrNull()
                    val tag = firstTagId?.let { currentTagMap[it] }
                    RecentDocument(
                        id = doc.id,
                        title = doc.title,
                        timeAgo = formatTimeAgo(context, doc.added),
                        tagName = tag?.name,
                        tagColor = tag?.color?.let { parseColorToLong(it) },
                    )
                }
            }
                .asUiResult()
                .collect { result ->
                    result.onSuccess { recentDocs ->
                        _uiState.update { it.copy(recentDocuments = recentDocs, isLoading = false) }
                    }.onFailure { e ->
                        _uiState.update { it.copy(isLoading = false) }
                        _error.value = RecentDocumentsError.LoadFailed("recentDocuments", e)
                    }
                }
        }
    }

    /**
     * Refresh the recently-added documents from the server. Updates the Room
     * cache; the reactive flow above re-emits with the new data.
     *
     * Called from:
     * - init (cold start)
     * - HomeScreen pull-to-refresh (in parallel with HomeViewModel.refreshDashboard)
     * - HomeScreen onlineTransition (offline -> online reconnect)
     */
    fun refreshRecentDocuments() {
        viewModelScope.launch {
            documentListRepository.getDocuments(
                page = 1,
                pageSize = REFRESH_PAGE_SIZE,
                ordering = "-added",
                forceRefresh = true,
            )
        }
    }

    fun deleteRecentDocument(documentId: Int, documentTitle: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(deletedDocument = DeletedDocumentInfo(documentId, documentTitle))
            }

            try {
                trashRepository.deleteDocument(documentId).onSuccess {
                    // Recent documents list updates automatically via reactive Flow.
                    analyticsService.trackEvent(AnalyticsEvent.DocumentDeleted)
                }.onFailure { error ->
                    _uiState.update { it.copy(deletedDocument = null) }
                    _error.value = RecentDocumentsError.ActionFailed("deleteDocument", error)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(deletedDocument = null) }
                _error.value = RecentDocumentsError.ActionFailed("deleteDocument", e)
            }
        }
    }

    /**
     * Undo the most recent document deletion by clearing its soft-delete flag.
     */
    fun undoDelete() {
        val deletedDoc = _uiState.value.deletedDocument ?: return

        viewModelScope.launch {
            // Clear deleted document state immediately for responsive UX.
            _uiState.update { it.copy(deletedDocument = null) }

            try {
                trashRepository.restoreDocument(deletedDoc.id).onFailure {
                    _error.value = RecentDocumentsError.ActionFailed("restoreDocument", it)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = RecentDocumentsError.ActionFailed("restoreDocument", e)
            }
        }
    }

    /**
     * Clear the deleted-document info (when the undo snackbar is dismissed).
     */
    fun clearDeletedDocument() {
        _uiState.update { it.copy(deletedDocument = null) }
    }

    fun clearError() {
        _error.value = null
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
