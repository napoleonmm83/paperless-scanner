package com.paperless.scanner.ui.screens.upload

import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.data.ai.models.DocumentAnalysis
import com.paperless.scanner.data.ai.models.SuggestionResult
import com.paperless.scanner.data.ai.models.SuggestionSource
import com.paperless.scanner.data.repository.UsageLimitStatus
import com.paperless.scanner.domain.model.Correspondent
import com.paperless.scanner.domain.model.CustomField
import com.paperless.scanner.domain.model.DocumentType
import com.paperless.scanner.domain.model.Tag
import com.paperless.scanner.ui.navigation.AppLockRouteArgsHolder
import com.paperless.scanner.ui.screens.scan.ScannedPage
import com.paperless.scanner.ui.screens.upload.usecase.AnalyzeDocumentUseCase
import com.paperless.scanner.ui.screens.upload.usecase.CreateTagResult
import com.paperless.scanner.ui.screens.upload.usecase.PerformUploadUseCase
import com.paperless.scanner.ui.screens.upload.usecase.UploadMetadataUseCase
import com.paperless.scanner.ui.screens.upload.usecase.UploadResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class UploadViewModel @Inject constructor(
    private val savedStateHandle: androidx.lifecycle.SavedStateHandle,
    private val routeArgsHolder: AppLockRouteArgsHolder,
    private val performUploadUseCase: PerformUploadUseCase,
    private val uploadMetadataUseCase: UploadMetadataUseCase,
    private val analyzeDocumentUseCase: AnalyzeDocumentUseCase,
) : ViewModel() {

    companion object {
        private const val TAG = "UploadViewModel"
        const val KEY_DOCUMENT_URIS = "documentUris"
    }

    // Reactive documentUris with synchronous initialization from SavedStateHandle.
    //
    // The SavedStateHandle is populated either:
    //   (a) by Navigation Compose with the URL-encoded `documentUris` route argument
    //       (initial navigation; segments need URL-decoding), OR
    //   (b) by process-death restoration with the unencoded form we wrote here last time
    //       (segments don't need decoding — Uri.decode is a no-op for them).
    // parseDocumentUrisFromSavedState() handles both. After init {} returns, SavedStateHandle
    // holds the unencoded canonical form so subsequent restorations are idempotent.
    private val _documentUris = MutableStateFlow(parseDocumentUrisFromSavedState())
    val documentUris: StateFlow<List<Uri>> = _documentUris.asStateFlow()

    private fun parseDocumentUrisFromSavedState(): List<Uri> {
        val raw = savedStateHandle.get<String>(KEY_DOCUMENT_URIS) ?: return emptyList()
        if (raw.isEmpty()) return emptyList()
        return raw.split("|").withIndex().mapNotNull { (index, segment) ->
            // Try a direct Uri.parse first. The canonicalised (process-death) form is
            // a valid URI string and round-trips correctly — even when the original URI
            // contains percent-encoded characters in the path (e.g. "%23"), which a
            // blind Uri.decode would corrupt by turning into a literal "#" and shifting
            // it into the fragment position. If the direct parse returns no scheme,
            // we're dealing with the URL-encoded nav-arg form and need an explicit
            // Uri.decode pass before re-parsing.
            val parsed = runCatching {
                val direct = Uri.parse(segment)
                if (!direct.scheme.isNullOrBlank()) direct
                else Uri.parse(Uri.decode(segment))
            }.getOrNull()

            if (parsed == null || parsed.scheme.isNullOrBlank()) {
                // Don't log the segment itself — content:// URIs can contain provider/file IDs
                // that count as user-identifiable. Index + length are enough to debug.
                Log.e(TAG, "Failed to parse URI at segment[$index] (len=${segment.length})")
                null
            } else {
                parsed
            }
        }
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
    val isWifiConnected: StateFlow<Boolean> = performUploadUseCase.isWifiConnected

    // Observe network and server status for status-specific queue messages
    val isOnline: StateFlow<Boolean> = performUploadUseCase.isOnline
    val isServerReachable: StateFlow<Boolean> = performUploadUseCase.isServerReachable

    // Premium status for AI Tagging PRO badge
    val isPremiumActive: StateFlow<Boolean> = analyzeDocumentUseCase.isPremiumAccessEnabled
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    // Observe AI new tags setting
    val aiNewTagsEnabled: Flow<Boolean> = analyzeDocumentUseCase.aiNewTagsEnabled

    /**
     * Whether AI suggestions are available (Debug build or Premium subscription).
     * Used to conditionally show the SuggestionsSection in UploadScreen.
     * In release builds without Premium, suggestions are only available AFTER upload
     * via the Paperless API in DocumentDetailScreen.
     */
    val isAiAvailable: Boolean
        get() = analyzeDocumentUseCase.isAiAvailable()

    // AI Usage Limit State
    private val _usageLimitStatus = MutableStateFlow<UsageLimitStatus>(UsageLimitStatus.WITHIN_LIMITS)
    val usageLimitStatus: StateFlow<UsageLimitStatus> = _usageLimitStatus.asStateFlow()

    private val _remainingCalls = MutableStateFlow<Int>(300)
    val remainingCalls: StateFlow<Int> = _remainingCalls.asStateFlow()

    init {
        // Canonicalise SavedStateHandle to the unencoded form so process-death restoration
        // and the Screen's BackStackEntry-sync (which writes unencoded) stay consistent.
        val canonical = _documentUris.value.joinToString("|") { it.toString() }
        savedStateHandle[KEY_DOCUMENT_URIS] = canonical.takeIf { it.isNotEmpty() }
        // Single source of truth for AppLock route reconstruction (#30): mirror the
        // URI write into the holder here, co-located with the SavedStateHandle write.
        // documentUris is immutable after init, so this one write is sufficient.
        routeArgsHolder.put(KEY_DOCUMENT_URIS, canonical.takeIf { it.isNotEmpty() })

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
            uploadMetadataUseCase.observeTags().collect { tagList ->
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
            uploadMetadataUseCase.observeDocumentTypes().collect { types ->
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
            uploadMetadataUseCase.observeCorrespondents().collect { correspondentList ->
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
            uploadMetadataUseCase.observeCustomFields().collect { fields ->
                _customFields.update { fields.sortedBy { it.name.lowercase() } }
            }
        }
        // Initial load
        viewModelScope.launch {
            uploadMetadataUseCase.loadCustomFields()
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
            analyzeDocumentUseCase.observeCurrentMonthCallCount().collect { callCount ->
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

    fun uploadDocument(
        uri: Uri,
        title: String? = null,
        tagIds: List<Int> = emptyList(),
        documentTypeId: Int? = null,
        correspondentId: Int? = null,
        customFields: Map<Int, String> = emptyMap()
    ) {
        viewModelScope.launch {
            performUploadUseCase.uploadSingle(
                uri = uri,
                title = title,
                tagIds = tagIds,
                documentTypeId = documentTypeId,
                correspondentId = correspondentId,
                customFields = customFields
            ).collect { result -> _uiState.update { result.toUiState() } }
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
        viewModelScope.launch {
            performUploadUseCase.uploadMultiPage(
                uris = uris,
                uploadAsSingleDocument = uploadAsSingleDocument,
                title = title,
                tagIds = tagIds,
                documentTypeId = documentTypeId,
                correspondentId = correspondentId,
                customFields = customFields
            ).collect { result -> _uiState.update { result.toUiState() } }
        }
    }

    /**
     * Upload pages with per-page metadata.
     * Groups pages by identical metadata and creates separate uploads for each group.
     *
     * @param pages List of ScannedPage objects with customMetadata
     */
    fun uploadPagesWithMetadata(pages: List<ScannedPage>) {
        viewModelScope.launch {
            performUploadUseCase.uploadPagesWithMetadata(pages)
                .collect { result -> _uiState.update { result.toUiState() } }
        }
    }

    private fun UploadResult.toUiState(): UploadUiState = when (this) {
        UploadResult.Queuing -> UploadUiState.Queuing
        UploadResult.Queued -> UploadUiState.Queued
        is UploadResult.Failed -> UploadUiState.Error(
            userMessage = userMessage,
            technicalDetails = technicalDetails,
            isRetryable = isRetryable
        )
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
        viewModelScope.launch {
            _createTagState.update { CreateTagState.Creating }

            when (val result = uploadMetadataUseCase.createTag(name = name, color = color)) {
                is CreateTagResult.Success -> {
                    // BEST PRACTICE: No manual list update needed!
                    // observeTagsReactively() automatically updates dropdown.
                    _createTagState.update { CreateTagState.Success(result.tag) }
                }
                is CreateTagResult.Failure -> {
                    _createTagState.update { CreateTagState.Error(result.message) }
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
        viewModelScope.launch {
            _analysisState.update { AnalysisState.Analyzing }

            // Single try/catch around the whole flow: any failure (usage-limit check,
            // analysis, or post-analysis usage logging) must surface as AnalysisState.Error
            // rather than cancelling the coroutine and leaving the UI stuck in Analyzing.
            try {
                // Check usage limits for UI feedback
                val limitStatus = analyzeDocumentUseCase.checkUsageLimit()

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

                // Use AnalyzeDocumentUseCase for centralized suggestion logic
                // Handles bitmap decode, Premium check, fallback chain (AI → Paperless → Local), and merging
                when (val result = analyzeDocumentUseCase.analyze(uri, overrideWifiOnly = _wifiOnlyOverride.value)) {
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
                            analyzeDocumentUseCase.logFirebaseUsage()
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
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Document analysis failed", e)
                _analysisState.update {
                    AnalysisState.Error(e.message ?: analyzeDocumentUseCase.analysisErrorMessage)
                }
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
            analyzeDocumentUseCase.setAiNewTagsEnabled(enabled)
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
