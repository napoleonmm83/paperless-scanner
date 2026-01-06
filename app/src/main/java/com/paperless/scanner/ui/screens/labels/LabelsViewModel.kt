package com.paperless.scanner.ui.screens.labels

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.data.repository.TagRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.inject.Inject

data class LabelsUiState(
    val labels: List<LabelItem> = emptyList(),
    val documentsForLabel: List<LabelDocument> = emptyList(),
    val isLoading: Boolean = true,
    val isLoadingDocuments: Boolean = false,
    val error: String? = null,
    val searchQuery: String = ""
)

@HiltViewModel
class LabelsViewModel @Inject constructor(
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

                // Apply current search filter
                val filtered = if (_uiState.value.searchQuery.isBlank()) {
                    allLabels
                } else {
                    allLabels.filter {
                        it.name.contains(_uiState.value.searchQuery, ignoreCase = true)
                    }
                }

                _uiState.update {
                    it.copy(
                        labels = filtered,
                        isLoading = false
                    )
                }
            }
        }
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
                        error = error.message ?: "Fehler beim Laden"
                    )
                }
            }
        }
    }

    fun search(query: String) {
        val filtered = if (query.isBlank()) {
            allLabels
        } else {
            allLabels.filter { it.name.contains(query, ignoreCase = true) }
        }

        _uiState.update { it.copy(searchQuery = query, labels = filtered) }
    }

    fun createLabel(name: String, color: Color) {
        viewModelScope.launch {
            val colorHex = colorToHex(color)
            tagRepository.createTag(name, colorHex).onSuccess {
                // BEST PRACTICE: No manual refresh needed!
                // observeTagsReactively() automatically updates UI.
            }.onFailure { error ->
                _uiState.update {
                    it.copy(error = error.message ?: "Fehler beim Erstellen")
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
                    it.copy(error = error.message ?: "Fehler beim Aktualisieren")
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
                    it.copy(error = error.message ?: "Fehler beim Löschen")
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
                        date = formatDate(doc.created),
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

    private fun formatDate(dateString: String): String {
        return try {
            val inputFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
            val outputFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy")
            val dateTime = LocalDateTime.parse(dateString.take(19), inputFormatter)
            dateTime.format(outputFormatter)
        } catch (e: DateTimeParseException) {
            dateString.take(10)
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
