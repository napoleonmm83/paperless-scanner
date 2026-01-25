package com.paperless.scanner.ui.screens.scan

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.data.repository.TagRepository
import com.paperless.scanner.data.repository.DocumentTypeRepository
import com.paperless.scanner.data.repository.CorrespondentRepository
import com.paperless.scanner.domain.model.Tag
import com.paperless.scanner.domain.model.DocumentType
import com.paperless.scanner.domain.model.Correspondent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class StepByStepMetadataUiState(
    val currentPageIndex: Int = 0,
    val pages: List<ScannedPage> = emptyList(),
    val pageMetadata: Map<String, PageMetadata> = emptyMap()  // pageId -> metadata
) {
    val currentPage: ScannedPage? get() = pages.getOrNull(currentPageIndex)
    val totalPages: Int get() = pages.size
    val hasNext: Boolean get() = currentPageIndex < pages.size - 1
    val hasPrevious: Boolean get() = currentPageIndex > 0
    val isLastPage: Boolean get() = currentPageIndex == pages.size - 1
}

@HiltViewModel
class StepByStepMetadataViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val tagRepository: TagRepository,
    private val documentTypeRepository: DocumentTypeRepository,
    private val correspondentRepository: CorrespondentRepository
) : ViewModel() {

    companion object {
        private const val KEY_CURRENT_INDEX = "currentPageIndex"
        private const val KEY_PAGE_METADATA = "pageMetadata"
    }

    private val _uiState = MutableStateFlow(StepByStepMetadataUiState())
    val uiState: StateFlow<StepByStepMetadataUiState> = _uiState.asStateFlow()

    // Reactive metadata collections
    private val _tags = MutableStateFlow<List<Tag>>(emptyList())
    val tags: StateFlow<List<Tag>> = _tags.asStateFlow()

    private val _documentTypes = MutableStateFlow<List<DocumentType>>(emptyList())
    val documentTypes: StateFlow<List<DocumentType>> = _documentTypes.asStateFlow()

    private val _correspondents = MutableStateFlow<List<Correspondent>>(emptyList())
    val correspondents: StateFlow<List<Correspondent>> = _correspondents.asStateFlow()

    init {
        observeTagsReactively()
        observeDocumentTypesReactively()
        observeCorrespondentsReactively()
    }

    private fun observeTagsReactively() {
        viewModelScope.launch {
            tagRepository.observeTags().collect { tagList ->
                _tags.update { tagList.sortedBy { it.name.lowercase() } }
            }
        }
    }

    private fun observeDocumentTypesReactively() {
        viewModelScope.launch {
            documentTypeRepository.observeDocumentTypes().collect { typeList ->
                _documentTypes.update { typeList.sortedBy { it.name.lowercase() } }
            }
        }
    }

    private fun observeCorrespondentsReactively() {
        viewModelScope.launch {
            correspondentRepository.observeCorrespondents().collect { corrList ->
                _correspondents.update { corrList.sortedBy { it.name.lowercase() } }
            }
        }
    }

    fun initializePages(pages: List<ScannedPage>) {
        if (_uiState.value.pages.isEmpty()) {
            _uiState.value = _uiState.value.copy(pages = pages)
        }
    }

    fun nextPage() {
        if (_uiState.value.hasNext) {
            val newIndex = _uiState.value.currentPageIndex + 1
            _uiState.value = _uiState.value.copy(currentPageIndex = newIndex)
            savedStateHandle[KEY_CURRENT_INDEX] = newIndex
        }
    }

    fun previousPage() {
        if (_uiState.value.hasPrevious) {
            val newIndex = _uiState.value.currentPageIndex - 1
            _uiState.value = _uiState.value.copy(currentPageIndex = newIndex)
            savedStateHandle[KEY_CURRENT_INDEX] = newIndex
        }
    }

    fun updateCurrentPageMetadata(metadata: PageMetadata) {
        val currentPage = _uiState.value.currentPage ?: return
        val updatedMetadata = _uiState.value.pageMetadata.toMutableMap()
        updatedMetadata[currentPage.id] = metadata
        _uiState.value = _uiState.value.copy(pageMetadata = updatedMetadata)
    }

    fun getCurrentPageMetadata(): PageMetadata? {
        val currentPage = _uiState.value.currentPage ?: return null
        return _uiState.value.pageMetadata[currentPage.id]
    }
}
