package com.paperless.scanner.ui.screens.documents

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.domain.model.Correspondent
import com.paperless.scanner.domain.model.DocumentType
import com.paperless.scanner.domain.model.Tag
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.data.repository.CorrespondentRepository
import com.paperless.scanner.data.repository.DocumentRepository
import com.paperless.scanner.data.repository.DocumentTypeRepository
import com.paperless.scanner.data.repository.TagRepository
import com.paperless.scanner.util.DateFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DocumentDetailUiState(
    val id: Int = 0,
    val title: String = "",
    val content: String? = null,
    val created: String = "",
    val added: String = "",
    val modified: String = "",
    val correspondent: String? = null,
    val documentType: String? = null,
    val tags: List<Tag> = emptyList(),
    val thumbnailUrl: String? = null,
    val downloadUrl: String? = null,
    val authToken: String? = null,
    val originalFileName: String? = null,
    val archiveSerialNumber: Int? = null,
    val isLoading: Boolean = true,
    val error: String? = null,
    val isDeleting: Boolean = false,
    val deleteError: String? = null,
    val deleteSuccess: Boolean = false
)

@HiltViewModel
class DocumentDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val documentRepository: DocumentRepository,
    private val tagRepository: TagRepository,
    private val correspondentRepository: CorrespondentRepository,
    private val documentTypeRepository: DocumentTypeRepository,
    private val tokenManager: TokenManager
) : ViewModel() {

    private val documentId: Int = savedStateHandle.get<String>("documentId")?.toIntOrNull() ?: 0

    private val _uiState = MutableStateFlow(DocumentDetailUiState())
    val uiState: StateFlow<DocumentDetailUiState> = _uiState.asStateFlow()

    private var tagMap: Map<Int, Tag> = emptyMap()
    private var correspondentMap: Map<Int, Correspondent> = emptyMap()
    private var documentTypeMap: Map<Int, DocumentType> = emptyMap()

    init {
        loadDocument()
    }

    fun loadDocument() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            // Load lookup data in parallel
            tagRepository.getTags().onSuccess { tags ->
                tagMap = tags.associateBy { it.id }
            }
            correspondentRepository.getCorrespondents().onSuccess { correspondents ->
                correspondentMap = correspondents.associateBy { it.id }
            }
            documentTypeRepository.getDocumentTypes().onSuccess { types ->
                documentTypeMap = types.associateBy { it.id }
            }

            // Load document
            documentRepository.getDocument(documentId).onSuccess { doc ->
                val serverUrl = tokenManager.serverUrl.first()?.removeSuffix("/") ?: ""
                val token = tokenManager.token.first() ?: ""

                _uiState.update {
                    DocumentDetailUiState(
                        id = doc.id,
                        title = doc.title,
                        content = doc.content,
                        created = DateFormatter.formatDateWithTime(doc.created),
                        added = DateFormatter.formatDateWithTime(doc.added),
                        modified = DateFormatter.formatDateWithTime(doc.modified),
                        correspondent = doc.correspondentId?.let { correspondentMap[it]?.name },
                        documentType = doc.documentTypeId?.let { documentTypeMap[it]?.name },
                        tags = doc.tags.mapNotNull { tagMap[it] },
                        thumbnailUrl = "$serverUrl/api/documents/$documentId/thumb/",
                        downloadUrl = "$serverUrl/api/documents/$documentId/download/",
                        authToken = token,
                        originalFileName = doc.originalFileName,
                        archiveSerialNumber = doc.archiveSerialNumber,
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


    fun deleteDocument() {
        viewModelScope.launch {
            _uiState.update { it.copy(isDeleting = true, deleteError = null) }

            documentRepository.deleteDocument(documentId).onSuccess {
                _uiState.update {
                    it.copy(
                        isDeleting = false,
                        deleteSuccess = true
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isDeleting = false,
                        deleteError = error.message ?: "Fehler beim Löschen"
                    )
                }
            }
        }
    }

    fun clearDeleteError() {
        _uiState.update { it.copy(deleteError = null) }
    }
}
