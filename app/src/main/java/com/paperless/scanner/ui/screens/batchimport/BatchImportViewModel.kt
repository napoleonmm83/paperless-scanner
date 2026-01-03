package com.paperless.scanner.ui.screens.batchimport

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.domain.model.Correspondent
import com.paperless.scanner.domain.model.DocumentType
import com.paperless.scanner.domain.model.Tag
import com.paperless.scanner.data.repository.CorrespondentRepository
import com.paperless.scanner.data.repository.DocumentTypeRepository
import com.paperless.scanner.data.repository.TagRepository
import com.paperless.scanner.data.repository.UploadQueueRepository
import com.paperless.scanner.worker.UploadWorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BatchImportViewModel @Inject constructor(
    private val uploadQueueRepository: UploadQueueRepository,
    private val uploadWorkManager: UploadWorkManager,
    private val tagRepository: TagRepository,
    private val documentTypeRepository: DocumentTypeRepository,
    private val correspondentRepository: CorrespondentRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<BatchImportUiState>(BatchImportUiState.Idle)
    val uiState: StateFlow<BatchImportUiState> = _uiState.asStateFlow()

    private val _tags = MutableStateFlow<List<Tag>>(emptyList())
    val tags: StateFlow<List<Tag>> = _tags.asStateFlow()

    private val _documentTypes = MutableStateFlow<List<DocumentType>>(emptyList())
    val documentTypes: StateFlow<List<DocumentType>> = _documentTypes.asStateFlow()

    private val _correspondents = MutableStateFlow<List<Correspondent>>(emptyList())
    val correspondents: StateFlow<List<Correspondent>> = _correspondents.asStateFlow()

    fun loadData() {
        viewModelScope.launch(Dispatchers.IO) {
            tagRepository.getTags()
                .onSuccess { _tags.value = it.sortedBy { tag -> tag.name.lowercase() } }

            documentTypeRepository.getDocumentTypes()
                .onSuccess { _documentTypes.value = it.sortedBy { type -> type.name.lowercase() } }

            correspondentRepository.getCorrespondents()
                .onSuccess { _correspondents.value = it.sortedBy { c -> c.name.lowercase() } }
        }
    }

    fun queueBatchImport(
        imageUris: List<Uri>,
        tagIds: List<Int>,
        documentTypeId: Int?,
        correspondentId: Int?,
        uploadAsSingleDocument: Boolean = false,
        uploadImmediately: Boolean = true
    ) {
        Log.d(TAG, "queueBatchImport called with ${imageUris.size} images, asSingle=$uploadAsSingleDocument, uploadImmediately=$uploadImmediately")
        if (imageUris.isEmpty()) {
            Log.d(TAG, "No images to import, returning")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                if (uploadAsSingleDocument) {
                    // Alle Bilder als ein Multi-Page Dokument
                    _uiState.value = BatchImportUiState.Queuing(0, 1)
                    uploadQueueRepository.queueMultiPageUpload(
                        uris = imageUris,
                        title = null,
                        tagIds = tagIds,
                        documentTypeId = documentTypeId,
                        correspondentId = correspondentId
                    )
                    _uiState.value = BatchImportUiState.Queuing(1, 1)
                    Log.d(TAG, "Multi-page document queued")
                } else {
                    // Jedes Bild einzeln hochladen
                    _uiState.value = BatchImportUiState.Queuing(0, imageUris.size)
                    imageUris.forEachIndexed { index, uri ->
                        uploadQueueRepository.queueUpload(
                            uri = uri,
                            title = null,
                            tagIds = tagIds,
                            documentTypeId = documentTypeId,
                            correspondentId = correspondentId
                        )
                        _uiState.value = BatchImportUiState.Queuing(index + 1, imageUris.size)
                    }
                }

                Log.d(TAG, "All images queued, scheduling upload")
                if (uploadImmediately) {
                    uploadWorkManager.scheduleImmediateUpload()
                } else {
                    uploadWorkManager.scheduleUpload()
                }

                val successCount = if (uploadAsSingleDocument) 1 else imageUris.size
                _uiState.value = BatchImportUiState.Success(successCount)
                Log.d(TAG, "Batch import completed successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error in queueBatchImport", e)
                _uiState.value = BatchImportUiState.Error(
                    e.message ?: "Fehler beim Hinzufügen zur Warteschlange"
                )
            }
        }
    }

    companion object {
        private const val TAG = "BatchImportViewModel"
    }

    fun resetState() {
        _uiState.value = BatchImportUiState.Idle
    }
}

sealed class BatchImportUiState {
    data object Idle : BatchImportUiState()
    data class Queuing(val current: Int, val total: Int) : BatchImportUiState()
    data class Success(val count: Int) : BatchImportUiState()
    data class Error(val message: String) : BatchImportUiState()
}
