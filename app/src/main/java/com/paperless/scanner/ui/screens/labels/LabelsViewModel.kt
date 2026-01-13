package com.paperless.scanner.ui.screens.labels

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.R
import com.paperless.scanner.data.repository.TagRepository
import com.paperless.scanner.util.DateFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LabelSortOption {
    NAME_ASC,
    NAME_DESC,
    COUNT_DESC,
    COUNT_ASC,
    NEWEST,
    OLDEST
}

enum class LabelFilterOption {
    ALL,
    WITH_DOCUMENTS,
    EMPTY,
    MANY_DOCUMENTS
}

data class LabelsUiState(
    val labels: List<LabelItem> = emptyList(),
    val documentsForLabel: List<LabelDocument> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingDocuments: Boolean = false,
    val error: String? = null,
    val searchQuery: String = "",
    val sortOption: LabelSortOption = LabelSortOption.NAME_ASC,
    val filterOption: LabelFilterOption = LabelFilterOption.ALL
)

@HiltViewModel
class LabelsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tagRepository: TagRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(LabelsUiState())
    val uiState: StateFlow<LabelsUiState> = _uiState.asStateFlow()

    private var allLabels: List<LabelItem> = emptyList()

    init {
        loadLabels()
        observeTagsReactively()
    }

    /**
     * BEST PRACTICE: Reactive Flow for tags.
     * Automatically updates labels when tags are added/modified/deleted.
     * No manual refresh needed after create/update/delete operations!
     */
    private fun observeTagsReactively() {
        viewModelScope.launch {
            tagRepository.observeTags().collect { tags ->
                allLabels = tags.map { tag ->
                    LabelItem(
                        id = tag.id,
                        name = tag.name,
                        color = parseColor(tag.color),
                        documentCount = tag.documentCount ?: 0
                    )
                }

                // Apply current search, filter, and sort
                val processed = applySearchFilterSort(allLabels, _uiState.value)

                _uiState.update {
                    it.copy(
                        labels = processed,
                        isLoading = false
                    )
                }
            }
        }
    }

    private fun applySearchFilterSort(
        labels: List<LabelItem>,
        state: LabelsUiState
    ): List<LabelItem> {
        // 1. Apply search
        var result = if (state.searchQuery.isBlank()) {
            labels
        } else {
            labels.filter { it.name.contains(state.searchQuery, ignoreCase = true) }
        }

        // 2. Apply filter
        result = when (state.filterOption) {
            LabelFilterOption.ALL -> result
            LabelFilterOption.WITH_DOCUMENTS -> result.filter { it.documentCount > 0 }
            LabelFilterOption.EMPTY -> result.filter { it.documentCount == 0 }
            LabelFilterOption.MANY_DOCUMENTS -> result.filter { it.documentCount > 5 }
        }

        // 3. Apply sort
        result = when (state.sortOption) {
            LabelSortOption.NAME_ASC -> result.sortedBy { it.name.lowercase() }
            LabelSortOption.NAME_DESC -> result.sortedByDescending { it.name.lowercase() }
            LabelSortOption.COUNT_DESC -> result.sortedByDescending { it.documentCount }
            LabelSortOption.COUNT_ASC -> result.sortedBy { it.documentCount }
            LabelSortOption.NEWEST -> result.sortedByDescending { it.id } // ID as proxy for creation time
            LabelSortOption.OLDEST -> result.sortedBy { it.id }
        }

        return result
    }

    fun loadLabels() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            tagRepository.getTags().onSuccess { tags ->
                allLabels = tags.map { tag ->
                    LabelItem(
                        id = tag.id,
                        name = tag.name,
                        color = parseColor(tag.color),
                        documentCount = tag.documentCount ?: 0
                    )
                }

                _uiState.update {
                    it.copy(
                        labels = allLabels,
                        isLoading = false
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

    fun search(query: String) {
        val newState = _uiState.value.copy(searchQuery = query)
        val processed = applySearchFilterSort(allLabels, newState)
        _uiState.update { newState.copy(labels = processed) }
    }

    fun setSortOption(option: LabelSortOption) {
        val newState = _uiState.value.copy(sortOption = option)
        val processed = applySearchFilterSort(allLabels, newState)
        _uiState.update { newState.copy(labels = processed) }
    }

    fun setFilterOption(option: LabelFilterOption) {
        val newState = _uiState.value.copy(filterOption = option)
        val processed = applySearchFilterSort(allLabels, newState)
        _uiState.update { newState.copy(labels = processed) }
    }

    fun setSortAndFilter(sort: LabelSortOption, filter: LabelFilterOption) {
        val newState = _uiState.value.copy(sortOption = sort, filterOption = filter)
        val processed = applySearchFilterSort(allLabels, newState)
        _uiState.update { newState.copy(labels = processed) }
    }

    fun resetSortAndFilter() {
        val newState = _uiState.value.copy(
            sortOption = LabelSortOption.NAME_ASC,
            filterOption = LabelFilterOption.ALL
        )
        val processed = applySearchFilterSort(allLabels, newState)
        _uiState.update { newState.copy(labels = processed) }
    }

    fun createLabel(name: String, color: Color) {
        viewModelScope.launch {
            val colorHex = colorToHex(color)
            tagRepository.createTag(name, colorHex).onSuccess {
                // BEST PRACTICE: No manual refresh needed!
                // observeTagsReactively() automatically updates UI.
            }.onFailure { error ->
                _uiState.update {
                    it.copy(error = error.message ?: context.getString(R.string.error_creating))
                }
            }
        }
    }

    fun updateLabel(id: Int, name: String, color: Color) {
        viewModelScope.launch {
            val colorHex = colorToHex(color)
            tagRepository.updateTag(id, name, colorHex).onSuccess {
                // BEST PRACTICE: No manual refresh needed!
                // observeTagsReactively() automatically updates UI.
            }.onFailure { error ->
                _uiState.update {
                    it.copy(error = error.message ?: context.getString(R.string.error_updating))
                }
            }
        }
    }

    fun deleteLabel(id: Int) {
        viewModelScope.launch {
            tagRepository.deleteTag(id).onSuccess {
                // BEST PRACTICE: No manual refresh needed!
                // observeTagsReactively() automatically updates UI.
            }.onFailure { error ->
                _uiState.update {
                    it.copy(error = error.message ?: context.getString(R.string.error_deleting))
                }
            }
        }
    }

    fun loadDocumentsForLabel(labelId: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingDocuments = true) }

            tagRepository.getDocumentsForTag(labelId).onSuccess { documents ->
                val labelDocs = documents.map { doc ->
                    LabelDocument(
                        id = doc.id,
                        title = doc.title,
                        date = DateFormatter.formatDateShort(doc.created),
                        pageCount = 1 // API doesn't provide page count
                    )
                }
                _uiState.update {
                    it.copy(
                        documentsForLabel = labelDocs,
                        isLoadingDocuments = false
                    )
                }
            }.onFailure {
                _uiState.update {
                    it.copy(
                        documentsForLabel = emptyList(),
                        isLoadingDocuments = false
                    )
                }
            }
        }
    }


    private fun parseColor(colorString: String?): Color {
        if (colorString == null) return labelColorOptions.first()

        return try {
            if (colorString.startsWith("#")) {
                Color(android.graphics.Color.parseColor(colorString))
            } else {
                labelColorOptions.first()
            }
        } catch (e: Exception) {
            labelColorOptions.first()
        }
    }

    private fun colorToHex(color: Color): String {
        val red = (color.red * 255).toInt()
        val green = (color.green * 255).toInt()
        val blue = (color.blue * 255).toInt()
        return String.format("#%02X%02X%02X", red, green, blue)
    }

    fun clearDocumentsForLabel() {
        _uiState.update { it.copy(documentsForLabel = emptyList()) }
    }

    fun clearSearch() {
        _uiState.update { it.copy(searchQuery = "", labels = allLabels) }
    }

    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    fun resetState() {
        _uiState.update { LabelsUiState() }
        allLabels = emptyList()
        loadLabels()
    }
}
