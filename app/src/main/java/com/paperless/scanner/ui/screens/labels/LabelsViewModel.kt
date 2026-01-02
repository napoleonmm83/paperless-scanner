package com.paperless.scanner.ui.screens.labels

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.data.repository.TagRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale
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
    }

    fun loadLabels() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)

            tagRepository.getTags().onSuccess { tags ->
                allLabels = tags.map { tag ->
                    LabelItem(
                        id = tag.id,
                        name = tag.name,
                        color = parseColor(tag.color),
                        documentCount = tag.documentCount ?: 0
                    )
                }

                _uiState.value = _uiState.value.copy(
                    labels = allLabels,
                    isLoading = false
                )
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = error.message ?: "Fehler beim Laden"
                )
            }
        }
    }

    fun search(query: String) {
        _uiState.value = _uiState.value.copy(searchQuery = query)

        val filtered = if (query.isBlank()) {
            allLabels
        } else {
            allLabels.filter { it.name.contains(query, ignoreCase = true) }
        }

        _uiState.value = _uiState.value.copy(labels = filtered)
    }

    fun createLabel(name: String, color: Color) {
        viewModelScope.launch {
            val colorHex = colorToHex(color)
            tagRepository.createTag(name, colorHex).onSuccess {
                loadLabels()
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    error = error.message ?: "Fehler beim Erstellen"
                )
            }
        }
    }

    fun updateLabel(id: Int, name: String, color: Color) {
        viewModelScope.launch {
            val colorHex = colorToHex(color)
            tagRepository.updateTag(id, name, colorHex).onSuccess {
                loadLabels()
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    error = error.message ?: "Fehler beim Aktualisieren"
                )
            }
        }
    }

    fun deleteLabel(id: Int) {
        viewModelScope.launch {
            tagRepository.deleteTag(id).onSuccess {
                allLabels = allLabels.filter { it.id != id }
                _uiState.value = _uiState.value.copy(labels = allLabels)
            }.onFailure { error ->
                _uiState.value = _uiState.value.copy(
                    error = error.message ?: "Fehler beim Löschen"
                )
            }
        }
    }

    fun loadDocumentsForLabel(labelId: Int) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingDocuments = true)

            tagRepository.getDocumentsForTag(labelId).onSuccess { documents ->
                val labelDocs = documents.map { doc ->
                    LabelDocument(
                        id = doc.id,
                        title = doc.title,
                        date = formatDate(doc.created),
                        pageCount = 1 // API doesn't provide page count
                    )
                }
                _uiState.value = _uiState.value.copy(
                    documentsForLabel = labelDocs,
                    isLoadingDocuments = false
                )
            }.onFailure {
                _uiState.value = _uiState.value.copy(
                    documentsForLabel = emptyList(),
                    isLoadingDocuments = false
                )
            }
        }
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
}
