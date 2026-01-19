package com.paperless.scanner.ui.screens.upload

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.R
import com.paperless.scanner.data.ai.SuggestionOrchestrator
import com.paperless.scanner.data.billing.PremiumFeature
import com.paperless.scanner.data.billing.PremiumFeatureManager
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
import com.paperless.scanner.util.FileUtils
import com.paperless.scanner.util.NetworkUtils
import com.paperless.scanner.utils.RetryUtil
import com.paperless.scanner.utils.StorageUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
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
    private val premiumFeatureManager: PremiumFeatureManager,
    private val paperlessGptRepository: com.paperless.scanner.data.ai.paperlessgpt.PaperlessGptRepository,
    private val taskRepository: com.paperless.scanner.data.repository.TaskRepository,
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

    private val _suggestionSource = MutableStateFlow<SuggestionSource?>(null)
    val suggestionSource: StateFlow<SuggestionSource?> = _suggestionSource.asStateFlow()

    // WiFi-Only State
    private val _wifiRequired = MutableStateFlow(false)
    val wifiRequired: StateFlow<Boolean> = _wifiRequired.asStateFlow()

    private val _wifiOnlyOverride = MutableStateFlow(false)
    val wifiOnlyOverride: StateFlow<Boolean> = _wifiOnlyOverride.asStateFlow()

    // Observe WiFi status for reactive UI
    val isWifiConnected: StateFlow<Boolean> = networkMonitor.isWifiConnected

    /**
     * Whether AI suggestions are available (Debug build or Premium subscription).
     * Used to conditionally show the SuggestionsSection in UploadScreen.
     * In release builds without Premium, suggestions are only available AFTER upload
     * via the Paperless API in DocumentDetailScreen.
     */
    val isAiAvailable: Boolean
        get() = premiumFeatureManager.isFeatureAvailable(PremiumFeature.AI_ANALYSIS)

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

    /**
     * Checks storage space before upload and returns error if insufficient.
     *
     * @return null if storage check passed, Error state otherwise
     */
    private suspend fun checkStorage(uris: List<Uri>): UploadUiState.Error? {
        val storageCheck = StorageUtil.checkStorageForUpload(context, uris)

        if (!storageCheck.hasEnoughSpace) {
            Log.w(TAG, "Storage check failed: ${storageCheck.message}")
            analyticsService.trackEvent(AnalyticsEvent.UploadFailed(errorType = "storage_insufficient"))

            return UploadUiState.Error(
                userMessage = "Nicht genug Speicherplatz verfügbar",
                technicalDetails = storageCheck.message,
                isRetryable = false
            )
        }

        // Check individual file sizes
        uris.forEach { uri ->
            StorageUtil.validateFileSize(context, uri).onFailure { e ->
                Log.w(TAG, "File size validation failed: ${e.message}")
                analyticsService.trackEvent(AnalyticsEvent.UploadFailed(errorType = "file_too_large"))

                return UploadUiState.Error(
                    userMessage = "Datei zu groß",
                    technicalDetails = e.message,
                    isRetryable = false
                )
            }
        }

        return null  // Storage check passed
    }

    /**
     * Converts exception to user-friendly error message with technical details.
     *
     * @param exception The exception that occurred
     * @return UploadUiState.Error with user message and details
     */
    private fun createErrorState(exception: Throwable): UploadUiState.Error {
        val errorInfo: Pair<String, Boolean> = when {
            // Network errors - retryable
            exception is java.net.SocketTimeoutException -> {
                "Zeitüberschreitung. Server antwortet nicht." to true
            }
            exception is java.net.UnknownHostException -> {
                "Server nicht erreichbar. Prüfe deine Internetverbindung." to true
            }
            exception is java.net.ConnectException -> {
                "Verbindung zum Server fehlgeschlagen. Ist der Server online?" to true
            }
            exception is java.io.IOException -> {
                "Netzwerkfehler. Prüfe deine Verbindung." to true
            }

            // HTTP errors
            exception is retrofit2.HttpException -> {
                when (exception.code()) {
                    401 -> "Nicht autorisiert. Prüfe deinen Zugangsschlüssel." to false
                    403 -> "Zugriff verweigert. Keine Berechtigung zum Hochladen." to false
                    413 -> "Datei zu groß für Server." to false
                    500, 502, 503, 504 -> "Server-Fehler. Versuche es später erneut." to true
                    else -> "Upload fehlgeschlagen (HTTP ${exception.code()})." to false
                }
            }

            // Storage errors
            exception.message?.contains("Speicher", ignoreCase = true) == true ||
            exception.message?.contains("storage", ignoreCase = true) == true -> {
                "Nicht genug Speicherplatz verfügbar." to false
            }

            // File errors
            exception is IllegalArgumentException -> {
                "Datei konnte nicht gelesen werden." to false
            }

            // Generic fallback
            else -> {
                (exception.message ?: "Upload fehlgeschlagen.") to false
            }
        }

        val (userMessage, isRetryable) = errorInfo

        return UploadUiState.Error(
            userMessage = userMessage,
            technicalDetails = "${exception::class.simpleName}: ${exception.message}",
            isRetryable = isRetryable
        )
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

            // Check storage BEFORE attempting upload
            checkStorage(listOf(uri))?.let { error ->
                _uiState.update { error }
                return@launch
            }

            // Check network availability - if offline, queue the upload
            if (!networkMonitor.checkOnlineStatus()) {
                Log.d(TAG, "Offline detected - queueing upload for later sync")
                // Copy file to local storage to ensure WorkManager can access it later
                val localUri = if (FileUtils.isLocalFileUri(uri)) {
                    uri
                } else {
                    FileUtils.copyToLocalStorage(context, uri) ?: run {
                        Log.e(TAG, "Failed to copy file for offline queue: $uri")
                        _uiState.update { UploadUiState.Error(
                            userMessage = "Fehler beim Speichern für Offline-Warteschlange",
                            technicalDetails = context.getString(R.string.error_queue_add),
                            isRetryable = false
                        ) }
                        return@launch
                    }
                }
                uploadQueueRepository.queueUpload(
                    uri = localUri,
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

            // Use RetryUtil for automatic retry with exponential backoff
            val result = RetryUtil.retryWithExponentialBackoff(
                maxAttempts = 3,
                initialDelay = 2000L,
                onRetry = { attempt, delay, error ->
                    Log.d(TAG, "Retrying upload (attempt $attempt/${3}, delay=${delay}ms): ${error.message}")
                    _uiState.update { UploadUiState.Retrying(attempt, 3, delay) }
                    delay(delay)  // Wait before retry
                    _uiState.update { UploadUiState.Uploading(0f) }  // Reset to uploading
                }
            ) {
                documentRepository.uploadDocument(
                    uri = uri,
                    title = title,
                    tagIds = tagIds,
                    documentTypeId = documentTypeId,
                    correspondentId = correspondentId,
                    onProgress = { progress ->
                        _uiState.update { UploadUiState.Uploading(progress) }
                    }
                ).getOrThrow()  // Convert Result to exception for retry logic
            }

            result
                .onSuccess { taskId ->
                    val durationMs = System.currentTimeMillis() - startTime
                    analyticsService.trackEvent(AnalyticsEvent.UploadSuccess(pageCount = 1, durationMs = durationMs))
                    lastUploadParams = null
                    _uiState.update { UploadUiState.Success(taskId) }

                    // Auto-trigger OCR improvement if needed (background, non-blocking)
                    autoTriggerOcrIfNeeded(taskId)
                }
                .onFailure { exception ->
                    analyticsService.trackEvent(AnalyticsEvent.UploadFailed(errorType = "upload_error"))
                    _uiState.update { createErrorState(exception) }
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

            // Check storage BEFORE attempting upload
            checkStorage(uris)?.let { error ->
                _uiState.update { error }
                return@launch
            }

            // Check network availability - if offline, queue the upload
            if (!networkMonitor.checkOnlineStatus()) {
                Log.d(TAG, "Offline detected - queueing multi-page upload for later sync")
                // Copy files to local storage to ensure WorkManager can access them later
                val localUris = uris.mapNotNull { uri ->
                    if (FileUtils.isLocalFileUri(uri)) {
                        uri
                    } else {
                        FileUtils.copyToLocalStorage(context, uri)
                    }
                }
                if (localUris.isEmpty()) {
                    Log.e(TAG, "Failed to copy any files for offline queue")
                    _uiState.update { UploadUiState.Error(
                        userMessage = "Fehler beim Speichern für Offline-Warteschlange",
                        technicalDetails = context.getString(R.string.error_queue_add),
                        isRetryable = false
                    ) }
                    return@launch
                }
                uploadQueueRepository.queueMultiPageUpload(
                    uris = localUris,
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

            // Use RetryUtil for automatic retry with exponential backoff
            val result = RetryUtil.retryWithExponentialBackoff(
                maxAttempts = 3,
                initialDelay = 2000L,
                onRetry = { attempt, delay, error ->
                    Log.d(TAG, "Retrying multi-page upload (attempt $attempt/${3}, delay=${delay}ms): ${error.message}")
                    _uiState.update { UploadUiState.Retrying(attempt, 3, delay) }
                    delay(delay)  // Wait before retry
                    _uiState.update { UploadUiState.Uploading(0f) }  // Reset to uploading
                }
            ) {
                documentRepository.uploadMultiPageDocument(
                    uris = uris,
                    title = title,
                    tagIds = tagIds,
                    documentTypeId = documentTypeId,
                    correspondentId = correspondentId,
                    onProgress = { progress ->
                        _uiState.update { UploadUiState.Uploading(progress) }
                    }
                ).getOrThrow()  // Convert Result to exception for retry logic
            }

            result
                .onSuccess { taskId ->
                    val durationMs = System.currentTimeMillis() - startTime
                    analyticsService.trackEvent(AnalyticsEvent.UploadSuccess(pageCount = pageCount, durationMs = durationMs))
                    lastUploadParams = null
                    _uiState.update { UploadUiState.Success(taskId) }

                    // Auto-trigger OCR improvement if needed (background, non-blocking)
                    autoTriggerOcrIfNeeded(taskId)
                }
                .onFailure { exception ->
                    analyticsService.trackEvent(AnalyticsEvent.UploadFailed(errorType = "multi_page_upload_error"))
                    _uiState.update { createErrorState(exception) }
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
                    // Handle duplicate tag error - try to find existing tag
                    if (e.message?.contains("unique constraint") == true ||
                        e.message?.contains("already exists") == true) {
                        Log.d(TAG, "Tag '$name' already exists, trying to find it...")
                        // Refresh tags from server and try to find the existing tag
                        tagRepository.getTags(forceRefresh = true)
                        val existingTag = tagRepository.observeTags().first()
                            .find { it.name.equals(name, ignoreCase = true) }
                        if (existingTag != null) {
                            Log.d(TAG, "Found existing tag: ${existingTag.name} (id=${existingTag.id})")
                            _createTagState.update { CreateTagState.Success(existingTag) }
                            return@launch
                        }
                    }
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
                    documentId = null, // Not applicable for pre-upload analysis
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

                        // Store the suggestion source for UI display
                        _suggestionSource.update { result.source }

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
        _suggestionSource.update { null }
        _wifiRequired.update { false }
        _wifiOnlyOverride.update { false }
    }

    /**
     * Override WiFi-only restriction for current session.
     * Allows user to use AI even without WiFi when they explicitly choose "Use anyway".
     *
     * Note: Does not automatically re-trigger analysis.
     * User must click "Analyze" button again after clicking "Use anyway".
     */
    fun overrideWifiOnlyForSession() {
        Log.d(TAG, "User overrode WiFi-only restriction")
        _wifiOnlyOverride.update { true }
        _wifiRequired.update { false }

        // User must manually trigger analysis again by clicking "Analyze" button
        // This provides explicit control and avoids unexpected data usage
    }

    /**
     * Automatically trigger Paperless-GPT OCR job if document has low OCR confidence.
     *
     * **Workflow** (runs in background, non-blocking):
     * 1. Poll task until completion (max 2 minutes)
     * 2. Extract document ID from task
     * 3. Fetch document details to get OCR confidence
     * 4. If confidence < 0.8 AND Paperless-GPT enabled → trigger OCR job
     * 5. Poll OCR job status until completion
     *
     * **Note:** This runs in background coroutine - user can navigate away during processing.
     *
     * @param taskId Task ID from document upload
     */
    private fun autoTriggerOcrIfNeeded(taskId: String) {
        viewModelScope.launch(ioDispatcher) {
            try {
                // Check if Paperless-GPT is enabled
                if (!paperlessGptRepository.isEnabled()) {
                    Log.d(TAG, "Paperless-GPT disabled, skipping OCR auto-trigger")
                    return@launch
                }

                Log.d(TAG, "OCR Auto-Trigger: Polling task $taskId for completion")

                // Step 1: Poll task until completion (max 2 minutes = 60 iterations @ 2s each)
                var documentId: Int? = null
                repeat(60) { iteration ->
                    delay(2000) // 2 seconds

                    taskRepository.getTask(taskId).fold(
                        onSuccess = { task ->
                            if (task == null) {
                                Log.w(TAG, "Task $taskId not found")
                                return@launch
                            }

                            when (task.status) {
                                "SUCCESS" -> {
                                    // Extract document ID from relatedDocument field
                                    // Format can be: "123" (plain ID) or "/api/documents/123/" (URL)
                                    documentId = task.relatedDocument?.let { relDoc ->
                                        // Try to parse as Int first
                                        relDoc.toIntOrNull()
                                            // If that fails, extract from URL pattern
                                            ?: Regex("""/api/documents/(\d+)""").find(relDoc)?.groupValues?.get(1)?.toIntOrNull()
                                            // If that fails, try just extracting digits
                                            ?: relDoc.filter { it.isDigit() }.toIntOrNull()
                                    }

                                    if (documentId != null) {
                                        Log.d(TAG, "OCR Auto-Trigger: Task completed, document ID = $documentId")
                                        return@repeat // Exit polling loop
                                    } else {
                                        Log.w(TAG, "OCR Auto-Trigger: Task completed but no document ID found: ${task.relatedDocument}")
                                        return@launch
                                    }
                                }
                                "FAILURE" -> {
                                    Log.w(TAG, "OCR Auto-Trigger: Task failed, skipping OCR")
                                    return@launch
                                }
                                "PENDING", "STARTED" -> {
                                    Log.d(TAG, "OCR Auto-Trigger: Task still processing (${task.status})")
                                    // Continue polling
                                }
                            }
                        },
                        onFailure = { error ->
                            Log.e(TAG, "OCR Auto-Trigger: Failed to get task status", error)
                            return@launch
                        }
                    )

                    // Timeout check
                    if (iteration == 59) {
                        Log.w(TAG, "OCR Auto-Trigger: Task polling timeout (2 minutes)")
                        return@launch
                    }
                }

                // Step 2: Fetch document details to get OCR confidence
                if (documentId == null) {
                    Log.w(TAG, "OCR Auto-Trigger: Document ID is null after polling")
                    return@launch
                }

                Log.d(TAG, "OCR Auto-Trigger: Fetching document $documentId details")
                documentRepository.getDocument(documentId!!).fold(
                    onSuccess = { document ->
                        val confidence = document.ocrConfidence
                        Log.d(TAG, "OCR Auto-Trigger: Document OCR confidence = $confidence")

                        // Step 3: Check confidence threshold and trigger OCR if needed
                        if (confidence == null) {
                            Log.d(TAG, "OCR Auto-Trigger: No OCR confidence available, skipping")
                            return@launch
                        }

                        if (confidence >= 0.8) {
                            Log.d(TAG, "OCR Auto-Trigger: Confidence ${confidence} >= 0.8, skipping OCR")
                            return@launch
                        }

                        // Trigger OCR improvement
                        Log.d(TAG, "OCR Auto-Trigger: Triggering OCR job for document $documentId (confidence: $confidence)")
                        paperlessGptRepository.autoTriggerOcrIfNeeded(
                            documentId = documentId!!,
                            ocrConfidence = confidence
                        ).fold(
                            onSuccess = {
                                Log.d(TAG, "OCR Auto-Trigger: OCR job completed successfully for document $documentId")
                                analyticsService.trackEvent(
                                    AnalyticsEvent.PaperlessGptOcrAutoSuccess(
                                        documentId = documentId!!,
                                        originalConfidence = confidence
                                    )
                                )
                            },
                            onFailure = { error ->
                                Log.e(TAG, "OCR Auto-Trigger: OCR job failed for document $documentId", error)
                                analyticsService.trackEvent(
                                    AnalyticsEvent.PaperlessGptOcrAutoFailed(
                                        documentId = documentId!!,
                                        error = error.message ?: "unknown"
                                    )
                                )
                            }
                        )
                    },
                    onFailure = { error ->
                        Log.e(TAG, "OCR Auto-Trigger: Failed to fetch document $documentId", error)
                    }
                )

            } catch (e: Exception) {
                Log.e(TAG, "OCR Auto-Trigger: Unexpected error", e)
            }
        }
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
    data class Retrying(val attempt: Int, val maxAttempts: Int, val nextDelay: Long) : UploadUiState()
    data class Success(val taskId: String) : UploadUiState()
    data class Error(
        val userMessage: String,           // Benutzerfreundliche Nachricht
        val technicalDetails: String? = null,  // Technische Details (ausklappbar)
        val isRetryable: Boolean = false   // Kann automatisch retried werden
    ) : UploadUiState()
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
