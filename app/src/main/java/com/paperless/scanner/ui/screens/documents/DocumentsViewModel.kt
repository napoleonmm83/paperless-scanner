package com.paperless.scanner.ui.screens.documents

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import com.paperless.scanner.R
import com.paperless.scanner.domain.model.Correspondent
import com.paperless.scanner.domain.model.DocumentFilter
import com.paperless.scanner.domain.model.DocumentType
import com.paperless.scanner.domain.model.Tag
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.data.repository.CorrespondentRepository
import com.paperless.scanner.data.repository.DocumentRepository
import com.paperless.scanner.data.repository.DocumentTypeRepository
import com.paperless.scanner.data.repository.TagRepository
import com.paperless.scanner.util.DateFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DocumentsUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val currentFilter: DocumentFilter = DocumentFilter.empty(),
    val availableTags: List<Tag> = emptyList(),
    val availableCorrespondents: List<Correspondent> = emptyList(),
    val availableDocumentTypes: List<DocumentType> = emptyList(),
    val totalCount: Int = 0
    // NOTE: documents are now Flow<PagingData<DocumentItem>> (not in UI State)
)

@HiltViewModel
class DocumentsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val savedStateHandle: SavedStateHandle,
    private val documentRepository: DocumentRepository,
    private val tagRepository: TagRepository,
    private val correspondentRepository: CorrespondentRepository,
    private val documentTypeRepository: DocumentTypeRepository,
    private val tokenManager: TokenManager
) : ViewModel() {

    companion object {
        private const val KEY_FILTER = "document_filter"
    }

    private val _uiState = MutableStateFlow(DocumentsUiState())
    val uiState: StateFlow<DocumentsUiState> = _uiState.asStateFlow()

    /**
     * BEST PRACTICE: Dual State Pattern for Search + Filter.
     *
     * - _searchQueryFlow: Full-text search (SearchBar) - searches title, content, filename, ASN, tags
     * - _filterFlow: Structured filters (FilterSheet) - date, tags, correspondent, document type, archive status
     *
     * Both are combined reactively via combine() for optimal UX.
     * Dual-Persistence: SavedStateHandle + TokenManager (survives process death AND AppLock).
     */
    private val _searchQueryFlow = MutableStateFlow<String?>(null)
    private val _filterFlow = MutableStateFlow(DocumentFilter.empty())

    private var tagMap: Map<Int, Tag> = emptyMap()
    private var correspondentMap: Map<Int, Correspondent> = emptyMap()
    private var documentTypeMap: Map<Int, DocumentType> = emptyMap()

    /**
     * PAGING 3: Documents as paginated Flow for infinite scroll.
     * Use collectAsLazyPagingItems() in UI.
     *
     * Automatically reloads when search/filter changes (via flatMapLatest).
     * cachedIn(viewModelScope) prevents re-fetching on config changes.
     */
    @OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
    val pagedDocuments: StateFlow<PagingData<DocumentItem>> = combine(
        _searchQueryFlow.debounce(300L).distinctUntilChanged(),
        _filterFlow.debounce(0L).distinctUntilChanged()
    ) { searchQuery, filter ->
        Pair(searchQuery, filter)
    }
        .flatMapLatest { (searchQuery, filter) ->
            // Update UI state with current filter
            _uiState.update {
                it.copy(currentFilter = filter, isLoading = false)
            }

            // Persist filter to dual storage
            saveFilterToPersistence(filter)

            // Trigger background API sync (fire and forget)
            triggerBackgroundSync(query = searchQuery, filter = filter)

            // Return the paged documents flow
            documentRepository.getDocumentsPaged(
                searchQuery = searchQuery,
                filter = filter
            ).map { pagingData ->
                // Map to UI model with tag/correspondent names
                pagingData.map { document ->
                    DocumentItem(
                        id = document.id,
                        title = document.title,
                        date = DateFormatter.formatDateShort(document.created),
                        correspondent = document.correspondentId?.let { correspondentMap[it]?.name },
                        tags = document.tags.mapNotNull { tagMap[it]?.name }
                    )
                }
            }
        }
        .cachedIn(viewModelScope)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = PagingData.empty()
        )

    init {
        restoreFilterState()
        loadInitialData()
        // Note: pagedDocuments is now a property (not a function), auto-initialized
        observeTotalCount()
    }

    /**
     * Restore filter state from persistent storage.
     * Priority: SavedStateHandle > DataStore > empty filter
     */
    private fun restoreFilterState() {
        viewModelScope.launch {
            // Try SavedStateHandle first (for process death recovery)
            val savedFilterJson = savedStateHandle.get<String>(KEY_FILTER)
            val filter = if (savedFilterJson != null) {
                DocumentFilter.fromJson(savedFilterJson)
            } else {
                // Fallback to DataStore (for AppLock resilience)
                tokenManager.getDocumentFilterSync()
            }

            _filterFlow.update { filter }
            _uiState.update { it.copy(currentFilter = filter) }
        }
    }

    /**
     * Track total document count (for UI display).
     * Runs separately from pagedDocuments.
     */
    private fun observeTotalCount() {
        viewModelScope.launch {
            val debouncedSearchQuery = _searchQueryFlow
                .debounce(300L)
                .distinctUntilChanged()

            val debouncedFilter = _filterFlow
                .debounce(0L)
                .distinctUntilChanged()

            combine(debouncedSearchQuery, debouncedFilter) { searchQuery, filter ->
                Pair(searchQuery, filter)
            }
                .flatMapLatest { (searchQuery, filter) ->
                    documentRepository.observeCountWithFilter(
                        searchQuery = searchQuery,
                        filter = filter
                    )
                }
                .collect { count ->
                    _uiState.update { it.copy(totalCount = count) }
                }
        }
    }

    /**
     * Dual-Persistence: Save to both SavedStateHandle and DataStore.
     */
    private fun saveFilterToPersistence(filter: DocumentFilter) {
        viewModelScope.launch {
            // SavedStateHandle for process death recovery
            if (filter.isEmpty()) {
                savedStateHandle[KEY_FILTER] = null
            } else {
                savedStateHandle[KEY_FILTER] = filter.toJson()
            }

            // DataStore for AppLock resilience
            tokenManager.saveDocumentFilter(filter)
        }
    }

    /**
     * Triggers background API sync to refresh cache with latest data.
     * Room Flow will automatically update UI when cache changes.
     *
     * @param query Full-text search query (from SearchBar)
     * @param filter Structured filter criteria (from FilterSheet)
     */
    private fun triggerBackgroundSync(query: String?, filter: DocumentFilter) {
        viewModelScope.launch {
            documentRepository.getDocuments(
                page = 1,
                pageSize = 100,
                query = query?.takeIf { it.isNotBlank() },
                tagIds = filter.tagIds.takeIf { it.isNotEmpty() },
                correspondentId = filter.correspondentId,
                documentTypeId = filter.documentTypeId,
                forceRefresh = true
            )
            // Result is ignored - Room Flow handles UI update
        }
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            // Load tags, correspondents, and document types first for name lookups
            val tagsResult = tagRepository.getTags()
            val correspondentsResult = correspondentRepository.getCorrespondents()
            val documentTypesResult = documentTypeRepository.getDocumentTypes()

            tagsResult.onSuccess { tags ->
                tagMap = tags.associateBy { it.id }
                _uiState.update { it.copy(availableTags = tags) }
            }

            correspondentsResult.onSuccess { correspondents ->
                correspondentMap = correspondents.associateBy { it.id }
                _uiState.update { it.copy(availableCorrespondents = correspondents) }
            }

            documentTypesResult.onSuccess { documentTypes ->
                documentTypeMap = documentTypes.associateBy { it.id }
                _uiState.update { it.copy(availableDocumentTypes = documentTypes) }
            }

            // Note: observeDocumentsReactively() handles document loading via Flow
        }
    }

    /**
     * Apply a new filter (replaces current filter completely).
     * Automatically persists to SavedStateHandle and TokenManager.
     */
    fun applyFilter(filter: DocumentFilter) {
        _filterFlow.update { filter }
        saveFilterToPersistence(filter)
    }

    /**
     * Update filter with a lambda (for partial updates).
     * Automatically persists to SavedStateHandle and TokenManager.
     *
     * Example: updateFilter { it.copy(query = "invoice") }
     */
    fun updateFilter(block: (DocumentFilter) -> DocumentFilter) {
        val newFilter = block(_filterFlow.value)
        _filterFlow.update { newFilter }
        saveFilterToPersistence(newFilter)
    }

    /**
     * Clear all filters (reset to empty).
     * Automatically persists to SavedStateHandle and TokenManager.
     */
    fun clearFilter() {
        val emptyFilter = DocumentFilter.empty()
        _filterFlow.update { emptyFilter }
        saveFilterToPersistence(emptyFilter)
    }

    /**
     * Update search query (for SearchBar).
     * Full-text search is handled separately from structured filters.
     */
    fun search(query: String) {
        _searchQueryFlow.update { query.takeIf { it.isNotBlank() } }
    }

    /**
     * Legacy: Update only tag filter (for backward compatibility).
     */
    fun filterByTag(tagId: Int?) {
        updateFilter {
            if (tagId == null) {
                it.copy(tagIds = emptyList())
            } else {
                it.copy(tagIds = listOf(tagId))
            }
        }
    }

    fun refresh() {
        loadInitialData()
    }

    fun clearFilters() {
        clearFilter()
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun resetState() {
        _uiState.update { DocumentsUiState() }
        _filterFlow.update { DocumentFilter.empty() }
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
