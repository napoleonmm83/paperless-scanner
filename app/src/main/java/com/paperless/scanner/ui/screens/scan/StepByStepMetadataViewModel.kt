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
import com.paperless.scanner.ui.screens.upload.CreateCorrespondentState
import com.paperless.scanner.ui.screens.upload.usecase.CreateCorrespondentResult
import com.paperless.scanner.ui.screens.upload.usecase.UploadMetadataUseCase
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
    val pageMetadata: Map<String, PageMetadata> = emptyMap(),  // pageId -> metadata
    // #296: tag-creation dialog state. The created tag appears in [tags]
    // automatically via the reactive Room flow; the dialog stays open on
    // failure so the user can retry.
    val showCreateTagDialog: Boolean = false,
    val isCreatingTag: Boolean = false,
    val createTagFailed: Boolean = false,
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
    private val correspondentRepository: CorrespondentRepository,
    private val uploadMetadataUseCase: UploadMetadataUseCase,
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

    private val _createCorrespondentState = MutableStateFlow<CreateCorrespondentState>(CreateCorrespondentState.Idle)
    val createCorrespondentState: StateFlow<CreateCorrespondentState> = _createCorrespondentState.asStateFlow()

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
        _uiState.update { state ->
            if (state.pages.isEmpty()) state.copy(pages = pages) else state
        }
    }

    fun openCreateTagDialog() {
        _uiState.update { it.copy(showCreateTagDialog = true, createTagFailed = false) }
    }

    fun closeCreateTagDialog() {
        _uiState.update { it.copy(showCreateTagDialog = false, createTagFailed = false) }
    }

    /**
     * #296: creates a tag inline from the step-by-step flow. On success the new
     * tag shows up in [tags] via the reactive Room flow — no manual refresh.
     */
    fun createTag(name: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isCreatingTag = true, createTagFailed = false) }
            tagRepository.createTag(name).fold(
                onSuccess = {
                    _uiState.update {
                        it.copy(isCreatingTag = false, showCreateTagDialog = false)
                    }
                },
                onFailure = {
                    _uiState.update { it.copy(isCreatingTag = false, createTagFailed = true) }
                },
            )
        }
    }

    /** Inline creation from the correspondent dropdown; same use-case path as the upload screens. */
    fun createCorrespondent(name: String) {
        viewModelScope.launch {
            _createCorrespondentState.update { CreateCorrespondentState.Creating }
            val next = when (val result = uploadMetadataUseCase.createCorrespondent(name)) {
                is CreateCorrespondentResult.Success -> CreateCorrespondentState.Success(result.correspondent)
                is CreateCorrespondentResult.Failure -> CreateCorrespondentState.Error(result.message)
            }
            _createCorrespondentState.update { next }
        }
    }

    fun resetCreateCorrespondentState() {
        _createCorrespondentState.update { CreateCorrespondentState.Idle }
    }

    fun nextPage() {
        resetCreateCorrespondentState() // a page-1 error must not show on page 2
        _uiState.update { state ->
            if (state.hasNext) {
                val newIndex = state.currentPageIndex + 1
                savedStateHandle[KEY_CURRENT_INDEX] = newIndex
                state.copy(currentPageIndex = newIndex)
            } else {
                state
            }
        }
    }

    fun previousPage() {
        resetCreateCorrespondentState()
        _uiState.update { state ->
            if (state.hasPrevious) {
                val newIndex = state.currentPageIndex - 1
                savedStateHandle[KEY_CURRENT_INDEX] = newIndex
                state.copy(currentPageIndex = newIndex)
            } else {
                state
            }
        }
    }

    fun updateCurrentPageMetadata(metadata: PageMetadata) {
        _uiState.update { state ->
            val currentPage = state.currentPage ?: return@update state
            val updatedMetadata = state.pageMetadata.toMutableMap()
            updatedMetadata[currentPage.id] = metadata
            state.copy(pageMetadata = updatedMetadata)
        }
    }

    fun getCurrentPageMetadata(): PageMetadata? {
        val currentPage = _uiState.value.currentPage ?: return null
        return _uiState.value.pageMetadata[currentPage.id]
    }
}
