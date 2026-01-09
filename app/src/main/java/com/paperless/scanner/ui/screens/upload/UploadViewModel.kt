package com.paperless.scanner.ui.screens.upload

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.R
import com.paperless.scanner.data.ai.SuggestionOrchestrator
import com.paperless.scanner.data.ai.models.DocumentAnalysis
import com.paperless.scanner.data.ai.models.SuggestionResult
import com.paperless.scanner.data.ai.models.SuggestionSource
import com.paperless.scanner.data.ai.models.TagSuggestion
import com.paperless.scanner.data.analytics.AnalyticsEvent
import com.paperless.scanner.data.analytics.AnalyticsService
import com.paperless.scanner.domain.model.Correspondent
import com.paperless.scanner.domain.model.DocumentType
import com.paperless.scanner.domain.model.Tag
import com.paperless.scanner.data.repository.AiUsageRepository
import com.paperless.scanner.data.repository.CorrespondentRepository
import com.paperless.scanner.data.repository.DocumentRepository
import com.paperless.scanner.data.repository.DocumentTypeRepository
import com.paperless.scanner.data.repository.TagRepository
import com.paperless.scanner.data.repository.UsageLimitStatus
import com.paperless.scanner.util.NetworkUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UploadViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val documentRepository: DocumentRepository,
    private val tagRepository: TagRepository,
    private val documentTypeRepository: DocumentTypeRepository,
    private val correspondentRepository: CorrespondentRepository,
    private val networkUtils: NetworkUtils,
    private val uploadQueueRepository: com.paperless.scanner.data.repository.UploadQueueRepository,
    private val networkMonitor: com.paperless.scanner.data.network.NetworkMonitor,
    private val analyticsService: AnalyticsService,
    private val suggestionOrchestrator: SuggestionOrchestrator,
    private val aiUsageRepository: AiUsageRepository,
    private val ioDispatcher: CoroutineDispatcher
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

    // AI Suggestions State
    private val _aiSuggestions = MutableStateFlow<DocumentAnalysis?>(null)
    val aiSuggestions: StateFlow<DocumentAnalysis?> = _aiSuggestions.asStateFlow()

    private val _analysisState = MutableStateFlow<AnalysisState>(AnalysisState.Idle)
    val analysisState: StateFlow<AnalysisState> = _analysisState.asStateFlow()

    // AI Usage Limit State
    private val _usageLimitStatus = MutableStateFlow<UsageLimitStatus>(UsageLimitStatus.WITHIN_LIMITS)
    val usageLimitStatus: StateFlow<UsageLimitStatus> = _usageLimitStatus.asStateFlow()

    private val _remainingCalls = MutableStateFlow<Int>(300)
    val remainingCalls: StateFlow<Int> = _remainingCalls.asStateFlow()

    // Store last upload params for retry
    private var lastUploadParams: UploadParams? = null

    init {
        observeTagsReactively()
        observeDocumentTypesReactively()
        observeCorrespondentsReactively()
        observeUsageLimits()
    }

    /**
     * BEST PRACTICE: Reactive Flow for tags.
     * Automatically updates dropdown when tags are added/modified/deleted.
     * User creates new tag in LabelsScreen → appears instantly in UploadScreen!
     */
    private fun observeTagsReactively() {
        viewModelScope.launch {
            tagRepository.observeTags().collect { tagList ->
                _tags.update { tagList.sortedBy { it.name.lowercase() } }
            }
        }
    }

    /**
     * BEST PRACTICE: Reactive Flow for document types.
     * Automatically updates dropdown when document types are added/modified/deleted.
     */
    private fun observeDocumentTypesReactively() {
        viewModelScope.launch {
            documentTypeRepository.observeDocumentTypes().collect { types ->
                _documentTypes.update { types.sortedBy { it.name.lowercase() } }
            }
        }
    }

    /**
     * BEST PRACTICE: Reactive Flow for correspondents.
     * Automatically updates dropdown when correspondents are added/modified/deleted.
     */
    private fun observeCorrespondentsReactively() {
        viewModelScope.launch {
            correspondentRepository.observeCorrespondents().collect { correspondentList ->
                _correspondents.update { correspondentList.sortedBy { it.name.lowercase() } }
            }
        }
    }

    /**
     * BEST PRACTICE: Reactive Flow for AI usage limits.
     * Automatically updates UI when usage changes.
     */
    private fun observeUsageLimits() {
        viewModelScope.launch {
            aiUsageRepository.observeCurrentMonthCallCount().collect { callCount ->
                // Update remaining calls
                _remainingCalls.update { (300 - callCount).coerceAtLeast(0) }

                // Update usage limit status
                val status = when {
                    callCount >= 300 -> UsageLimitStatus.HARD_LIMIT_REACHED
                    callCount >= 200 -> UsageLimitStatus.SOFT_LIMIT_200
                    callCount >= 100 -> UsageLimitStatus.SOFT_LIMIT_100
                    else -> UsageLimitStatus.WITHIN_LIMITS
                }
                _usageLimitStatus.update { status }
            }
        }
    }

    // REMOVED: loadTags(), loadDocumentTypes(), loadCorrespondents()
    // These methods are no longer needed because reactive Flows automatically
    // populate dropdown state via observeTagsReactively(), observeDocumentTypesReactively(),
    // and observeCorrespondentsReactively() in init{}

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

        viewModelScope.launch(ioDispatcher) {
            val startTime = System.currentTimeMillis()
            analyticsService.trackEvent(AnalyticsEvent.UploadStarted(pageCount = 1, isMultiPage = false))

            // Check network availability - if offline, queue the upload
            if (!networkMonitor.checkOnlineStatus()) {
                Log.d(TAG, "Offline detected - queueing upload for later sync")
                uploadQueueRepository.queueUpload(
                    uri = uri,
                    title = title,
                    tagIds = tagIds,
                    documentTypeId = documentTypeId,
                    correspondentId = correspondentId
                )
                lastUploadParams = null
                analyticsService.trackEvent(AnalyticsEvent.UploadQueued(isOffline = true))
                _uiState.update { UploadUiState.Queued }
                return@launch
            }

            _uiState.update { UploadUiState.Uploading(0f) }

            documentRepository.uploadDocument(
                uri = uri,
                title = title,
                tagIds = tagIds,
                documentTypeId = documentTypeId,
                correspondentId = correspondentId,
                onProgress = { progress ->
                    _uiState.update { UploadUiState.Uploading(progress) }
                }
            )
                .onSuccess { taskId ->
                    val durationMs = System.currentTimeMillis() - startTime
                    analyticsService.trackEvent(AnalyticsEvent.UploadSuccess(pageCount = 1, durationMs = durationMs))
                    lastUploadParams = null
                    _uiState.update { UploadUiState.Success(taskId) }
                }
                .onFailure { exception ->
                    analyticsService.trackEvent(AnalyticsEvent.UploadFailed(errorType = "upload_error"))
                    _uiState.update {
                        UploadUiState.Error(exception.message ?: context.getString(R.string.upload_error_generic))
                    }
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

        viewModelScope.launch(ioDispatcher) {
            val startTime = System.currentTimeMillis()
            val pageCount = uris.size
            analyticsService.trackEvent(AnalyticsEvent.UploadStarted(pageCount = pageCount, isMultiPage = true))

            // Check network availability - if offline, queue the upload
            if (!networkMonitor.checkOnlineStatus()) {
                Log.d(TAG, "Offline detected - queueing multi-page upload for later sync")
                uploadQueueRepository.queueMultiPageUpload(
                    uris = uris,
                    title = title,
                    tagIds = tagIds,
                    documentTypeId = documentTypeId,
                    correspondentId = correspondentId
                )
                lastUploadParams = null
                analyticsService.trackEvent(AnalyticsEvent.UploadQueued(isOffline = true))
                _uiState.update { UploadUiState.Queued }
                return@launch
            }

            _uiState.update { UploadUiState.Uploading(0f) }

            documentRepository.uploadMultiPageDocument(
                uris = uris,
                title = title,
                tagIds = tagIds,
                documentTypeId = documentTypeId,
                correspondentId = correspondentId,
                onProgress = { progress ->
                    _uiState.update { UploadUiState.Uploading(progress) }
                }
            )
                .onSuccess { taskId ->
                    val durationMs = System.currentTimeMillis() - startTime
                    analyticsService.trackEvent(AnalyticsEvent.UploadSuccess(pageCount = pageCount, durationMs = durationMs))
                    lastUploadParams = null
                    _uiState.update { UploadUiState.Success(taskId) }
                }
                .onFailure { exception ->
                    analyticsService.trackEvent(AnalyticsEvent.UploadFailed(errorType = "multi_page_upload_error"))
                    _uiState.update {
                        UploadUiState.Error(exception.message ?: context.getString(R.string.upload_error_generic))
                    }
                }
        }
    }

    fun retry() {
        analyticsService.trackEvent(AnalyticsEvent.UploadRetried)
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
        _uiState.update { UploadUiState.Idle }
        lastUploadParams = null
    }

    fun clearError() {
        if (_uiState.value is UploadUiState.Error) {
            _uiState.update { UploadUiState.Idle }
        }
    }

    override fun onCleared() {
        super.onCleared()
        lastUploadParams = null
    }

    fun createTag(name: String, color: String? = null) {
        viewModelScope.launch(ioDispatcher) {
            _createTagState.update { CreateTagState.Creating }

            tagRepository.createTag(name = name, color = color)
                .onSuccess { newTag ->
                    Log.d(TAG, "Tag created: ${newTag.name}")
                    analyticsService.trackEvent(AnalyticsEvent.TagCreated)
                    // BEST PRACTICE: No manual list update needed!
                    // observeTagsReactively() automatically updates dropdown.
                    _createTagState.update { CreateTagState.Success(newTag) }
                }
                .onFailure { e ->
                    Log.e(TAG, "Failed to create tag", e)
                    _createTagState.update {
                        CreateTagState.Error(e.message ?: context.getString(R.string.error_create_tag))
                    }
                }
        }
    }

    fun resetCreateTagState() {
        _createTagState.update { CreateTagState.Idle }
    }

    /**
     * Analyze document image using centralized SuggestionOrchestrator.
     *
     * BEST PRACTICE: Uses intelligent fallback chain:
     * 1. Premium + Within Limits → Firebase AI
     * 2. Online + documentId → Paperless API (not applicable pre-upload)
     * 3. ALWAYS → Local Tag Matching (offline-capable)
     *
     * The orchestrator handles Premium checks, network status, and merging automatically.
     */
    fun analyzeDocument(uri: Uri, usePremiumAi: Boolean = false) {
        viewModelScope.launch(ioDispatcher) {
            _analysisState.update { AnalysisState.Analyzing }

            try {
                // Check usage limits for UI feedback
                val limitStatus = aiUsageRepository.checkUsageLimit()

                // Show limit warning/info based on status
                when (limitStatus) {
                    UsageLimitStatus.HARD_LIMIT_REACHED -> {
                        Log.w(TAG, "Hard limit reached - AI disabled, using fallback suggestions")
                        _analysisState.update { AnalysisState.LimitReached }
                    }
                    UsageLimitStatus.SOFT_LIMIT_200 -> {
                        Log.i(TAG, "Soft limit 200 reached - showing warning")
                        _analysisState.update { AnalysisState.LimitWarning(_remainingCalls.value) }
                    }
                    UsageLimitStatus.SOFT_LIMIT_100 -> {
                        Log.i(TAG, "Soft limit 100 reached - showing info")
                        _analysisState.update { AnalysisState.LimitInfo(_remainingCalls.value) }
                    }
                    else -> {
                        _analysisState.update { AnalysisState.Analyzing }
                    }
                }

                // Decode bitmap for AI/local analysis
                val bitmap = context.contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it)
                }

                if (bitmap == null) {
                    Log.w(TAG, "Could not decode image for analysis")
                    _analysisState.update { AnalysisState.Error(context.getString(R.string.error_analyze_document)) }
                    return@launch
                }

                // Use SuggestionOrchestrator for centralized suggestion logic
                // Handles Premium check, fallback chain (AI → Paperless → Local), and merging
                val result = suggestionOrchestrator.getSuggestions(
                    bitmap = bitmap,
                    extractedText = "", // TODO: Add OCR text extraction in future
                    documentId = null // Not applicable for pre-upload analysis
                )

                when (result) {
                    is SuggestionResult.Success -> {
                        Log.d(TAG, "Suggestions retrieved: ${result.analysis.suggestedTags.size} tags from ${result.source}")

                        // Track AI usage if AI was used
                        if (result.source == SuggestionSource.FIREBASE_AI) {
                            val estimatedInputTokens = 1000  // ~1 image
                            val estimatedOutputTokens = 200  // ~200 tokens for suggestions

                            aiUsageRepository.logUsage(
                                featureType = "document_analysis",
                                inputTokens = estimatedInputTokens,
                                outputTokens = estimatedOutputTokens,
                                success = true,
                                subscriptionType = "free" // TODO: Update when premium implemented
                            )

                            analyticsService.trackAiFeatureUsage(
                                featureType = "document_analysis",
                                inputTokens = estimatedInputTokens,
                                outputTokens = estimatedOutputTokens,
                                subscriptionType = "free"
                            )
                        }

                        _aiSuggestions.update { result.analysis }

                        // Update state based on source and limit status
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
                        // Should not happen in this context, but handle for exhaustiveness
                        _analysisState.update { AnalysisState.Analyzing }
                    }
                }

            } catch (e: Exception) {
                Log.e(TAG, "Document analysis failed", e)
                _analysisState.update { AnalysisState.Error(e.message ?: context.getString(R.string.error_analyze_document)) }
            }
        }
    }

    /**
     * Clear AI suggestions (e.g., when starting new upload).
     */
    fun clearSuggestions() {
        _aiSuggestions.update { null }
        _analysisState.update { AnalysisState.Idle }
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
    data object Queued : UploadUiState() // Upload in Queue, wird später synchronisiert
}

sealed class CreateTagState {
    data object Idle : CreateTagState()
    data object Creating : CreateTagState()
    data class Success(val tag: Tag) : CreateTagState()
    data class Error(val message: String) : CreateTagState()
}

sealed class AnalysisState {
    data object Idle : AnalysisState()
    data object Analyzing : AnalysisState()
    data object Success : AnalysisState()
    data class Error(val message: String) : AnalysisState()
    data class LimitInfo(val remainingCalls: Int) : AnalysisState() // 100+ calls used
    data class LimitWarning(val remainingCalls: Int) : AnalysisState() // 200+ calls used
    data object LimitReached : AnalysisState() // 300+ calls used (hard limit)
}
