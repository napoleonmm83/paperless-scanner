package com.paperless.scanner.ui.screens.documents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.data.api.models.Correspondent
import com.paperless.scanner.data.api.models.Tag
import com.paperless.scanner.data.repository.CorrespondentRepository
import com.paperless.scanner.data.repository.DocumentRepository
import com.paperless.scanner.data.repository.TagRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
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
    private val documentRepository: DocumentRepository,
    private val tagRepository: TagRepository,
    private val correspondentRepository: CorrespondentRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DocumentsUiState())
    val uiState: StateFlow<DocumentsUiState> = _uiState.asStateFlow()

    private var allDocuments: List<DocumentItem> = emptyList()
    private var tagMap: Map<Int, Tag> = emptyMap()
    private var correspondentMap: Map<Int, Correspondent> = emptyMap()

    init {
        loadInitialData()
    }

    private fun loadInitialData() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            // Load tags and correspondents first for name lookups
            val tagsResult = tagRepository.getTags()
            val correspondentsResult = correspondentRepository.getCorrespondents()

            tagsResult.onSuccess { tags ->
                tagMap = tags.associateBy { it.id }
                _uiState.value = _uiState.value.copy(availableTags = tags)
            }

            correspondentsResult.onSuccess { correspondents ->
                correspondentMap = correspondents.associateBy { it.id }
            }

            // Now load documents
            loadDocuments()
        }
    }

    fun loadDocuments() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            val query = _uiState.value.searchQuery.takeIf { it.isNotBlank() }
            val tagIds = _uiState.value.activeTagFilter?.let { listOf(it) }

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
                        date = formatDate(doc.created),
                        correspondent = doc.correspondentId?.let { correspondentMap[it]?.name },
                        tags = doc.tags.mapNotNull { tagMap[it]?.name }
                    )
                }
                _uiState.value = _uiState.value.copy(
                    documents = allDocuments,
                    isLoading = false,
                    totalCount = response.count,
                    currentPage = 1,
                    hasMorePages = response.next != null
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = error.message ?: "Fehler beim Laden"
                )
            }
        }
    }

    fun loadNextPage() {
        if (_uiState.value.isLoading || !_uiState.value.hasMorePages) return

        viewModelScope.launch {
            val nextPage = _uiState.value.currentPage + 1
            val query = _uiState.value.searchQuery.takeIf { it.isNotBlank() }
            val tagIds = _uiState.value.activeTagFilter?.let { listOf(it) }

            documentRepository.getDocuments(
                page = nextPage,
                pageSize = 25,
                query = query,
                tagIds = tagIds
            ).onSuccess { response ->
                val newDocuments = response.results.map { doc ->
                    DocumentItem(
                        id = doc.id,
                        title = doc.title,
                        date = formatDate(doc.created),
                        correspondent = doc.correspondentId?.let { correspondentMap[it]?.name },
                        tags = doc.tags.mapNotNull { tagMap[it]?.name }
                    )
                }
                allDocuments = allDocuments + newDocuments
                _uiState.value = _uiState.value.copy(
                    documents = allDocuments,
                    currentPage = nextPage,
                    hasMorePages = response.next != null
                )
            }
        }
    }

    fun search(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        loadDocuments()
    }

    fun filterByTag(tagId: Int?) {
        _uiState.value = _uiState.value.copy(activeTagFilter = tagId)
        loadDocuments()
    }

    fun refresh() {
        loadInitialData()
    }

    private fun formatDate(dateString: String): String {
        return try {
            val inputFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault())
            val outputFormat = SimpleDateFormat("dd.MM.yyyy", Locale.GERMAN)
            val date = inputFormat.parse(dateString)
            date?.let { outputFormat.format(it) } ?: dateString
        } catch (e: Exception) {
            dateString.take(10)
        }
    }
}
