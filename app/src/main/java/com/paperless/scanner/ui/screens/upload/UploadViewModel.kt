package com.paperless.scanner.ui.screens.upload

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.data.api.models.Tag
import com.paperless.scanner.data.repository.DocumentRepository
import com.paperless.scanner.data.repository.TagRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UploadViewModel @Inject constructor(
    private val documentRepository: DocumentRepository,
    private val tagRepository: TagRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<UploadUiState>(UploadUiState.Idle)
    val uiState: StateFlow<UploadUiState> = _uiState.asStateFlow()

    private val _tags = MutableStateFlow<List<Tag>>(emptyList())
    val tags: StateFlow<List<Tag>> = _tags.asStateFlow()

    fun loadTags() {
        viewModelScope.launch(Dispatchers.IO) {
            tagRepository.getTags()
                .onSuccess { tagList ->
                    _tags.value = tagList.sortedBy { it.name.lowercase() }
                }
                .onFailure {
                    // Tags loading failed silently - user can still upload without tags
                }
        }
    }

    fun uploadDocument(
        uri: Uri,
        title: String? = null,
        tagIds: List<Int> = emptyList()
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = UploadUiState.Uploading

            documentRepository.uploadDocument(
                uri = uri,
                title = title,
                tagIds = tagIds
            )
                .onSuccess { taskId ->
                    _uiState.value = UploadUiState.Success(taskId)
                }
                .onFailure { exception ->
                    _uiState.value = UploadUiState.Error(
                        exception.message ?: "Upload fehlgeschlagen"
                    )
                }
        }
    }

    fun resetState() {
        _uiState.value = UploadUiState.Idle
    }
}

sealed class UploadUiState {
    data object Idle : UploadUiState()
    data object Uploading : UploadUiState()
    data class Success(val taskId: String) : UploadUiState()
    data class Error(val message: String) : UploadUiState()
}
