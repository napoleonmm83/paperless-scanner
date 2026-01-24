package com.paperless.scanner.ui.screens.documents

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.R
import com.paperless.scanner.data.ai.SuggestionOrchestrator
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.api.userMessage
import com.paperless.scanner.data.ai.models.DocumentAnalysis
import com.paperless.scanner.data.ai.models.SuggestionResult
import com.paperless.scanner.data.ai.models.SuggestionSource
import com.paperless.scanner.data.billing.PremiumFeature
import com.paperless.scanner.data.billing.PremiumFeatureManager
import com.paperless.scanner.domain.model.Correspondent
import com.paperless.scanner.domain.model.DocumentType
import com.paperless.scanner.domain.model.Tag
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.data.repository.AiUsageRepository
import com.paperless.scanner.data.repository.CorrespondentRepository
import com.paperless.scanner.data.repository.DocumentRepository
import com.paperless.scanner.data.repository.DocumentTypeRepository
import com.paperless.scanner.data.repository.TagRepository
import com.paperless.scanner.data.repository.UsageLimitStatus
import com.paperless.scanner.ui.screens.upload.AnalysisState
import com.paperless.scanner.util.DateFormatter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.URL
import javax.inject.Inject

// User/Group models for permissions UI
data class UserInfo(
    val id: Int,
    val username: String
)

data class GroupInfo(
    val id: Int,
    val name: String
)

// Tag creation state
sealed class CreateTagState {
    data object Idle : CreateTagState()
    data object Creating : CreateTagState()
    data class Success(val tag: Tag) : CreateTagState()
    data class Error(val message: String) : CreateTagState()
}

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
    val availableDocumentTypes: List<DocumentType> = emptyList(),
    // Permissions management
    val availableUsers: List<UserInfo> = emptyList(),
    val availableGroups: List<GroupInfo> = emptyList(),
    val isLoadingPermissionsData: Boolean = false,
    val isUpdatingPermissions: Boolean = false,
    val updatePermissionsError: String? = null,
    val updatePermissionsSuccess: Boolean = false,
    val userCanChange: Boolean = true
)

@HiltViewModel
class DocumentDetailViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val savedStateHandle: SavedStateHandle,
    private val documentRepository: DocumentRepository,
    private val tagRepository: TagRepository,
    private val correspondentRepository: CorrespondentRepository,
    private val documentTypeRepository: DocumentTypeRepository,
    private val tokenManager: TokenManager,
    private val suggestionOrchestrator: SuggestionOrchestrator,
    private val aiUsageRepository: AiUsageRepository,
    private val premiumFeatureManager: PremiumFeatureManager,
    private val networkMonitor: com.paperless.scanner.data.network.NetworkMonitor,
    private val serverHealthMonitor: com.paperless.scanner.data.health.ServerHealthMonitor
) : ViewModel() {

    companion object {
        private const val TAG = "DocumentDetailViewModel"
        private const val KEY_DOCUMENT_ID = "documentId"
    }

    // Reactive documentId using SavedStateHandle.getStateFlow()
    // Automatically survives process death and configuration changes
    private val documentIdStateFlow: StateFlow<Int> = savedStateHandle.getStateFlow<String?>(KEY_DOCUMENT_ID, null)
        .map { it?.toIntOrNull() ?: 0 }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = savedStateHandle.get<String>(KEY_DOCUMENT_ID)?.toIntOrNull() ?: 0
        )

    private val _uiState = MutableStateFlow(DocumentDetailUiState())
    val uiState: StateFlow<DocumentDetailUiState> = _uiState.asStateFlow()

    private val _createTagState = MutableStateFlow<CreateTagState>(CreateTagState.Idle)
    val createTagState: StateFlow<CreateTagState> = _createTagState.asStateFlow()

    // AI Suggestions State
    private val _aiSuggestions = MutableStateFlow<DocumentAnalysis?>(null)
    val aiSuggestions: StateFlow<DocumentAnalysis?> = _aiSuggestions.asStateFlow()

    private val _analysisState = MutableStateFlow<AnalysisState>(AnalysisState.Idle)
    val analysisState: StateFlow<AnalysisState> = _analysisState.asStateFlow()

    private val _suggestionSource = MutableStateFlow<SuggestionSource?>(null)
    val suggestionSource: StateFlow<SuggestionSource?> = _suggestionSource.asStateFlow()

    // WiFi-Only State
    private val _wifiRequired = MutableStateFlow(false)
    val wifiRequired: StateFlow<Boolean> = _wifiRequired.asStateFlow()

    private val _wifiOnlyOverride = MutableStateFlow(false)
    val wifiOnlyOverride: StateFlow<Boolean> = _wifiOnlyOverride.asStateFlow()

    // Observe WiFi status for reactive UI
    val isWifiConnected: StateFlow<Boolean> = networkMonitor.isWifiConnected

    // Server Health Status (Phase 2: Server Offline Detection)
    val serverStatus: StateFlow<com.paperless.scanner.data.health.ServerStatus> = serverHealthMonitor.serverStatus
    val isServerReachable: StateFlow<Boolean> = serverHealthMonitor.isServerReachable

    // Observe AI new tags setting
    val aiNewTagsEnabled: Flow<Boolean> = tokenManager.aiNewTagsEnabled

    /**
     * Whether AI suggestions are available (Debug build or Premium subscription).
     */
    val isAiAvailable: Boolean
        get() = premiumFeatureManager.isFeatureAvailable(PremiumFeature.AI_ANALYSIS)

    // AI Usage Limit State
    private val _remainingCalls = MutableStateFlow<Int>(300)
    val remainingCalls: StateFlow<Int> = _remainingCalls.asStateFlow()

    private var tagMap: Map<Int, Tag> = emptyMap()
    private var correspondentMap: Map<Int, Correspondent> = emptyMap()
    private var documentTypeMap: Map<Int, DocumentType> = emptyMap()

    init {
        loadLookupData()
        observeDocumentReactively()
    }

    /**
     * BEST PRACTICE: Load tags, correspondents, and document types once at init.
     * These are used for name lookups throughout the screen.
     */
    private fun loadLookupData() {
        viewModelScope.launch {
            tagRepository.getTags().onSuccess { tags ->
                tagMap = tags.associateBy { it.id }
                _uiState.update { it.copy(availableTags = tags) }
            }
            correspondentRepository.getCorrespondents().onSuccess { correspondents ->
                correspondentMap = correspondents.associateBy { it.id }
                _uiState.update { it.copy(availableCorrespondents = correspondents) }
            }
            documentTypeRepository.getDocumentTypes().onSuccess { types ->
                documentTypeMap = types.associateBy { it.id }
                _uiState.update { it.copy(availableDocumentTypes = types) }
            }
        }
    }

    /**
     * Check Paperless server health status.
     * Delegates to ServerHealthMonitor for proactive server reachability check.
     * Used by UI to trigger manual health check (e.g., retry button).
     */
    fun checkServerHealth() {
        viewModelScope.launch {
            serverHealthMonitor.checkServerHealth()
        }
    }

    /**
     * BEST PRACTICE: Reactive Flow for document observation.
     * Automatically updates UI when document is modified (via sync, edit, etc.).
     * Observes documentId changes from SavedStateHandle (handles process death).
     */
    private fun observeDocumentReactively() {
        viewModelScope.launch {
            documentIdStateFlow.collect { documentId ->
                if (documentId <= 0) {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = context.getString(R.string.document_detail_invalid_id_error)
                        )
                    }
                    return@collect
                }

                // Set loading state
                _uiState.update { it.copy(isLoading = true, error = null) }

                // Trigger background API refresh to fetch notes & permissions
                viewModelScope.launch {
                    triggerBackgroundRefresh(documentId)
                }

                // Observe reactive Flow - automatically updates when DB changes
                documentRepository.observeDocument(documentId).collect { doc ->
                    if (doc == null) {
                        // Don't show error if document is being deleted or was just deleted
                        // (this prevents "Error loading" flash before navigation back)
                        val currentState = _uiState.value
                        if (!currentState.isDeleting && !currentState.deleteSuccess) {
                            _uiState.update {
                                it.copy(
                                    isLoading = false,
                                    error = context.getString(R.string.error_loading)
                                )
                            }
                        }
                        return@collect
                    }

                    val serverUrl = tokenManager.serverUrl.first()?.removeSuffix("/") ?: ""
                    val token = tokenManager.token.first() ?: ""

                    _uiState.update { currentState ->
                        currentState.copy(
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
                            // CRITICAL: Preserve notes, permissions, owner correctly
                            // DB doesn't store these fields, so keep them from currentState if doc (from DB) doesn't have them
                            notes = if (doc.notes.isNotEmpty()) {
                                doc.notes
                            } else if (currentState.notes.isNotEmpty()) {
                                currentState.notes
                            } else {
                                emptyList()
                            },
                            owner = doc.owner ?: currentState.owner,
                            permissions = doc.permissions ?: currentState.permissions,
                            isLoading = false,
                            error = null
                        )
                    }
                }
            }
        }
    }

    /**
     * Triggers background API refresh to fetch latest document data including notes,
     * permissions, and history. Room Flow will automatically update UI when cache changes.
     */
    private suspend fun triggerBackgroundRefresh(documentId: Int) {
        // Fetch full document data (notes, permissions, etc.)
        val docResult = documentRepository.getDocument(documentId, forceRefresh = true)

        // Extract notes & permissions from API response (DB doesn't store them!)
        docResult.onSuccess { doc ->
            _uiState.update { currentState ->
                currentState.copy(
                    notes = if (doc.notes.isNotEmpty()) doc.notes else currentState.notes,
                    permissions = doc.permissions ?: currentState.permissions,
                    owner = doc.owner ?: currentState.owner
                )
            }
        }

        // Fetch history separately (don't block if it fails)
        val historyResult = documentRepository.getDocumentHistory(documentId)
        val historyList = historyResult.getOrNull() ?: emptyList()
        if (historyList.isNotEmpty()) {
            _uiState.update { it.copy(history = historyList) }
        }
    }

    /**
     * Manual refresh function for pull-to-refresh or retry actions.
     * BEST PRACTICE: Reactive Flow handles automatic updates, this is just for user-triggered refresh.
     */
    fun refresh() {
        val documentId = documentIdStateFlow.value
        if (documentId > 0) {
            _uiState.update { it.copy(isLoading = true, error = null) }
            viewModelScope.launch {
                triggerBackgroundRefresh(documentId)
            }
        }
    }


    fun deleteDocument() {
        viewModelScope.launch {
            val documentId = documentIdStateFlow.value
            if (documentId <= 0) return@launch

            _uiState.update { it.copy(isDeleting = true, deleteError = null) }

            documentRepository.deleteDocument(documentId).onSuccess {
                _uiState.update {
                    it.copy(
                        isDeleting = false,
                        deleteSuccess = true
                    )
                }
            }.onFailure { error ->
                val paperlessException = PaperlessException.from(error)
                _uiState.update {
                    it.copy(
                        isDeleting = false,
                        deleteError = paperlessException.userMessage
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
            val documentId = documentIdStateFlow.value
            if (documentId <= 0) return@launch

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
                refresh()
            }.onFailure { error ->
                val paperlessException = PaperlessException.from(error)
                _uiState.update {
                    it.copy(
                        isUpdating = false,
                        updateError = paperlessException.userMessage
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
            val documentId = documentIdStateFlow.value
            if (documentId <= 0) return@launch

            _uiState.update { it.copy(isAddingNote = true, addNoteError = null) }

            documentRepository.addNote(documentId, noteText).onSuccess { updatedNotes ->
                // First update UI with new notes (instant feedback)
                _uiState.update {
                    it.copy(
                        isAddingNote = false,
                        notes = updatedNotes
                    )
                }

                // Then sync to DB (await to prevent race condition)
                // This ensures DB is updated before reactive Flow can overwrite UI
                triggerBackgroundRefresh(documentId)
            }.onFailure { error ->
                val paperlessException = PaperlessException.from(error)
                _uiState.update {
                    it.copy(
                        isAddingNote = false,
                        addNoteError = paperlessException.userMessage
                    )
                }
            }
        }
    }

    fun deleteNote(noteId: Int) {
        viewModelScope.launch {
            val documentId = documentIdStateFlow.value
            if (documentId <= 0) return@launch

            _uiState.update { it.copy(isDeletingNoteId = noteId, deleteNoteError = null) }

            documentRepository.deleteNote(documentId, noteId).onSuccess { updatedNotes ->
                // First update UI with updated notes (instant feedback)
                _uiState.update {
                    it.copy(
                        isDeletingNoteId = null,
                        notes = updatedNotes
                    )
                }

                // Then sync to DB (await to prevent race condition)
                // This ensures DB is updated before reactive Flow can overwrite UI
                triggerBackgroundRefresh(documentId)
            }.onFailure { error ->
                val paperlessException = PaperlessException.from(error)
                _uiState.update {
                    it.copy(
                        isDeletingNoteId = null,
                        deleteNoteError = paperlessException.userMessage
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

    // Permissions management

    fun loadPermissionsData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingPermissionsData = true) }

            val users = mutableListOf<UserInfo>()
            val groups = mutableListOf<GroupInfo>()

            documentRepository.getUsers().onSuccess { userList ->
                users.addAll(userList.map { UserInfo(it.id, it.username) })
            }

            documentRepository.getGroups().onSuccess { groupList ->
                groups.addAll(groupList.map { GroupInfo(it.id, it.name) })
            }

            _uiState.update {
                it.copy(
                    isLoadingPermissionsData = false,
                    availableUsers = users,
                    availableGroups = groups
                )
            }
        }
    }

    fun updatePermissions(
        owner: Int?,
        viewUsers: List<Int>,
        viewGroups: List<Int>,
        changeUsers: List<Int>,
        changeGroups: List<Int>
    ) {
        viewModelScope.launch {
            val documentId = documentIdStateFlow.value
            if (documentId <= 0) return@launch

            _uiState.update { it.copy(isUpdatingPermissions = true, updatePermissionsError = null) }

            documentRepository.updateDocumentPermissions(
                documentId = documentId,
                owner = owner,
                viewUsers = viewUsers,
                viewGroups = viewGroups,
                changeUsers = changeUsers,
                changeGroups = changeGroups
            ).onSuccess { updatedDoc ->
                _uiState.update {
                    it.copy(
                        isUpdatingPermissions = false,
                        updatePermissionsSuccess = true,
                        // Update permissions immediately (DB doesn't store them)
                        permissions = updatedDoc.permissions,
                        owner = updatedDoc.owner
                    )
                }
                // Trigger background refresh to ensure consistency
                viewModelScope.launch {
                    triggerBackgroundRefresh(documentId)
                }
            }.onFailure { error ->
                val paperlessException = PaperlessException.from(error)
                _uiState.update {
                    it.copy(
                        isUpdatingPermissions = false,
                        updatePermissionsError = paperlessException.userMessage
                    )
                }
            }
        }
    }

    fun clearUpdatePermissionsError() {
        _uiState.update { it.copy(updatePermissionsError = null) }
    }

    fun resetUpdatePermissionsSuccess() {
        _uiState.update { it.copy(updatePermissionsSuccess = false) }
    }

    // Tag creation
    fun createTag(name: String, color: String? = null) {
        viewModelScope.launch {
            _createTagState.update { CreateTagState.Creating }
            tagRepository.createTag(name, color).fold(
                onSuccess = { tag ->
                    // Add to available tags immediately
                    _uiState.update { state ->
                        state.copy(
                            availableTags = (state.availableTags + tag).sortedBy { it.name.lowercase() }
                        )
                    }
                    _createTagState.update { CreateTagState.Success(tag) }
                },
                onFailure = { error ->
                    val paperlessException = PaperlessException.from(error)
                    _createTagState.update {
                        CreateTagState.Error(paperlessException.userMessage)
                    }
                }
            )
        }
    }

    fun resetCreateTagState() {
        _createTagState.update { CreateTagState.Idle }
    }

    /**
     * Analyze the document thumbnail using SuggestionOrchestrator.
     * Downloads the thumbnail image and uses AI to suggest tags.
     */
    fun analyzeDocumentThumbnail() {
        viewModelScope.launch(Dispatchers.IO) {
            val documentId = documentIdStateFlow.value
            if (documentId <= 0) return@launch

            _analysisState.update { AnalysisState.Analyzing }

            try {
                val state = _uiState.value
                val thumbnailUrl = state.thumbnailUrl
                val authToken = state.authToken

                if (thumbnailUrl == null || authToken == null) {
                    Log.w(TAG, "Thumbnail URL or auth token not available")
                    _analysisState.update { AnalysisState.Error(context.getString(R.string.error_analyze_document)) }
                    return@launch
                }

                // Check usage limits
                val limitStatus = aiUsageRepository.checkUsageLimit()

                when (limitStatus) {
                    UsageLimitStatus.HARD_LIMIT_REACHED -> {
                        Log.w(TAG, "Hard limit reached - AI disabled")
                        _analysisState.update { AnalysisState.LimitReached }
                    }
                    UsageLimitStatus.SOFT_LIMIT_200 -> {
                        Log.i(TAG, "Soft limit 200 reached")
                        _analysisState.update { AnalysisState.LimitWarning(_remainingCalls.value) }
                    }
                    UsageLimitStatus.SOFT_LIMIT_100 -> {
                        Log.i(TAG, "Soft limit 100 reached")
                        _analysisState.update { AnalysisState.LimitInfo(_remainingCalls.value) }
                    }
                    else -> {
                        _analysisState.update { AnalysisState.Analyzing }
                    }
                }

                // Download thumbnail image
                val connection = URL(thumbnailUrl).openConnection()
                connection.setRequestProperty("Authorization", "Token $authToken")
                val bitmap = connection.getInputStream().use { inputStream ->
                    BitmapFactory.decodeStream(inputStream)
                }

                if (bitmap == null) {
                    Log.w(TAG, "Could not decode thumbnail image")
                    _analysisState.update { AnalysisState.Error(context.getString(R.string.error_analyze_document)) }
                    return@launch
                }

                // Use SuggestionOrchestrator for centralized suggestion logic
                val result = suggestionOrchestrator.getSuggestions(
                    bitmap = bitmap,
                    extractedText = state.content ?: "",
                    documentId = documentId,
                    overrideWifiOnly = _wifiOnlyOverride.value
                )

                when (result) {
                    is SuggestionResult.WiFiRequired -> {
                        Log.d(TAG, "WiFi required for AI suggestions")
                        _wifiRequired.update { true }
                        _analysisState.update { AnalysisState.Idle }
                        // Don't show error - banner will inform user
                    }
                    is SuggestionResult.Success -> {
                        Log.d(TAG, "Suggestions retrieved: ${result.analysis.suggestedTags.size} tags from ${result.source}")

                        // Clear WiFi required state if analysis succeeded
                        _wifiRequired.update { false }

                        _suggestionSource.update { result.source }

                        // Track AI usage if AI was used
                        if (result.source == SuggestionSource.FIREBASE_AI) {
                            val estimatedInputTokens = 1000
                            val estimatedOutputTokens = 200

                            aiUsageRepository.logUsage(
                                featureType = "document_analysis",
                                inputTokens = estimatedInputTokens,
                                outputTokens = estimatedOutputTokens,
                                success = true,
                                subscriptionType = "free"
                            )
                        }

                        _aiSuggestions.update { result.analysis }

                        _analysisState.update {
                            when {
                                limitStatus == UsageLimitStatus.HARD_LIMIT_REACHED -> AnalysisState.LimitReached
                                else -> AnalysisState.Success
                            }
                        }
                    }
                    is SuggestionResult.Error -> {
                        Log.e(TAG, "Suggestion orchestration failed: ${result.message}")
                        _analysisState.update { AnalysisState.Error(result.message) }
                    }
                    is SuggestionResult.Loading -> {
                        _analysisState.update { AnalysisState.Analyzing }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Document analysis failed", e)
                val paperlessException = PaperlessException.from(e)
                _analysisState.update { AnalysisState.Error(paperlessException.userMessage) }
            }
        }
    }

    /**
     * Clear AI suggestions.
     */
    fun clearSuggestions() {
        _aiSuggestions.update { null }
        _analysisState.update { AnalysisState.Idle }
        _suggestionSource.update { null }
        _wifiRequired.update { false }
        _wifiOnlyOverride.update { false }
    }

    /**
     * Override WiFi-only restriction for current session.
     * Allows user to use AI even without WiFi when they explicitly choose "Use anyway".
     */
    fun overrideWifiOnlyForSession() {
        Log.d(TAG, "User overrode WiFi-only restriction")
        _wifiOnlyOverride.update { true }
        _wifiRequired.update { false }

        // Re-trigger analysis with override
        analyzeDocumentThumbnail()
    }

    /**
     * Enable or disable AI new tags feature.
     * Updates the user preference in TokenManager.
     */
    fun setAiNewTagsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            tokenManager.setAiNewTagsEnabled(enabled)
        }
    }
}
