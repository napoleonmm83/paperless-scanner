package com.paperless.scanner.ui.screens.documents

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.google.gson.JsonSerializer
import com.paperless.scanner.R
import java.time.LocalDate
import com.paperless.scanner.domain.model.Correspondent
import com.paperless.scanner.domain.model.DocumentFilter
import com.paperless.scanner.domain.model.DocumentType
import com.paperless.scanner.domain.model.Tag
import com.paperless.scanner.data.repository.CorrespondentRepository
import com.paperless.scanner.data.repository.DocumentRepository
import com.paperless.scanner.data.repository.DocumentTypeRepository
import com.paperless.scanner.data.repository.TagRepository
import com.paperless.scanner.util.DateFormatter
import com.paperless.scanner.utils.escapeSqlLikeWildcards
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * PAGING 3: Simplified UiState without manual pagination fields.
 *
 * Changes from manual pagination:
 * - Removed: documents (now in separate documentsFlow)
 * - Removed: currentPage, hasMorePages (Paging 3 handles this)
 * - Removed: totalCount (not needed with Paging 3)
 * - Removed: isLoadingMore (LoadState.append handles this)
 * - Kept: isLoading (for initial load), filter-related fields, metadata
 */
data class DocumentsUiState(
    val isLoading: Boolean = true,
    val error: String? = null,
    val searchQuery: String = "",
    val activeTagFilter: Int? = null,
    val availableTags: List<Tag> = emptyList(),
    val availableCorrespondents: List<Correspondent> = emptyList(),
    val availableDocumentTypes: List<com.paperless.scanner.domain.model.DocumentType> = emptyList()
)

@HiltViewModel
class DocumentsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val documentRepository: DocumentRepository,
    private val tagRepository: TagRepository,
    private val correspondentRepository: CorrespondentRepository,
    private val documentTypeRepository: DocumentTypeRepository,
    private val tokenManager: com.paperless.scanner.data.datastore.TokenManager,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {

    // Gson with LocalDate adapter for proper serialization
    private val gson = GsonBuilder()
        .registerTypeAdapter(
            LocalDate::class.java,
            JsonSerializer<LocalDate> { src, _, _ ->
                com.google.gson.JsonPrimitive(src.toString())
            }
        )
        .registerTypeAdapter(
            LocalDate::class.java,
            JsonDeserializer { json, _, _ ->
                LocalDate.parse(json.asString)
            }
        )
        .create()

    private val _uiState = MutableStateFlow(DocumentsUiState())
    val uiState: StateFlow<DocumentsUiState> = _uiState.asStateFlow()

    // Advanced filter state for comprehensive filtering
    private val _filter = MutableStateFlow(DocumentFilter())
    val filter: StateFlow<DocumentFilter> = _filter.asStateFlow()

    private var tagMap: Map<Int, Tag> = emptyMap()
    private var correspondentMap: Map<Int, Correspondent> = emptyMap()

    /**
     * PAGING 3: Reactive Flow of paginated documents.
     *
     * Automatically updates when:
     * - Filter changes (via flatMapLatest)
     * - Documents are added/modified/deleted in DB (PagingSource invalidation)
     *
     * cachedIn(viewModelScope):
     * - Caches PagingData across configuration changes
     * - Prevents reloading when rotating device
     * - Manages Flow lifecycle with ViewModel
     */
    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    val documentsFlow: Flow<PagingData<DocumentItem>> = _filter
        .debounce(300) // Debounce filter changes (especially search query)
        .distinctUntilChanged()
        .flatMapLatest { filter ->
            // Update UI state with current filter (for display in filter chips)
            _uiState.update {
                it.copy(
                    searchQuery = filter.query ?: "",
                    activeTagFilter = filter.tagIds.firstOrNull(),
                    isLoading = false
                )
            }

            // Create new Pager with current filter
            documentRepository.observeDocumentsPaged(filter)
        }
        .map { pagingData ->
            // Map Document to DocumentItem using PagingData.map
            pagingData.map { document ->
                document.toDocumentItem()
            }
        }
        .cachedIn(viewModelScope) // Cache across config changes

    init {
        restoreFilterState()
        loadInitialData()
    }

    /**
     * Restores filter state from DataStore (survives AppLock) or SavedStateHandle (Process Death Recovery).
     * Priority: DataStore > SavedStateHandle > Default
     */
    private fun restoreFilterState() {
        viewModelScope.launch {
            try {
                // FIRST: Try DataStore (survives AppLock and app restart)
                val dataStoreFilterJson = tokenManager.documentFilterJson.first()
                if (dataStoreFilterJson != null) {
                    _filter.value = gson.fromJson(dataStoreFilterJson, DocumentFilter::class.java)
                    // Sync to SavedStateHandle for Process Death
                    savedStateHandle["filter_json"] = dataStoreFilterJson
                    return@launch
                }
            } catch (e: Exception) {
                // If DataStore fails, try SavedStateHandle
            }

            // FALLBACK: Try SavedStateHandle (Process Death Recovery)
            val savedStateFilterJson = savedStateHandle.get<String>("filter_json")
            if (savedStateFilterJson != null) {
                try {
                    _filter.value = gson.fromJson(savedStateFilterJson, DocumentFilter::class.java)
                } catch (e: Exception) {
                    // If deserialization fails, keep default filter
                }
            }
        }
    }

    // observeDocumentsReactively() removed - replaced by documentsFlow property above

    private fun loadInitialData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            // Load tags, correspondents, and document types for filter UI and name lookups
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
                _uiState.update { it.copy(availableDocumentTypes = documentTypes) }
            }

            // Note: documentsFlow handles document loading via Paging 3
        }
    }

    /**
     * Updates search query. Debouncing and reactive update happen automatically.
     * Updates the comprehensive DocumentFilter with new query.
     *
     * BEST PRACTICE: Input sanitization for SQL LIKE queries:
     * - Trims whitespace to avoid matching issues with leading/trailing spaces
     * - Escapes SQL wildcards (%, _) to prevent unintended wildcard matching
     *
     * Examples:
     * - "50% " → "50\%" (escapes wildcard, trims space)
     * - "test_file" → "test\_file" (escapes underscore wildcard)
     */
    fun search(query: String) {
        val sanitized = query.trim()
            .escapeSqlLikeWildcards()
            .takeIf { it.isNotBlank() }
        _filter.update { it.copy(query = sanitized) }
    }

    /**
     * Updates tag filter. Reactive update happens instantly (no debounce).
     * Updates the comprehensive DocumentFilter with new single tag.
     * @deprecated Use updateFilter() for multi-tag filtering
     */
    fun filterByTag(tagId: Int?) {
        _filter.update { it.copy(tagIds = tagId?.let { listOf(it) } ?: emptyList()) }
    }

    /**
     * PAGING 3: Updates comprehensive document filter.
     *
     * Persists filter to BOTH DataStore (survives AppLock) and SavedStateHandle (Process Death).
     * Triggers background API sync with new filter.
     *
     * Note: No manual page reset needed - PagingSource automatically recreates when filter changes
     * via flatMapLatest in documentsFlow.
     */
    fun updateFilter(newFilter: DocumentFilter) {
        _filter.value = newFilter

        val filterJson = gson.toJson(newFilter)

        // Persist to SavedStateHandle (Process Death Recovery)
        savedStateHandle["filter_json"] = filterJson

        // Persist to DataStore (survives AppLock and app restart)
        viewModelScope.launch {
            tokenManager.saveDocumentFilter(filterJson)
        }

        // Trigger background API refresh with new filter
        triggerFilterSync(newFilter)
    }

    /**
     * PAGING 3: Clears all advanced filters and resets to default state.
     *
     * Removes filter from BOTH DataStore and SavedStateHandle.
     *
     * Note: No manual page reset needed - PagingSource automatically recreates when filter changes.
     */
    fun clearFilter() {
        _filter.value = DocumentFilter()

        // Remove from SavedStateHandle
        savedStateHandle.remove<String>("filter_json")

        // Remove from DataStore
        viewModelScope.launch {
            tokenManager.clearDocumentFilter()
        }

        // Trigger background API refresh with empty filter
        triggerFilterSync(DocumentFilter())
    }

    // loadNextPage() removed - Paging 3 handles infinite scrolling automatically

    /**
     * Triggers background API sync with comprehensive DocumentFilter.
     * Room Flow will automatically update UI when cache changes.
     */
    private fun triggerFilterSync(filter: DocumentFilter) {
        viewModelScope.launch {
            documentRepository.getDocuments(
                page = 1,
                pageSize = 100,
                filter = filter,
                forceRefresh = true
            )
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
        _filter.value = DocumentFilter()
        savedStateHandle.remove<String>("filter_json")
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

