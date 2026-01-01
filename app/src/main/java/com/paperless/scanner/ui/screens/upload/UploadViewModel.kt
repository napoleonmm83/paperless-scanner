package com.paperless.scanner.ui.screens.upload

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.data.api.models.Correspondent
import com.paperless.scanner.data.api.models.DocumentType
import com.paperless.scanner.data.api.models.Tag
import com.paperless.scanner.data.repository.CorrespondentRepository
import com.paperless.scanner.data.repository.DocumentRepository
import com.paperless.scanner.data.repository.DocumentTypeRepository
import com.paperless.scanner.data.repository.TagRepository
import com.paperless.scanner.util.NetworkUtils
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
    private val tagRepository: TagRepository,
    private val documentTypeRepository: DocumentTypeRepository,
    private val correspondentRepository: CorrespondentRepository,
    private val networkUtils: NetworkUtils
) : ViewModel() {

    private val _uiState = MutableStateFlow<UploadUiState>(UploadUiState.Idle)
    val uiState: StateFlow<UploadUiState> = _uiState.asStateFlow()

    private val _tags = MutableStateFlow<List<Tag>>(emptyList())
    val tags: StateFlow<List<Tag>> = _tags.asStateFlow()

    private val _documentTypes = MutableStateFlow<List<DocumentType>>(emptyList())
    val documentTypes: StateFlow<List<DocumentType>> = _documentTypes.asStateFlow()

    private val _correspondents = MutableStateFlow<List<Correspondent>>(emptyList())
    val correspondents: StateFlow<List<Correspondent>> = _correspondents.asStateFlow()

    private val _createTagState = MutableStateFlow<CreateTagState>(CreateTagState.Idle)
    val createTagState: StateFlow<CreateTagState> = _createTagState.asStateFlow()

    // Store last upload params for retry
    private var lastUploadParams: UploadParams? = null

    fun loadTags() {
        viewModelScope.launch(Dispatchers.IO) {
            Log.d(TAG, "Loading tags...")
            tagRepository.getTags()
                .onSuccess { tagList ->
                    Log.d(TAG, "Tags loaded: ${tagList.size}")
                    _tags.value = tagList.sortedBy { it.name.lowercase() }
                }
                .onFailure { e ->
                    Log.e(TAG, "Failed to load tags", e)
                }
        }
    }

    fun loadDocumentTypes() {
        viewModelScope.launch(Dispatchers.IO) {
            Log.d(TAG, "Loading document types...")
            documentTypeRepository.getDocumentTypes()
                .onSuccess { types ->
                    Log.d(TAG, "Document types loaded: ${types.size}")
                    _documentTypes.value = types.sortedBy { it.name.lowercase() }
                }
                .onFailure { e ->
                    Log.e(TAG, "Failed to load document types", e)
                }
        }
    }

    fun loadCorrespondents() {
        viewModelScope.launch(Dispatchers.IO) {
            Log.d(TAG, "Loading correspondents...")
            correspondentRepository.getCorrespondents()
                .onSuccess { correspondentList ->
                    Log.d(TAG, "Correspondents loaded: ${correspondentList.size}")
                    _correspondents.value = correspondentList.sortedBy { it.name.lowercase() }
                }
                .onFailure { e ->
                    Log.e(TAG, "Failed to load correspondents", e)
                }
        }
    }

    companion object {
        private const val TAG = "UploadViewModel"
    }

    fun uploadDocument(
        uri: Uri,
        title: String? = null,
        tagIds: List<Int> = emptyList(),
        documentTypeId: Int? = null,
        correspondentId: Int? = null
    ) {
        // Store params for potential retry
        lastUploadParams = UploadParams.Single(uri, title, tagIds, documentTypeId, correspondentId)

        viewModelScope.launch(Dispatchers.IO) {
            // Check network availability
            if (!networkUtils.isNetworkAvailable()) {
                _uiState.value = UploadUiState.Error("Keine Netzwerkverbindung")
                return@launch
            }

            _uiState.value = UploadUiState.Uploading(0f)

            documentRepository.uploadDocument(
                uri = uri,
                title = title,
                tagIds = tagIds,
                documentTypeId = documentTypeId,
                correspondentId = correspondentId,
                onProgress = { progress ->
                    _uiState.value = UploadUiState.Uploading(progress)
                }
            )
                .onSuccess { taskId ->
                    lastUploadParams = null
                    _uiState.value = UploadUiState.Success(taskId)
                }
                .onFailure { exception ->
                    _uiState.value = UploadUiState.Error(
                        exception.message ?: "Upload fehlgeschlagen"
                    )
                }
        }
    }

    fun uploadMultiPageDocument(
        uris: List<Uri>,
        title: String? = null,
        tagIds: List<Int> = emptyList(),
        documentTypeId: Int? = null,
        correspondentId: Int? = null
    ) {
        // Store params for potential retry
        lastUploadParams = UploadParams.MultiPage(uris, title, tagIds, documentTypeId, correspondentId)

        viewModelScope.launch(Dispatchers.IO) {
            // Check network availability
            if (!networkUtils.isNetworkAvailable()) {
                _uiState.value = UploadUiState.Error("Keine Netzwerkverbindung")
                return@launch
            }

            _uiState.value = UploadUiState.Uploading(0f)

            documentRepository.uploadMultiPageDocument(
                uris = uris,
                title = title,
                tagIds = tagIds,
                documentTypeId = documentTypeId,
                correspondentId = correspondentId,
                onProgress = { progress ->
                    _uiState.value = UploadUiState.Uploading(progress)
                }
            )
                .onSuccess { taskId ->
                    lastUploadParams = null
                    _uiState.value = UploadUiState.Success(taskId)
                }
                .onFailure { exception ->
                    _uiState.value = UploadUiState.Error(
                        exception.message ?: "Upload fehlgeschlagen"
                    )
                }
        }
    }

    fun retry() {
        when (val params = lastUploadParams) {
            is UploadParams.Single -> uploadDocument(
                uri = params.uri,
                title = params.title,
                tagIds = params.tagIds,
                documentTypeId = params.documentTypeId,
                correspondentId = params.correspondentId
            )
            is UploadParams.MultiPage -> uploadMultiPageDocument(
                uris = params.uris,
                title = params.title,
                tagIds = params.tagIds,
                documentTypeId = params.documentTypeId,
                correspondentId = params.correspondentId
            )
            null -> {
                Log.w(TAG, "No upload params to retry")
            }
        }
    }

    fun canRetry(): Boolean = lastUploadParams != null

    fun resetState() {
        _uiState.value = UploadUiState.Idle
    }

    fun createTag(name: String, color: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            _createTagState.value = CreateTagState.Creating

            tagRepository.createTag(name = name, color = color)
                .onSuccess { newTag ->
                    Log.d(TAG, "Tag created: ${newTag.name}")
                    // Add new tag to list and sort
                    val updatedTags = (_tags.value + newTag).sortedBy { it.name.lowercase() }
                    _tags.value = updatedTags
                    _createTagState.value = CreateTagState.Success(newTag)
                }
                .onFailure { e ->
                    Log.e(TAG, "Failed to create tag", e)
                    _createTagState.value = CreateTagState.Error(
                        e.message ?: "Tag konnte nicht erstellt werden"
                    )
                }
        }
    }

    fun resetCreateTagState() {
        _createTagState.value = CreateTagState.Idle
    }
}

sealed class UploadParams {
    data class Single(
        val uri: Uri,
        val title: String?,
        val tagIds: List<Int>,
        val documentTypeId: Int?,
        val correspondentId: Int?
    ) : UploadParams()

    data class MultiPage(
        val uris: List<Uri>,
        val title: String?,
        val tagIds: List<Int>,
        val documentTypeId: Int?,
        val correspondentId: Int?
    ) : UploadParams()
}

sealed class UploadUiState {
    data object Idle : UploadUiState()
    data class Uploading(val progress: Float = 0f) : UploadUiState()
    data class Success(val taskId: String) : UploadUiState()
    data class Error(val message: String) : UploadUiState()
}

sealed class CreateTagState {
    data object Idle : CreateTagState()
    data object Creating : CreateTagState()
    data class Success(val tag: com.paperless.scanner.data.api.models.Tag) : CreateTagState()
    data class Error(val message: String) : CreateTagState()
}
