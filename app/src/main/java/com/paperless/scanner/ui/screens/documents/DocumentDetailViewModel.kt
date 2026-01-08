package com.paperless.scanner.ui.screens.documents

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.R
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
import dagger.hilt.android.qualifiers.ApplicationContext
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
    val createdRaw: String = "",  // ISO 8601 format for editing
    val added: String = "",
    val modified: String = "",
    val correspondent: String? = null,
    val correspondentId: Int? = null,
    val documentType: String? = null,
    val documentTypeId: Int? = null,
    val tags: List<Tag> = emptyList(),
    val tagIds: List<Int> = emptyList(),
    val thumbnailUrl: String? = null,
    val downloadUrl: String? = null,
    val authToken: String? = null,
    val originalFileName: String? = null,
    val archiveSerialNumber: Int? = null,
    val notes: List<com.paperless.scanner.domain.model.Note> = emptyList(),
    val owner: Int? = null,
    val permissions: com.paperless.scanner.domain.model.Permissions? = null,
    val history: List<com.paperless.scanner.domain.model.AuditLogEntry> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val isDeleting: Boolean = false,
    val deleteError: String? = null,
    val deleteSuccess: Boolean = false,
    val isUpdating: Boolean = false,
    val updateError: String? = null,
    val updateSuccess: Boolean = false,
    // Note management
    val isAddingNote: Boolean = false,
    val addNoteError: String? = null,
    val isDeletingNoteId: Int? = null,
    val deleteNoteError: String? = null,
    // Available options for editing
    val availableTags: List<Tag> = emptyList(),
    val availableCorrespondents: List<Correspondent> = emptyList(),
    val availableDocumentTypes: List<DocumentType> = emptyList()
)

@HiltViewModel
class DocumentDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
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
            var allTags = emptyList<Tag>()
            var allCorrespondents = emptyList<Correspondent>()
            var allDocumentTypes = emptyList<DocumentType>()

            tagRepository.getTags().onSuccess { tags ->
                tagMap = tags.associateBy { it.id }
                allTags = tags
            }
            correspondentRepository.getCorrespondents().onSuccess { correspondents ->
                correspondentMap = correspondents.associateBy { it.id }
                allCorrespondents = correspondents
            }
            documentTypeRepository.getDocumentTypes().onSuccess { types ->
                documentTypeMap = types.associateBy { it.id }
                allDocumentTypes = types
            }

            // Load document and history (forceRefresh to get notes, permissions, etc.)
            documentRepository.getDocument(documentId, forceRefresh = true).onSuccess { doc ->
                val serverUrl = tokenManager.serverUrl.first()?.removeSuffix("/") ?: ""
                val token = tokenManager.token.first() ?: ""

                // Load history (don't block if it fails)
                val historyResult = documentRepository.getDocumentHistory(documentId)
                val historyList = historyResult.getOrNull() ?: emptyList()

                _uiState.update {
                    DocumentDetailUiState(
                        id = doc.id,
                        title = doc.title,
                        content = doc.content,
                        created = DateFormatter.formatDateWithTime(doc.created),
                        createdRaw = doc.created,
                        added = DateFormatter.formatDateWithTime(doc.added),
                        modified = DateFormatter.formatDateWithTime(doc.modified),
                        correspondent = doc.correspondentId?.let { correspondentMap[it]?.name },
                        correspondentId = doc.correspondentId,
                        documentType = doc.documentTypeId?.let { documentTypeMap[it]?.name },
                        documentTypeId = doc.documentTypeId,
                        tags = doc.tags.mapNotNull { tagMap[it] },
                        tagIds = doc.tags,
                        thumbnailUrl = "$serverUrl/api/documents/$documentId/thumb/",
                        downloadUrl = "$serverUrl/api/documents/$documentId/download/",
                        authToken = token,
                        originalFileName = doc.originalFileName,
                        archiveSerialNumber = doc.archiveSerialNumber,
                        notes = doc.notes,
                        owner = doc.owner,
                        permissions = doc.permissions,
                        history = historyList,
                        isLoading = false,
                        availableTags = allTags,
                        availableCorrespondents = allCorrespondents,
                        availableDocumentTypes = allDocumentTypes
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
                        deleteError = error.message ?: context.getString(R.string.error_deleting)
                    )
                }
            }
        }
    }

    fun clearDeleteError() {
        _uiState.update { it.copy(deleteError = null) }
    }

    fun updateDocument(
        title: String,
        tagIds: List<Int>,
        correspondentId: Int?,
        documentTypeId: Int?,
        archiveSerialNumber: String?,
        created: String?
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isUpdating = true, updateError = null) }

            val asnInt = archiveSerialNumber?.toIntOrNull()

            documentRepository.updateDocument(
                documentId = documentId,
                title = title,
                tags = tagIds,
                correspondent = correspondentId,
                documentType = documentTypeId,
                archiveSerialNumber = asnInt,
                created = created
            ).onSuccess {
                _uiState.update {
                    it.copy(
                        isUpdating = false,
                        updateSuccess = true
                    )
                }
                // Reload document to show updated values
                loadDocument()
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isUpdating = false,
                        updateError = error.message ?: context.getString(R.string.error_updating)
                    )
                }
            }
        }
    }

    fun clearUpdateError() {
        _uiState.update { it.copy(updateError = null) }
    }

    fun resetUpdateSuccess() {
        _uiState.update { it.copy(updateSuccess = false) }
    }

    fun addNote(noteText: String) {
        if (noteText.isBlank()) {
            _uiState.update { it.copy(addNoteError = context.getString(R.string.error_note_empty)) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isAddingNote = true, addNoteError = null) }

            documentRepository.addNote(documentId, noteText).onSuccess { updatedNotes ->
                _uiState.update {
                    it.copy(
                        isAddingNote = false,
                        notes = updatedNotes
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isAddingNote = false,
                        addNoteError = error.message ?: context.getString(R.string.error_add_note)
                    )
                }
            }
        }
    }

    fun deleteNote(noteId: Int) {
        viewModelScope.launch {
            _uiState.update { it.copy(isDeletingNoteId = noteId, deleteNoteError = null) }

            documentRepository.deleteNote(documentId, noteId).onSuccess { updatedNotes ->
                _uiState.update {
                    it.copy(
                        isDeletingNoteId = null,
                        notes = updatedNotes
                    )
                }
            }.onFailure { error ->
                _uiState.update {
                    it.copy(
                        isDeletingNoteId = null,
                        deleteNoteError = error.message ?: context.getString(R.string.error_delete_note)
                    )
                }
            }
        }
    }

    fun clearAddNoteError() {
        _uiState.update { it.copy(addNoteError = null) }
    }

    fun clearDeleteNoteError() {
        _uiState.update { it.copy(deleteNoteError = null) }
    }
}
