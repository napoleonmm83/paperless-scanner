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
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
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

    // Separate flow for search input to enable debouncing
    private val _searchQueryFlow = MutableStateFlow("")

    private var allDocuments: List<DocumentItem> = emptyList()
    private var tagMap: Map<Int, Tag> = emptyMap()
    private var correspondentMap: Map<Int, Correspondent> = emptyMap()

    init {
        loadInitialData()
        observeDocumentsReactively()
        observeDebouncedSearch()
    }

    /**
     * BEST PRACTICE: Debounced search to avoid triggering API calls on every keystroke.
     * Waits 300ms after user stops typing before executing search.
     */
    @OptIn(FlowPreview::class)
    private fun observeDebouncedSearch() {
        viewModelScope.launch {
            _searchQueryFlow
                .debounce(300) // Wait 300ms after last keystroke
                .distinctUntilChanged() // Only trigger if value actually changed
                .collect { query ->
                    _uiState.update { it.copy(searchQuery = query) }
                    loadDocuments()
                }
        }
    }

    /**
     * BEST PRACTICE: Reactive Flow-based observation.
     * Automatically updates UI when documents are added/modified/deleted in DB.
     * No manual refresh logic needed!
     */
    private fun observeDocumentsReactively() {
        viewModelScope.launch {
            documentRepository.observeDocuments(
                page = 1,
                pageSize = 25
            ).collect { documents ->
                // Transform to UI models
                allDocuments = documents.map { it.toDocumentItem() }

                _uiState.update {
                    it.copy(
                        documents = allDocuments,
                        isLoading = false,
                        totalCount = allDocuments.size
                    )
                }
            }
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

    fun loadDocuments() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            val currentState = _uiState.value
            val query = currentState.searchQuery.takeIf { it.isNotBlank() }
            val tagIds = currentState.activeTagFilter?.let { listOf(it) }

            documentRepository.getDocuments(
                page = 1,
                pageSize = 25,
                query = query,
                tagIds = tagIds
            ).onSuccess { response ->
                allDocuments = response.results.map { doc ->
                    DocumentItem(
                        id = doc.id,
                        title = doc.title,
                        date = DateFormatter.formatDateShort(doc.created),
                        correspondent = doc.correspondentId?.let { correspondentMap[it]?.name },
                        tags = doc.tags.mapNotNull { tagMap[it]?.name }
                    )
                }
                _uiState.update {
                    it.copy(
                        documents = allDocuments,
                        isLoading = false,
                        totalCount = response.count,
                        currentPage = 1,
                        hasMorePages = response.next != null
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = error.message ?: context.getString(R.string.error_loading)
                    )
                }
            }
        }
    }

    fun loadNextPage() {
        val currentState = _uiState.value
        if (currentState.isLoading || !currentState.hasMorePages) return

        viewModelScope.launch {
            val nextPage = currentState.currentPage + 1
            val query = currentState.searchQuery.takeIf { it.isNotBlank() }
            val tagIds = currentState.activeTagFilter?.let { listOf(it) }

            documentRepository.getDocuments(
                page = nextPage,
                pageSize = 25,
                query = query,
                tagIds = tagIds
            ).onSuccess { response ->
                val newDocuments = response.results.map { it.toDocumentItem() }
                allDocuments = allDocuments + newDocuments
                _uiState.update {
                    it.copy(
                        documents = allDocuments,
                        currentPage = nextPage,
                        hasMorePages = response.next != null
                    )
                }
            }
        }
    }

    fun search(query: String) {
        // Update search flow - debouncing happens in observeDebouncedSearch()
        _searchQueryFlow.update { query }
    }

    fun filterByTag(tagId: Int?) {
        _uiState.update { it.copy(activeTagFilter = tagId) }
        loadDocuments()
    }

    fun refresh() {
        loadInitialData()
    }

    fun clearFilters() {
        _uiState.update { it.copy(searchQuery = "", activeTagFilter = null) }
        loadDocuments()
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun resetState() {
        _uiState.update { DocumentsUiState() }
        allDocuments = emptyList()
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

