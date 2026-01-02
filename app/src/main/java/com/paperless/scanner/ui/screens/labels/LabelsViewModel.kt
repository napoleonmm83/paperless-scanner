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
import javax.inject.Inject

data class LabelsUiState(
    val labels: List<LabelItem> = emptyList(),
    val documentsForLabel: List<LabelDocument> = emptyList(),
    val isLoading: Boolean = true,
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
            _uiState.value = _uiState.value.copy(isLoading = true)

            try {
                val tags = tagRepository.getTags()
                allLabels = tags.getOrNull()?.map { tag ->
                    LabelItem(
                        id = tag.id,
                        name = tag.name,
                        color = parseColor(tag.color),
                        documentCount = 0 // TODO: Fetch document count from API
                    )
                } ?: emptyList()

                _uiState.value = LabelsUiState(
                    labels = allLabels,
                    isLoading = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false)
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
            try {
                val colorHex = colorToHex(color)
                tagRepository.createTag(name, colorHex)
                loadLabels()
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    fun updateLabel(id: Int, name: String, color: Color) {
        // TODO: Implement update tag API
        // For now, just reload labels
        loadLabels()
    }

    fun deleteLabel(id: Int) {
        // TODO: Implement delete tag API
        // For now, remove locally
        allLabels = allLabels.filter { it.id != id }
        _uiState.value = _uiState.value.copy(labels = allLabels)
    }

    fun loadDocumentsForLabel(labelId: Int) {
        viewModelScope.launch {
            // TODO: Implement API call to fetch documents with this tag
            _uiState.value = _uiState.value.copy(documentsForLabel = emptyList())
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
