package com.paperless.scanner.ui.screens.documents

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.R
import com.paperless.scanner.domain.model.Correspondent
import com.paperless.scanner.domain.model.Tag
import com.paperless.scanner.data.repository.CorrespondentRepository
import com.paperless.scanner.data.repository.DocumentRepository
import com.paperless.scanner.data.repository.TagRepository
import com.paperless.scanner.util.DateFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DocumentsUiState(
    val documents: List<DocumentItem> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val searchQuery: String = "",
    val activeTagFilter: Int? = null,
    val availableTags: List<Tag> = emptyList(),
    val totalCount: Int = 0,
    val currentPage: Int = 1,
    val hasMorePages: Boolean = false
)

@HiltViewModel
class DocumentsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val documentRepository: DocumentRepository,
    private val tagRepository: TagRepository,
    private val correspondentRepository: CorrespondentRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DocumentsUiState())
    val uiState: StateFlow<DocumentsUiState> = _uiState.asStateFlow()

    // BEST PRACTICE: Separate flows for filter inputs to enable debouncing
    private val _searchQueryFlow = MutableStateFlow("")
    private val _tagFilterFlow = MutableStateFlow<Int?>(null)

    private var tagMap: Map<Int, Tag> = emptyMap()
    private var correspondentMap: Map<Int, Correspondent> = emptyMap()

    init {
        loadInitialData()
        observeDocumentsReactively()
    }

    /**
     * BEST PRACTICE: Single reactive Flow combining search + tag filter with debouncing.
     * Automatically updates UI when:
     * - Documents are added/modified/deleted in DB
     * - Search query changes (with 300ms debounce)
     * - Tag filter changes (no debounce)
     *
     * Uses flatMapLatest to automatically cancel old flows when filters change.
     * This eliminates race conditions and memory leaks!
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    private fun observeDocumentsReactively() {
        viewModelScope.launch {
            // Combine search (debounced) and tag filter (instant)
            _searchQueryFlow
                .debounce(300) // Wait 300ms after last keystroke
                .distinctUntilChanged()
                .combine(_tagFilterFlow) { searchQuery, tagId ->
                    Pair(searchQuery.takeIf { it.isNotBlank() }, tagId)
                }
                .flatMapLatest { (searchQuery, tagId) ->
                    // Update UI state with current filters
                    _uiState.update {
                        it.copy(
                            searchQuery = searchQuery ?: "",
                            activeTagFilter = tagId,
                            isLoading = true
                        )
                    }

                    // Trigger background API sync (fire and forget)
                    triggerBackgroundSync(searchQuery, tagId)

                    // Return the filtered documents flow
                    documentRepository.observeDocumentsFiltered(
                        searchQuery = searchQuery,
                        tagId = tagId,
                        page = 1,
                        pageSize = 100
                    )
                }
                .collect { documents ->
                    val documentItems = documents.map { it.toDocumentItem() }
                    _uiState.update {
                        it.copy(
                            documents = documentItems,
                            isLoading = false
                        )
                    }
                }
        }

        // Separate flow for total count
        viewModelScope.launch {
            _searchQueryFlow
                .debounce(300)
                .distinctUntilChanged()
                .combine(_tagFilterFlow) { searchQuery, tagId ->
                    Pair(searchQuery.takeIf { it.isNotBlank() }, tagId)
                }
                .flatMapLatest { (searchQuery, tagId) ->
                    documentRepository.observeFilteredCount(
                        searchQuery = searchQuery,
                        tagId = tagId
                    )
                }
                .collect { count ->
                    _uiState.update { it.copy(totalCount = count) }
                }
        }
    }

    /**
     * Triggers background API sync to refresh cache with latest data.
     * Room Flow will automatically update UI when cache changes.
     */
    private fun triggerBackgroundSync(searchQuery: String?, tagId: Int?) {
        viewModelScope.launch {
            documentRepository.getDocuments(
                page = 1,
                pageSize = 100,
                query = searchQuery,
                tagIds = tagId?.let { listOf(it) },
                forceRefresh = true
            )
            // Result is ignored - Room Flow handles UI update
        }
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            // Load tags and correspondents first for name lookups
            val tagsResult = tagRepository.getTags()
            val correspondentsResult = correspondentRepository.getCorrespondents()

            tagsResult.onSuccess { tags ->
                tagMap = tags.associateBy { it.id }
                _uiState.update { it.copy(availableTags = tags) }
            }

            correspondentsResult.onSuccess { correspondents ->
                correspondentMap = correspondents.associateBy { it.id }
            }

            // Note: observeDocumentsReactively() handles document loading via Flow
        }
    }

    // Deprecated: No longer needed with reactive Flow architecture
    // Background sync is handled automatically by observeDocumentsReactively()

    /**
     * Updates search query. Debouncing and reactive update happen automatically.
     */
    fun search(query: String) {
        _searchQueryFlow.update { query }
    }

    /**
     * Updates tag filter. Reactive update happens instantly (no debounce).
     */
    fun filterByTag(tagId: Int?) {
        _tagFilterFlow.update { tagId }
    }

    fun refresh() {
        loadInitialData()
    }

    fun clearFilters() {
        _searchQueryFlow.update { "" }
        _tagFilterFlow.update { null }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun resetState() {
        _uiState.update { DocumentsUiState() }
        _searchQueryFlow.update { "" }
        _tagFilterFlow.update { null }
        loadInitialData()
    }

    /**
     * BEST PRACTICE: Single transformation method to convert Document domain model
     * to DocumentItem UI model. Avoids code duplication across multiple calls.
     */
    private fun com.paperless.scanner.domain.model.Document.toDocumentItem(): DocumentItem {
        return DocumentItem(
            id = this.id,
            title = this.title,
            date = DateFormatter.formatDateShort(this.created),
            correspondent = this.correspondentId?.let { correspondentMap[it]?.name },
            tags = this.tags.mapNotNull { tagMap[it]?.name }
        )
    }
}

