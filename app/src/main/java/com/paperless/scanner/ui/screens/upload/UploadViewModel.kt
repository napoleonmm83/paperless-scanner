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
                Log.d(TAG, "documentUrisStateFlow.map: input=$urisString, parsed=${parsed.size} URIs")
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
            Log.w(TAG, "setDocumentUris called with empty list")
            return
        }
        Log.d(TAG, "setDocumentUris: Setting ${uris.size} URIs")
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

    fun uploadDocument(
        uri: Uri,
        title: String? = null,
        tagIds: List<Int> = emptyList(),
        documentTypeId: Int? = null,
        correspondentId: Int? = null
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
                    Log.d(TAG, "URI is already local file: $uri")
                    uri
                } else {
                    Log.d(TAG, "Copying content URI to persistent storage: $uri")
                    FileUtils.copyToLocalStorage(context, uri) ?: run {
                        Log.e(TAG, "Failed to copy file for queue: $uri")
                        _uiState.update { UploadUiState.Error(
                            userMessage = "Fehler beim Speichern",
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
                        userMessage = "Datei konnte nicht gespeichert werden",
                        technicalDetails = "File not accessible: ${localUri.lastPathSegment}",
                        isRetryable = false
                    ) }
                    return@launch
                }

                val fileSize = FileUtils.getFileSize(localUri)
                Log.d(TAG, "Queueing upload: $localUri ($fileSize bytes)")

                // Queue the upload - WorkManager will handle retry, network checks, etc.
                val queueId = uploadQueueRepository.queueUpload(
                    uri = localUri,
                    title = title,
                    tagIds = tagIds,
                    documentTypeId = documentTypeId,
                    correspondentId = correspondentId
                )
                Log.d(TAG, "Upload queued with ID: $queueId")

                // Trigger immediate upload processing
                uploadWorkManager.scheduleImmediateUpload()

                analyticsService.trackEvent(AnalyticsEvent.UploadQueued(isOffline = false))
                _uiState.update { UploadUiState.Queued }
            } catch (e: Exception) {
                Log.e(TAG, "Error queueing upload", e)
                analyticsService.trackEvent(AnalyticsEvent.UploadFailed(errorType = "queue_error"))
                _uiState.update { UploadUiState.Error(
                    userMessage = "Fehler beim Hinzufügen zur Warteschlange",
                    technicalDetails = e.message ?: "Unknown error",
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
        correspondentId: Int? = null
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
                Log.d(TAG, "Queueing multi-page upload (${uris.size} pages)")

                // Copy files to local storage to ensure WorkManager can access them later
                // Content URIs from SAF lose permissions when passed to WorkManager
                val localUris = uris.mapIndexedNotNull { index, uri ->
                    if (FileUtils.isLocalFileUri(uri)) {
                        Log.d(TAG, "  Page ${index + 1}: Already local file: $uri")
                        uri
                    } else {
                        Log.d(TAG, "  Page ${index + 1}: Copying content URI to persistent storage: $uri")
                        val copiedUri = FileUtils.copyToLocalStorage(context, uri)
                        if (copiedUri == null) {
                            Log.e(TAG, "  Page ${index + 1}: Failed to copy: $uri")
                        }
                        copiedUri
                    }
                }

                if (localUris.isEmpty()) {
                    Log.e(TAG, "Failed to copy any files for queue (0/${uris.size} succeeded)")
                    _uiState.update { UploadUiState.Error(
                        userMessage = "Fehler beim Speichern",
                        technicalDetails = context.getString(R.string.error_queue_add),
                        isRetryable = false
                    ) }
                    return@launch
                }

                if (localUris.size < uris.size) {
                    Log.w(TAG, "Only ${localUris.size}/${uris.size} files copied successfully")
                }

                // Verify all files exist before queueing
                val missingFiles = localUris.filterNot { FileUtils.fileExists(it) }
                if (missingFiles.isNotEmpty()) {
                    Log.e(TAG, "File validation failed: ${missingFiles.size}/${localUris.size} files not accessible")
                    missingFiles.forEach { uri ->
                        Log.e(TAG, "  Missing: $uri")
                    }
                    _uiState.update { UploadUiState.Error(
                        userMessage = "Einige Dateien konnten nicht gespeichert werden",
                        technicalDetails = "${missingFiles.size}/${localUris.size} files not accessible",
                        isRetryable = false
                    ) }
                    return@launch
                }

                val totalSize = localUris.sumOf { FileUtils.getFileSize(it) }
                Log.d(TAG, "Queueing multi-page upload: ${localUris.size} files ($totalSize bytes total)")

                // Queue the upload - WorkManager will handle retry, network checks, etc.
                if (uploadAsSingleDocument) {
                    // Combined: Merge all pages into a single PDF document
                    val queueId = uploadQueueRepository.queueMultiPageUpload(
                        uris = localUris,
                        title = title,
                        tagIds = tagIds,
                        documentTypeId = documentTypeId,
                        correspondentId = correspondentId
                    )
                    Log.d(TAG, "Multi-page upload queued as single document with ID: $queueId")
                } else {
                    // Individual: Queue each page as a separate document
                    Log.d(TAG, "Queueing ${localUris.size} individual documents")
                    localUris.forEachIndexed { index, uri ->
                        val individualTitle = if (title.isNullOrBlank()) {
                            null
                        } else if (localUris.size > 1) {
                            "$title (${index + 1}/${localUris.size})"
                        } else {
                            title
                        }
                        val queueId = uploadQueueRepository.queueUpload(
                            uri = uri,
                            title = individualTitle,
                            tagIds = tagIds,
                            documentTypeId = documentTypeId,
                            correspondentId = correspondentId
                        )
                        Log.d(TAG, "  Document ${index + 1}/${localUris.size} queued with ID: $queueId")
                    }
                    Log.d(TAG, "All ${localUris.size} individual documents queued successfully")
                }

                // Trigger immediate upload processing
                uploadWorkManager.scheduleImmediateUpload()

                analyticsService.trackEvent(AnalyticsEvent.UploadQueued(isOffline = false))
                _uiState.update { UploadUiState.Queued }
            } catch (e: Exception) {
                Log.e(TAG, "Error queueing multi-page upload", e)
                analyticsService.trackEvent(AnalyticsEvent.UploadFailed(errorType = "queue_error"))
                _uiState.update { UploadUiState.Error(
                    userMessage = "Fehler beim Hinzufügen zur Warteschlange",
                    technicalDetails = e.message ?: "Unknown error",
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
            analyticsService.trackEvent(AnalyticsEvent.UploadStarted(pageCount = pageCount, isMultiPage = true))

            val uris = pages.map { it.uri }

            // Check storage BEFORE queueing
            checkStorage(uris)?.let { error ->
                _uiState.update { error }
                return@launch
            }

            _uiState.update { UploadUiState.Queuing }

            try {
                Log.d(TAG, "Queueing pages with per-page metadata (${pages.size} pages)")

                // Copy files to local storage
                val localPages = pages.mapNotNull { page ->
                    val localUri = if (FileUtils.isLocalFileUri(page.uri)) {
                        Log.d(TAG, "  Page ${page.pageNumber}: Already local file: ${page.uri}")
                        page.uri
                    } else {
                        Log.d(TAG, "  Page ${page.pageNumber}: Copying content URI to persistent storage: ${page.uri}")
                        val copiedUri = FileUtils.copyToLocalStorage(context, page.uri)
                        if (copiedUri == null) {
                            Log.e(TAG, "  Page ${page.pageNumber}: Failed to copy: ${page.uri}")
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
                        userMessage = "Fehler beim Speichern",
                        technicalDetails = context.getString(R.string.error_queue_add),
                        isRetryable = false
                    ) }
                    return@launch
                }

                if (localPages.size < pages.size) {
                    Log.w(TAG, "Only ${localPages.size}/${pages.size} files copied successfully")
                }

                // Verify all files exist before queueing
                val missingFiles = localPages.filterNot { FileUtils.fileExists(it.uri) }
                if (missingFiles.isNotEmpty()) {
                    Log.e(TAG, "File validation failed: ${missingFiles.size}/${localPages.size} files not accessible")
                    _uiState.update { UploadUiState.Error(
                        userMessage = "Einige Dateien konnten nicht gespeichert werden",
                        technicalDetails = "${missingFiles.size}/${localPages.size} files not accessible",
                        isRetryable = false
                    ) }
                    return@launch
                }

                // Group pages by metadata
                val groups = localPages.groupBy { it.customMetadata }

                Log.d(TAG, "Grouped ${localPages.size} pages into ${groups.size} upload groups")

                // Create uploads for each group
                var uploadCount = 0
                groups.forEach { (metadata, groupPages) ->
                    val groupUris = groupPages.map { it.uri }
                    val title = metadata?.title
                    val tagIds = metadata?.tags ?: emptyList()
                    val documentTypeId = metadata?.documentType
                    val correspondentId = metadata?.correspondent

                    if (groupPages.size == 1) {
                        // Single page with unique metadata -> Individual upload
                        val queueId = uploadQueueRepository.queueUpload(
                            uri = groupUris.first(),
                            title = title,
                            tagIds = tagIds,
                            documentTypeId = documentTypeId,
                            correspondentId = correspondentId
                        )
                        uploadCount++
                        Log.d(TAG, "  Group ${uploadCount}: 1 page queued as single document (ID: $queueId)")
                    } else {
                        // Multiple pages with same metadata -> Multi-page upload
                        val queueId = uploadQueueRepository.queueMultiPageUpload(
                            uris = groupUris,
                            title = title,
                            tagIds = tagIds,
                            documentTypeId = documentTypeId,
                            correspondentId = correspondentId
                        )
                        uploadCount++
                        Log.d(TAG, "  Group ${uploadCount}: ${groupPages.size} pages queued as multi-page document (ID: $queueId)")
                    }
                }

                Log.d(TAG, "All ${uploadCount} upload groups queued successfully")

                // Trigger immediate upload processing
                uploadWorkManager.scheduleImmediateUpload()

                analyticsService.trackEvent(AnalyticsEvent.UploadQueued(isOffline = false))
                _uiState.update { UploadUiState.Queued }
            } catch (e: Exception) {
                Log.e(TAG, "Error queueing pages with metadata", e)
                analyticsService.trackEvent(AnalyticsEvent.UploadFailed(errorType = "queue_error"))
                _uiState.update { UploadUiState.Error(
                    userMessage = "Fehler beim Hinzufügen zur Warteschlange",
                    technicalDetails = e.message ?: "Unknown error",
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

    // REMOVED: autoTriggerOcrIfNeeded()
    // This function is no longer compatible with the queue-based upload system.
    // OCR auto-trigger should be implemented in UploadWorker in the future.

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
