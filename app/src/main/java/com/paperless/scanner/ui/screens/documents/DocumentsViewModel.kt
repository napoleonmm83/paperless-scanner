package com.paperless.scanner.ui.screens.documents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.data.repository.DocumentRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DocumentsUiState(
    val documents: List<DocumentItem> = emptyList(),
    val filteredDocuments: List<DocumentItem> = emptyList(),
    val isLoading: Boolean = true,
    val searchQuery: String = "",
    val activeFilter: String? = null
)

@HiltViewModel
class DocumentsViewModel @Inject constructor(
    private val documentRepository: DocumentRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(DocumentsUiState())
    val uiState: StateFlow<DocumentsUiState> = _uiState.asStateFlow()

    private var allDocuments: List<DocumentItem> = emptyList()

    init {
        loadDocuments()
    }

    fun loadDocuments() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)

            try {
                // TODO: Implement actual document loading from API
                // For now, we show an empty list
                allDocuments = emptyList()
                _uiState.value = DocumentsUiState(
                    documents = allDocuments,
                    isLoading = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    fun search(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)
        applyFilters()
    }

    fun filter(filter: String?) {
        _uiState.value = _uiState.value.copy(activeFilter = filter)
        applyFilters()
    }

    private fun applyFilters() {
        val query = _uiState.value.searchQuery
        val filter = _uiState.value.activeFilter

        var filtered = allDocuments

        // Apply search
        if (query.isNotBlank()) {
            filtered = filtered.filter { doc ->
                doc.title.contains(query, ignoreCase = true) ||
                        doc.correspondent?.contains(query, ignoreCase = true) == true ||
                        doc.tags.any { it.contains(query, ignoreCase = true) }
            }
        }

        // Apply filter
        if (filter != null) {
            filtered = filtered.filter { doc ->
                doc.tags.any { it.equals(filter, ignoreCase = true) }
            }
        }

        _uiState.value = _uiState.value.copy(documents = filtered)
    }
}
