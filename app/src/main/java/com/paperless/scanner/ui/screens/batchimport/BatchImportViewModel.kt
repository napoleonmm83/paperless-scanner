package com.paperless.scanner.ui.screens.batchimport

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.util.Log
import androidx.core.graphics.drawable.toBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.paperless.scanner.R
import com.paperless.scanner.data.ai.SuggestionOrchestrator
import com.paperless.scanner.data.ai.models.DocumentAnalysis
import com.paperless.scanner.data.ai.models.SuggestionResult
import com.paperless.scanner.data.ai.models.SuggestionSource
import com.paperless.scanner.data.ai.models.TagSuggestion
import com.paperless.scanner.data.billing.PremiumFeature
import com.paperless.scanner.data.billing.PremiumFeatureManager
import com.paperless.scanner.data.repository.AiUsageRepository
import com.paperless.scanner.data.repository.UsageLimitStatus
import com.paperless.scanner.domain.model.Correspondent
import com.paperless.scanner.domain.model.DocumentType
import com.paperless.scanner.domain.model.Tag
import com.paperless.scanner.data.repository.CorrespondentRepository
import com.paperless.scanner.data.repository.DocumentTypeRepository
import com.paperless.scanner.data.repository.TagRepository
import com.paperless.scanner.data.repository.UploadQueueRepository
import com.paperless.scanner.ui.screens.upload.AnalysisState
import com.paperless.scanner.util.FileUtils
import com.paperless.scanner.worker.UploadWorkManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class BatchImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val uploadQueueRepository: UploadQueueRepository,
    private val uploadWorkManager: UploadWorkManager,
    private val tagRepository: TagRepository,
    private val documentTypeRepository: DocumentTypeRepository,
    private val correspondentRepository: CorrespondentRepository,
    private val suggestionOrchestrator: SuggestionOrchestrator,
    private val aiUsageRepository: AiUsageRepository,
    private val premiumFeatureManager: PremiumFeatureManager,
    private val networkMonitor: com.paperless.scanner.data.network.NetworkMonitor
) : ViewModel() {

    private val _uiState = MutableStateFlow<BatchImportUiState>(BatchImportUiState.Idle)
    val uiState: StateFlow<BatchImportUiState> = _uiState.asStateFlow()

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
     * BEST PRACTICE: Reactive Flow for AI usage limits.
     */
    private fun observeUsageLimits() {
        viewModelScope.launch {
            aiUsageRepository.observeCurrentMonthCallCount().collect { callCount ->
                _remainingCalls.update { (300 - callCount).coerceAtLeast(0) }
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
     * BEST PRACTICE: Reactive Flow for tags.
     * Automatically updates dropdown when tags are added/modified/deleted.
     * Consistent with UploadViewModel pattern.
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

    // REMOVED: loadData()
    // This method is no longer needed because reactive Flows automatically
    // populate dropdown state via observeTagsReactively(), observeDocumentTypesReactively(),
    // and observeCorrespondentsReactively() in init{}

    fun queueBatchImport(
        imageUris: List<Uri>,
        title: String?,
        tagIds: List<Int>,
        documentTypeId: Int?,
        correspondentId: Int?,
        uploadAsSingleDocument: Boolean = false,
        uploadImmediately: Boolean = true
    ) {
        Log.d(TAG, "queueBatchImport called with ${imageUris.size} images, title=$title, asSingle=$uploadAsSingleDocument, uploadImmediately=$uploadImmediately")
        if (imageUris.isEmpty()) {
            Log.d(TAG, "No images to import, returning")
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                // CRITICAL: Copy content URIs to local storage before queuing
                // Content URIs from SAF (file picker) lose permissions when passed to WorkManager
                // Local file URIs can be safely accessed by background workers
                Log.d(TAG, "Copying files to local storage...")
                _uiState.update { BatchImportUiState.Queuing(0, imageUris.size) }

                val localUris = imageUris.mapIndexedNotNull { index, uri ->
                    val localUri = if (FileUtils.isLocalFileUri(uri)) {
                        // Already a local file, no need to copy
                        Log.d(TAG, "URI $index is already local: $uri")
                        uri
                    } else {
                        // Copy content URI to local storage
                        val copied = FileUtils.copyToLocalStorage(context, uri)
                        if (copied == null) {
                            Log.e(TAG, "Failed to copy URI $index: $uri")
                        }
                        copied
                    }
                    _uiState.update { BatchImportUiState.Queuing(index + 1, imageUris.size) }
                    localUri
                }

                if (localUris.isEmpty()) {
                    Log.e(TAG, "All file copies failed")
                    _uiState.update {
                        BatchImportUiState.Error(context.getString(R.string.error_queue_add))
                    }
                    return@launch
                }

                if (localUris.size < imageUris.size) {
                    Log.w(TAG, "Some files could not be copied: ${imageUris.size - localUris.size} failed")
                }

                Log.d(TAG, "Successfully copied ${localUris.size} files to local storage")

                if (uploadAsSingleDocument) {
                    // Alle Bilder als ein Multi-Page Dokument
                    uploadQueueRepository.queueMultiPageUpload(
                        uris = localUris,
                        title = title,
                        tagIds = tagIds,
                        documentTypeId = documentTypeId,
                        correspondentId = correspondentId
                    )
                    Log.d(TAG, "Multi-page document queued")
                } else {
                    // Jedes Bild einzeln hochladen
                    localUris.forEach { uri ->
                        uploadQueueRepository.queueUpload(
                            uri = uri,
                            title = title,
                            tagIds = tagIds,
                            documentTypeId = documentTypeId,
                            correspondentId = correspondentId
                        )
                    }
                }

                Log.d(TAG, "All images queued, scheduling upload")
                if (uploadImmediately) {
                    uploadWorkManager.scheduleImmediateUpload()
                } else {
                    uploadWorkManager.scheduleUpload()
                }

                val successCount = if (uploadAsSingleDocument) 1 else localUris.size
                _uiState.update { BatchImportUiState.Success(successCount) }
                Log.d(TAG, "Batch import completed successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error in queueBatchImport", e)
                _uiState.update {
                    BatchImportUiState.Error(
                        e.message ?: context.getString(R.string.error_queue_add)
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "BatchImportViewModel"
    }

    fun resetState() {
        _uiState.update { BatchImportUiState.Idle }
    }

    fun createTag(name: String, color: String? = null) {
        viewModelScope.launch {
            _createTagState.update { CreateTagState.Creating }
            tagRepository.createTag(name, color).fold(
                onSuccess = { tag ->
                    _createTagState.update { CreateTagState.Success(tag) }
                },
                onFailure = { error ->
                    _createTagState.update {
                        CreateTagState.Error(error.message ?: context.getString(R.string.error_create_tag))
                    }
                }
            )
        }
    }

    fun resetCreateTagState() {
        _createTagState.update { CreateTagState.Idle }
    }

    /**
     * Analyze the first document image using SuggestionOrchestrator.
     * For batch import, we analyze the first image to get tag suggestions.
     * Supports both images (loaded via Coil) and PDFs (rendered via PdfRenderer).
     */
    fun analyzeDocument(uri: Uri) {
        // Store URI for potential re-analysis with WiFi override
        lastAnalyzedUri = uri

        viewModelScope.launch(Dispatchers.IO) {
            _analysisState.update { AnalysisState.Analyzing }

            try {
                // Check usage limits for UI feedback
                val limitStatus = aiUsageRepository.checkUsageLimit()

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

                // Check if the file is a PDF
                val isPdf = FileUtils.isPdfFile(context, uri)
                Log.d(TAG, "Analyzing document: $uri, isPdf: $isPdf")

                val bitmap: Bitmap? = if (isPdf) {
                    // For PDFs, render the first page as a bitmap
                    Log.d(TAG, "Rendering PDF first page for AI analysis")
                    FileUtils.renderPdfFirstPage(context, uri, maxWidth = 1024)
                } else {
                    // For images, use Coil to load the bitmap
                    // Coil handles content URI permissions correctly
                    val imageLoader = ImageLoader(context)
                    val request = ImageRequest.Builder(context)
                        .data(uri)
                        .allowHardware(false) // Need software bitmap for AI analysis
                        .build()

                    val imageResult = imageLoader.execute(request)
                    if (imageResult is SuccessResult) {
                        (imageResult.drawable as? BitmapDrawable)?.bitmap
                            ?: imageResult.drawable.toBitmap()
                    } else {
                        Log.w(TAG, "Failed to load image with Coil: ${uri}")
                        null
                    }
                }

                if (bitmap == null) {
                    Log.w(TAG, "Could not decode ${if (isPdf) "PDF" else "image"} for analysis")
                    _analysisState.update { AnalysisState.Error(context.getString(R.string.error_analyze_document)) }
                    return@launch
                }

                // Use SuggestionOrchestrator for centralized suggestion logic
                val result = suggestionOrchestrator.getSuggestions(
                    bitmap = bitmap,
                    extractedText = "",
                    documentId = null,
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
                _analysisState.update { AnalysisState.Error(e.message ?: context.getString(R.string.error_analyze_document)) }
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
     *
     * Note: For batch import, we need to re-trigger analysis with the first document URI.
     * This is stored in the uiState, so we'll need to track it.
     */
    private var lastAnalyzedUri: Uri? = null

    fun overrideWifiOnlyForSession() {
        Log.d(TAG, "User overrode WiFi-only restriction")
        _wifiOnlyOverride.update { true }
        _wifiRequired.update { false }

        // Re-trigger analysis with override if we have a previously analyzed URI
        lastAnalyzedUri?.let { uri ->
            analyzeDocument(uri)
        }
    }
}

sealed class CreateTagState {
    data object Idle : CreateTagState()
    data object Creating : CreateTagState()
    data class Success(val tag: Tag) : CreateTagState()
    data class Error(val message: String) : CreateTagState()
}

sealed class BatchImportUiState {
    data object Idle : BatchImportUiState()
    data class Queuing(val current: Int, val total: Int) : BatchImportUiState()
    data class Success(val count: Int) : BatchImportUiState()
    data class Error(val message: String) : BatchImportUiState()
}
