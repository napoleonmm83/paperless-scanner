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
import com.paperless.scanner.data.api.models.CustomField
import com.paperless.scanner.domain.model.Correspondent
import com.paperless.scanner.domain.model.DocumentType
import com.paperless.scanner.domain.model.Tag
import com.paperless.scanner.data.repository.AiUsageRepository
import com.paperless.scanner.data.repository.CorrespondentRepository
import com.paperless.scanner.data.repository.CustomFieldRepository
import com.paperless.scanner.data.repository.DocumentTypeRepository
import com.paperless.scanner.data.repository.TagRepository
import com.paperless.scanner.data.repository.UsageLimitStatus
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.util.FileUtils
import com.paperless.scanner.utils.StorageUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.delay
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
import javax.inject.Inject

@HiltViewModel
class UploadViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val savedStateHandle: androidx.lifecycle.SavedStateHandle,
    private val tagRepository: TagRepository,
    private val documentTypeRepository: DocumentTypeRepository,
    private val correspondentRepository: CorrespondentRepository,
    private val customFieldRepository: CustomFieldRepository,
    private val uploadQueueRepository: com.paperless.scanner.data.repository.UploadQueueRepository,
    private val uploadWorkManager: com.paperless.scanner.worker.UploadWorkManager,
    private val networkMonitor: com.paperless.scanner.data.network.NetworkMonitor,
    private val serverHealthMonitor: com.paperless.scanner.data.health.ServerHealthMonitor,
    private val analyticsService: AnalyticsService,
    private val suggestionOrchestrator: SuggestionOrchestrator,
    private val aiUsageRepository: AiUsageRepository,
    private val premiumFeatureManager: PremiumFeatureManager,
    private val tokenManager: TokenManager,
    private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    companion object {
        private const val TAG = "UploadViewModel"
        private const val KEY_DOCUMENT_URIS = "documentUris"
    }

    // Reactive documentUris using SavedStateHandle.getStateFlow()
    // Automatically survives process death and configuration changes
    private val documentUrisStateFlow: StateFlow<List<Uri>> =
        savedStateHandle.getStateFlow<String?>(KEY_DOCUMENT_URIS, null)
            .map { urisString ->
                val parsed = if (urisString.isNullOrEmpty()) {
                    emptyList()
                } else {
                    urisString.split("|").mapNotNull { uriString ->
                        try {
                            Uri.parse(uriString)
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse URI: $uriString", e)
                            null
                        }
                    }
                }
                parsed
            }
            .stateIn(
                scope = viewModelScope,
                started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    val documentUris: StateFlow<List<Uri>> = documentUrisStateFlow

    /**
     * Initialize document URIs from navigation arguments.
     * Called from the Screen when navigation arguments are received.
     */
    fun setDocumentUris(uris: List<Uri>) {
        if (uris.isEmpty()) {
            return
        }
        val urisString = uris.joinToString("|") { it.toString() }
        savedStateHandle[KEY_DOCUMENT_URIS] = urisString
    }

    private val _uiState = MutableStateFlow<UploadUiState>(UploadUiState.Idle)
    val uiState: StateFlow<UploadUiState> = _uiState.asStateFlow()

    private val _tags = MutableStateFlow<List<Tag>>(emptyList())
    val tags: StateFlow<List<Tag>> = _tags.asStateFlow()

    private val _documentTypes = MutableStateFlow<List<DocumentType>>(emptyList())
    val documentTypes: StateFlow<List<DocumentType>> = _documentTypes.asStateFlow()

    private val _correspondents = MutableStateFlow<List<Correspondent>>(emptyList())
    val correspondents: StateFlow<List<Correspondent>> = _correspondents.asStateFlow()

    private val _customFields = MutableStateFlow<List<CustomField>>(emptyList())
    val customFields: StateFlow<List<CustomField>> = _customFields.asStateFlow()

    private val _customFieldValues = MutableStateFlow<Map<Int, String>>(emptyMap())
    val customFieldValues: StateFlow<Map<Int, String>> = _customFieldValues.asStateFlow()

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

    // Observe network and server status for status-specific queue messages
    val isOnline: StateFlow<Boolean> = networkMonitor.isOnline
    val isServerReachable: StateFlow<Boolean> = serverHealthMonitor.isServerReachable

    // Premium status for AI Tagging PRO badge
    val isPremiumActive: StateFlow<Boolean> = premiumFeatureManager.isPremiumAccessEnabled
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    // Observe AI new tags setting
    val aiNewTagsEnabled: Flow<Boolean> = tokenManager.aiNewTagsEnabled

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

    init {
        observeTagsReactively()
        observeDocumentTypesReactively()
        observeCorrespondentsReactively()
        observeCustomFieldsReactively()
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
     * BEST PRACTICE: Reactive Flow for custom fields.
     * Automatically updates UI when custom fields are added/modified/deleted.
     * Uses feature detection - silently returns empty list if server doesn't support custom fields.
     */
    private fun observeCustomFieldsReactively() {
        viewModelScope.launch {
            customFieldRepository.observeCustomFields().collect { fields ->
                _customFields.update { fields.sortedBy { it.name.lowercase() } }
            }
        }
        // Initial load
        viewModelScope.launch(ioDispatcher) {
            customFieldRepository.getCustomFields()
        }
    }

    /**
     * Set a custom field value.
     * @param fieldId The ID of the custom field
     * @param value The value as String (null to clear)
     */
    fun setCustomFieldValue(fieldId: Int, value: String?) {
        _customFieldValues.update { current ->
            if (value.isNullOrBlank()) {
                current - fieldId
            } else {
                current + (fieldId to value)
            }
        }
    }

    /**
     * Clear all custom field values (e.g., when starting a new upload).
     */
    fun clearCustomFieldValues() {
        _customFieldValues.update { emptyMap() }
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
                userMessage = context.getString(R.string.error_not_enough_storage),
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
                    userMessage = context.getString(R.string.error_file_too_large_short),
                    technicalDetails = e.message,
                    isRetryable = false
                )
            }
        }

        return null  // Storage check passed
    }

    fun uploadDocument(
        uri: Uri,
        title: String? = null,
        tagIds: List<Int> = emptyList(),
        documentTypeId: Int? = null,
        correspondentId: Int? = null,
        customFields: Map<Int, String> = emptyMap()
    ) {
        viewModelScope.launch(ioDispatcher) {
            analyticsService.trackEvent(AnalyticsEvent.UploadStarted(pageCount = 1, isMultiPage = false))

            // Check storage BEFORE queueing
            checkStorage(listOf(uri))?.let { error ->
                _uiState.update { error }
                return@launch
            }

            _uiState.update { UploadUiState.Queuing }

            try {
                // Copy file to local storage to ensure WorkManager can access it later
                // Content URIs from SAF lose permissions when passed to WorkManager
                val localUri = if (FileUtils.isLocalFileUri(uri)) {
                    uri
                } else {
                    FileUtils.copyToLocalStorage(context, uri) ?: run {
                        Log.e(TAG, "Failed to copy file for queue: $uri")
                        _uiState.update { UploadUiState.Error(
                            userMessage = context.getString(R.string.error_saving_file),
                            technicalDetails = context.getString(R.string.error_queue_add),
                            isRetryable = false
                        ) }
                        return@launch
                    }
                }

                // Verify file exists before queueing
                if (!FileUtils.fileExists(localUri)) {
                    Log.e(TAG, "File validation failed after copy: $localUri")
                    _uiState.update { UploadUiState.Error(
                        userMessage = context.getString(R.string.error_file_not_saved),
                        technicalDetails = context.getString(R.string.error_file_not_accessible, localUri.lastPathSegment ?: ""),
                        isRetryable = false
                    ) }
                    return@launch
                }

                // Queue the upload - WorkManager will handle retry, network checks, etc.
                val queueId = uploadQueueRepository.queueUpload(
                    uri = localUri,
                    title = title,
                    tagIds = tagIds,
                    documentTypeId = documentTypeId,
                    correspondentId = correspondentId,
                    customFields = customFields
                )

                // Trigger immediate upload processing
                uploadWorkManager.scheduleImmediateUpload()

                analyticsService.trackEvent(AnalyticsEvent.UploadQueued(isOffline = false))
                _uiState.update { UploadUiState.Queued }
            } catch (e: Exception) {
                Log.e(TAG, "Error queueing upload", e)
                analyticsService.trackEvent(AnalyticsEvent.UploadFailed(errorType = "queue_error"))
                _uiState.update { UploadUiState.Error(
                    userMessage = context.getString(R.string.error_adding_to_queue),
                    technicalDetails = e.message ?: context.getString(R.string.error_unknown_short),
                    isRetryable = false
                ) }
            }
        }
    }

    fun uploadMultiPageDocument(
        uris: List<Uri>,
        uploadAsSingleDocument: Boolean = true,
        title: String? = null,
        tagIds: List<Int> = emptyList(),
        documentTypeId: Int? = null,
        correspondentId: Int? = null,
        customFields: Map<Int, String> = emptyMap()
    ) {
        viewModelScope.launch(ioDispatcher) {
            val pageCount = uris.size

            analyticsService.trackEvent(AnalyticsEvent.UploadStarted(pageCount = pageCount, isMultiPage = true))

            // Check storage BEFORE queueing
            checkStorage(uris)?.let { error ->
                _uiState.update { error }
                return@launch
            }

            _uiState.update { UploadUiState.Queuing }

            try {
                // Copy files to local storage to ensure WorkManager can access them later
                // Content URIs from SAF lose permissions when passed to WorkManager
                // For large batches, this may take several minutes - process sequentially to avoid OOM
                val localUris = uris.mapIndexedNotNull { index, uri ->
                    // Log progress every 10 files for large batches
                    if (pageCount > 50 && (index + 1) % 10 == 0) {
                        Log.d(TAG, "Copying progress: ${index + 1}/$pageCount files")
                    }

                    if (FileUtils.isLocalFileUri(uri)) {
                        uri
                    } else {
                        val copiedUri = FileUtils.copyToLocalStorage(context, uri)
                        if (copiedUri == null) {
                            Log.e(TAG, "Page ${index + 1}/$pageCount: Failed to copy: $uri")
                        }
                        copiedUri
                    }
                }

                if (localUris.isEmpty()) {
                    Log.e(TAG, "Failed to copy any files for queue (0/${uris.size} succeeded)")
                    _uiState.update { UploadUiState.Error(
                        userMessage = context.getString(R.string.error_saving_file),
                        technicalDetails = context.getString(R.string.error_queue_add),
                        isRetryable = false
                    ) }
                    return@launch
                }

                // Verify all files exist before queueing
                val missingFiles = localUris.filterNot { FileUtils.fileExists(it) }
                if (missingFiles.isNotEmpty()) {
                    Log.e(TAG, "File validation failed: ${missingFiles.size}/${localUris.size} files not accessible")
                    missingFiles.forEach { uri ->
                        Log.e(TAG, "  Missing: $uri")
                    }
                    _uiState.update { UploadUiState.Error(
                        userMessage = context.getString(R.string.error_files_not_saved),
                        technicalDetails = context.getString(R.string.error_files_not_accessible, missingFiles.size, localUris.size),
                        isRetryable = false
                    ) }
                    return@launch
                }

                // Queue the upload - WorkManager will handle retry, network checks, etc.
                if (uploadAsSingleDocument) {
                    // Combined: Merge all pages into a single PDF document
                    uploadQueueRepository.queueMultiPageUpload(
                        uris = localUris,
                        title = title,
                        tagIds = tagIds,
                        documentTypeId = documentTypeId,
                        correspondentId = correspondentId,
                        customFields = customFields
                    )
                } else {
                    // Individual: Queue each page as a separate document
                    localUris.forEachIndexed { index, uri ->
                        val individualTitle = if (title.isNullOrBlank()) {
                            null
                        } else if (localUris.size > 1) {
                            "$title (${index + 1}/${localUris.size})"
                        } else {
                            title
                        }
                        uploadQueueRepository.queueUpload(
                            uri = uri,
                            title = individualTitle,
                            tagIds = tagIds,
                            documentTypeId = documentTypeId,
                            correspondentId = correspondentId,
                            customFields = customFields
                        )
                    }
                }

                // Trigger immediate upload processing
                uploadWorkManager.scheduleImmediateUpload()

                analyticsService.trackEvent(AnalyticsEvent.UploadQueued(isOffline = false))
                _uiState.update { UploadUiState.Queued }
            } catch (e: Exception) {
                Log.e(TAG, "Error queueing multi-page upload", e)
                analyticsService.trackEvent(AnalyticsEvent.UploadFailed(errorType = "queue_error"))
                _uiState.update { UploadUiState.Error(
                    userMessage = context.getString(R.string.error_adding_to_queue),
                    technicalDetails = e.message ?: context.getString(R.string.error_unknown_short),
                    isRetryable = false
                ) }
            }
        }
    }

    /**
     * Upload pages with per-page metadata.
     * Groups pages by identical metadata and creates separate uploads for each group.
     *
     * @param pages List of ScannedPage objects with customMetadata
     */
    fun uploadPagesWithMetadata(pages: List<com.paperless.scanner.ui.screens.scan.ScannedPage>) {
        viewModelScope.launch(ioDispatcher) {
            val pageCount = pages.size

            // CRITICAL: Warn for large batches (50+ files can cause performance issues)
            if (pageCount > 50) {
                Log.w(TAG, "Large batch upload detected: $pageCount pages. This may take several minutes.")
            }

            analyticsService.trackEvent(AnalyticsEvent.UploadStarted(pageCount = pageCount, isMultiPage = true))

            val uris = pages.map { it.uri }

            // Check storage BEFORE queueing
            checkStorage(uris)?.let { error ->
                _uiState.update { error }
                return@launch
            }

            _uiState.update { UploadUiState.Queuing }

            try {
                // Copy files to local storage
                // For large batches, this may take several minutes - process sequentially to avoid OOM
                val localPages = pages.mapIndexedNotNull { index, page ->
                    // Log progress every 10 files for large batches
                    if (pageCount > 50 && (index + 1) % 10 == 0) {
                        Log.d(TAG, "Copying progress: ${index + 1}/$pageCount pages")
                    }

                    val localUri = if (FileUtils.isLocalFileUri(page.uri)) {
                        page.uri
                    } else {
                        val copiedUri = FileUtils.copyToLocalStorage(context, page.uri)
                        if (copiedUri == null) {
                            Log.e(TAG, "Page ${page.pageNumber}: Failed to copy: ${page.uri}")
                        }
                        copiedUri
                    }

                    if (localUri != null) {
                        page.copy(uri = localUri)
                    } else {
                        null
                    }
                }

                if (localPages.isEmpty()) {
                    Log.e(TAG, "Failed to copy any files for queue (0/${pages.size} succeeded)")
                    _uiState.update { UploadUiState.Error(
                        userMessage = context.getString(R.string.error_saving_file),
                        technicalDetails = context.getString(R.string.error_queue_add),
                        isRetryable = false
                    ) }
                    return@launch
                }

                // Verify all files exist before queueing
                val missingFiles = localPages.filterNot { FileUtils.fileExists(it.uri) }
                if (missingFiles.isNotEmpty()) {
                    Log.e(TAG, "File validation failed: ${missingFiles.size}/${localPages.size} files not accessible")
                    _uiState.update { UploadUiState.Error(
                        userMessage = context.getString(R.string.error_files_not_saved),
                        technicalDetails = context.getString(R.string.error_files_not_accessible, missingFiles.size, localPages.size),
                        isRetryable = false
                    ) }
                    return@launch
                }

                // Group pages by metadata for organization
                val groups = localPages.groupBy { it.customMetadata }

                // CRITICAL: "Individuell bearbeiten" workflow ALWAYS uploads individual documents
                // Each image becomes a separate document in Paperless, even if they share metadata
                Log.i(TAG, "Queueing ${localPages.size} pages as individual documents (${groups.size} metadata groups)")

                // Create individual uploads for each page
                groups.forEach { (metadata, groupPages) ->
                    val title = metadata?.title
                    val tagIds = metadata?.tags ?: emptyList()
                    val documentTypeId = metadata?.documentType
                    val correspondentId = metadata?.correspondent

                    // Upload each page individually with automatic numbering
                    groupPages.forEachIndexed { index, page ->
                        val individualTitle = if (groupPages.size == 1) {
                            // Single page in group - use title as-is
                            title
                        } else {
                            // Multiple pages in group - add numbering
                            if (title.isNullOrBlank()) {
                                context.getString(R.string.document_numbered, index + 1, groupPages.size)
                            } else {
                                "$title (${index + 1}/${groupPages.size})"
                            }
                        }

                        uploadQueueRepository.queueUpload(
                            uri = page.uri,
                            title = individualTitle,
                            tagIds = tagIds,
                            documentTypeId = documentTypeId,
                            correspondentId = correspondentId
                        )
                    }
                }

                // Trigger immediate upload processing
                uploadWorkManager.scheduleImmediateUpload()

                analyticsService.trackEvent(AnalyticsEvent.UploadQueued(isOffline = false))
                _uiState.update { UploadUiState.Queued }
            } catch (e: Exception) {
                Log.e(TAG, "Error queueing pages with metadata", e)
                analyticsService.trackEvent(AnalyticsEvent.UploadFailed(errorType = "queue_error"))
                _uiState.update { UploadUiState.Error(
                    userMessage = context.getString(R.string.error_adding_to_queue),
                    technicalDetails = e.message ?: context.getString(R.string.error_unknown_short),
                    isRetryable = false
                ) }
            }
        }
    }

    fun resetState() {
        _uiState.update { UploadUiState.Idle }
    }

    fun clearError() {
        if (_uiState.value is UploadUiState.Error) {
            _uiState.update { UploadUiState.Idle }
        }
    }

    fun createTag(name: String, color: String? = null) {
        viewModelScope.launch(ioDispatcher) {
            _createTagState.update { CreateTagState.Creating }

            tagRepository.createTag(name = name, color = color)
                .onSuccess { newTag ->
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
                        // Refresh tags from server and try to find the existing tag
                        tagRepository.getTags(forceRefresh = true)
                        val existingTag = tagRepository.observeTags().first()
                            .find { it.name.equals(name, ignoreCase = true) }
                        if (existingTag != null) {
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
                        _analysisState.update { AnalysisState.LimitReached }
                    }
                    UsageLimitStatus.SOFT_LIMIT_200 -> {
                        _analysisState.update { AnalysisState.LimitWarning(_remainingCalls.value) }
                    }
                    UsageLimitStatus.SOFT_LIMIT_100 -> {
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
                        _wifiRequired.update { true }
                        _analysisState.update { AnalysisState.Idle }
                        // Don't show error - banner will inform user
                    }
                    is SuggestionResult.Success -> {
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
        _wifiOnlyOverride.update { true }
        _wifiRequired.update { false }

        // User must manually trigger analysis again by clicking "Analyze" button
        // This provides explicit control and avoids unexpected data usage
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

sealed class UploadUiState {
    data object Idle : UploadUiState()
    data object Queuing : UploadUiState() // Adding to queue (copying files, etc.)
    data object Queued : UploadUiState() // Upload in Queue, wird im Hintergrund verarbeitet
    data class Error(
        val userMessage: String,           // Benutzerfreundliche Nachricht
        val technicalDetails: String? = null,  // Technische Details (ausklappbar)
        val isRetryable: Boolean = false   // Kann automatisch retried werden
    ) : UploadUiState()
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
