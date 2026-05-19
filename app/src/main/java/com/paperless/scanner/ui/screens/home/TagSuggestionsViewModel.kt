package com.paperless.scanner.ui.screens.home

import android.content.Context
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.R
import com.paperless.scanner.data.ai.SuggestionOrchestrator
import com.paperless.scanner.data.ai.models.SuggestionResult
import com.paperless.scanner.data.analytics.AnalyticsEvent
import com.paperless.scanner.data.analytics.AnalyticsService
import com.paperless.scanner.data.billing.PremiumFeatureManager
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.data.repository.DocumentListRepository
import com.paperless.scanner.data.repository.DocumentMetadataRepository
import com.paperless.scanner.data.repository.TagRepository
import com.paperless.scanner.domain.model.Tag
import com.paperless.scanner.util.NetworkConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject

/**
 * Tag creation state for the suggestions sheet / SmartTaggingScreen flow.
 *
 * Moved from [HomeViewModel] in Phase 3 of the god-VM decomposition (#72).
 */
sealed class CreateTagState {
    data object Idle : CreateTagState()
    data object Creating : CreateTagState()
    data class Success(val tag: Tag) : CreateTagState()
    data class Error(val message: String) : CreateTagState()
}

sealed class TagSuggestionsError {
    data class LoadFailed(val source: String, val cause: Throwable) : TagSuggestionsError()
}

/**
 * Owns the untagged-documents + AI tag-suggestions flow shared between
 * [HomeScreen]'s bottom sheet and [SmartTaggingScreen].
 *
 * Phase 3 of the [HomeViewModel] god-VM decomposition (issue #72). No
 * coordinator hook is needed: tag mutations go through
 * [DocumentMetadataRepository.updateDocument], which invalidates the Room
 * cached_document table, and [HomeViewModel] observes the untagged count
 * via [com.paperless.scanner.data.repository.DocumentCountRepository.observeUntaggedDocumentsCount]
 * — the dashboard counter therefore updates automatically.
 */
@HiltViewModel
class TagSuggestionsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tagRepository: TagRepository,
    private val documentListRepository: DocumentListRepository,
    private val documentMetadataRepository: DocumentMetadataRepository,
    private val suggestionOrchestrator: SuggestionOrchestrator,
    private val tokenManager: TokenManager,
    private val premiumFeatureManager: PremiumFeatureManager,
    private val analyticsService: AnalyticsService,
) : ViewModel() {

    companion object {
        private val logger = Logger.getLogger(TagSuggestionsViewModel::class.java.name)
        private const val ANALYZE_THUMBNAIL_TIMEOUT_MS = 10_000
    }

    val availableTags: StateFlow<List<Tag>> = tagRepository.observeTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val isAiAvailable: StateFlow<Boolean> = premiumFeatureManager.isAiEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _tagSuggestionsState = MutableStateFlow(TagSuggestionsState())
    val tagSuggestionsState: StateFlow<TagSuggestionsState> = _tagSuggestionsState.asStateFlow()

    private val _showTagSuggestionsSheet = MutableStateFlow(false)
    val showTagSuggestionsSheet: StateFlow<Boolean> = _showTagSuggestionsSheet.asStateFlow()

    private val _createTagState = MutableStateFlow<CreateTagState>(CreateTagState.Idle)
    val createTagState: StateFlow<CreateTagState> = _createTagState.asStateFlow()

    private val _error = MutableStateFlow<TagSuggestionsError?>(null)
    val error: StateFlow<TagSuggestionsError?> = _error.asStateFlow()

    /**
     * Per-document analyze guard. Re-tapping "analyze" on a document that is
     * already mid-analysis would otherwise queue duplicate AI requests and
     * let the older response overwrite a newer one. Pattern mirrors the
     * Job guard used in [ProcessingTasksViewModel.startTaskPolling] and
     * [HomeViewModel.onPollingTick].
     */
    private val analyzeJobs = mutableMapOf<Int, Job>()

    // ==================== SHEET LIFECYCLE ====================

    fun openTagSuggestionsSheet() {
        _showTagSuggestionsSheet.value = true
        loadUntaggedDocuments()
    }

    /**
     * Entry-point for [SmartTaggingScreen], which is a full-screen variant
     * of the same flow and does not toggle the sheet visibility.
     */
    fun loadUntaggedDocumentsForScreen() {
        loadUntaggedDocuments()
    }

    fun closeTagSuggestionsSheet() {
        _showTagSuggestionsSheet.value = false
        _tagSuggestionsState.value = TagSuggestionsState()
        // Cancel any in-flight analyses and drop completed Job references so
        // the map can't accumulate or confuse the re-entry guard if a doc id
        // is reused in a fresh session.
        analyzeJobs.values.forEach { it.cancel() }
        analyzeJobs.clear()
    }

    private fun loadUntaggedDocuments() {
        viewModelScope.launch {
            _tagSuggestionsState.update { it.copy(isLoading = true) }

            documentListRepository.getUntaggedDocuments().onSuccess { documents ->
                val serverUrl = tokenManager.serverUrl.first() ?: ""
                val authToken = tokenManager.token.first() ?: ""

                val untaggedDocs = documents.map { doc ->
                    UntaggedDocument(
                        id = doc.id,
                        title = doc.title,
                        thumbnailUrl = if (serverUrl.isNotEmpty() && authToken.isNotEmpty()) {
                            "$serverUrl/api/documents/${doc.id}/thumb/"
                        } else null,
                        analysisState = UntaggedDocAnalysisState.Idle,
                    )
                }

                _tagSuggestionsState.update {
                    it.copy(documents = untaggedDocs, isLoading = false)
                }

                if (serverUrl.isNotEmpty() && authToken.isNotEmpty()) {
                    untaggedDocs.forEach { doc ->
                        loadThumbnailForDocument(doc.id, serverUrl, authToken)
                    }
                }
            }.onFailure { error ->
                logger.log(Level.WARNING, "Failed to load untagged documents: ${error.message}")
                _tagSuggestionsState.update { it.copy(isLoading = false) }
                _error.value = TagSuggestionsError.LoadFailed("untaggedDocuments", error)
            }
        }
    }

    /**
     * Shared thumbnail download for [loadThumbnailForDocument] (prefetch with
     * the default user-facing timeout) and the [analyzeDocument] preflight
     * (longer AI-analyze timeout). Returns null on any IO/decoding failure.
     */
    private suspend fun downloadThumbnail(
        documentId: Int,
        serverUrl: String,
        authToken: String,
        timeoutMs: Int = NetworkConfig.THUMBNAIL_TIMEOUT_MS,
    ): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
        try {
            val thumbnailUrl = "$serverUrl/api/documents/$documentId/thumb/"
            val connection = URL(thumbnailUrl).openConnection()
            connection.setRequestProperty("Authorization", "Token $authToken")
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.getInputStream().use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            }
        } catch (e: Exception) {
            logger.log(Level.WARNING, "Failed to download thumbnail for doc $documentId: ${e.message}")
            null
        }
    }

    private fun loadThumbnailForDocument(documentId: Int, serverUrl: String, authToken: String) {
        viewModelScope.launch {
            val bitmap = downloadThumbnail(documentId, serverUrl, authToken)

            if (bitmap != null) {
                _tagSuggestionsState.update { state ->
                    state.copy(
                        documents = state.documents.map { doc ->
                            if (doc.id == documentId) doc.copy(thumbnailBitmap = bitmap) else doc
                        },
                    )
                }
            }
        }
    }

    // ==================== AI ANALYZE ====================

    fun analyzeDocument(documentId: Int) {
        if (analyzeJobs[documentId]?.isActive == true) return

        analyzeJobs[documentId] = viewModelScope.launch {
            updateDocumentState(documentId, UntaggedDocAnalysisState.LoadingThumbnail)

            try {
                val serverUrl = tokenManager.serverUrl.first() ?: ""
                val authToken = tokenManager.token.first() ?: ""

                if (serverUrl.isEmpty() || authToken.isEmpty()) {
                    updateDocumentState(
                        documentId,
                        UntaggedDocAnalysisState.Error(context.getString(R.string.error_not_authenticated)),
                    )
                    return@launch
                }

                val bitmap = downloadThumbnail(documentId, serverUrl, authToken, ANALYZE_THUMBNAIL_TIMEOUT_MS)

                if (bitmap == null) {
                    updateDocumentState(
                        documentId,
                        UntaggedDocAnalysisState.Error(context.getString(R.string.tag_suggestions_error_thumbnail)),
                    )
                    return@launch
                }

                _tagSuggestionsState.update { state ->
                    state.copy(
                        documents = state.documents.map { doc ->
                            if (doc.id == documentId) {
                                doc.copy(
                                    thumbnailBitmap = bitmap,
                                    analysisState = UntaggedDocAnalysisState.Analyzing,
                                )
                            } else doc
                        },
                    )
                }

                val result = suggestionOrchestrator.getSuggestions(
                    bitmap = bitmap,
                    documentId = documentId,
                )

                when (result) {
                    is SuggestionResult.Success -> {
                        val existingTags = availableTags.value

                        val preSelectedIds = result.analysis.suggestedTags
                            .mapNotNull { suggestion ->
                                suggestion.tagId ?: existingTags.find {
                                    it.name.equals(suggestion.tagName, ignoreCase = true)
                                }?.id
                            }
                            .toSet()

                        val newTagSuggestions = result.analysis.suggestedTags
                            .filter { suggestion ->
                                val hasTagId = suggestion.tagId != null
                                val existsInSystem = existingTags.any {
                                    it.name.equals(suggestion.tagName, ignoreCase = true)
                                }
                                !hasTagId && !existsInSystem
                            }

                        _tagSuggestionsState.update { state ->
                            state.copy(
                                documents = state.documents.map { doc ->
                                    if (doc.id == documentId) {
                                        doc.copy(
                                            analysisState = UntaggedDocAnalysisState.Success(result.analysis),
                                            suggestions = result.analysis,
                                            selectedTagIds = preSelectedIds,
                                            suggestedNewTags = newTagSuggestions,
                                        )
                                    } else doc
                                },
                            )
                        }

                        analyticsService.trackEvent(
                            AnalyticsEvent.AiFeatureUsed(
                                featureType = "tag_suggestions_home",
                                inputTokens = 0,
                                outputTokens = 0,
                                estimatedCostUsd = 0.0,
                                subscriptionType = "unknown",
                                success = true,
                            ),
                        )
                    }
                    is SuggestionResult.Error -> {
                        updateDocumentState(documentId, UntaggedDocAnalysisState.Error(result.message))
                    }
                    is SuggestionResult.Loading -> {
                        // Already in loading state, no action needed.
                    }
                    is SuggestionResult.WiFiRequired -> {
                        // WiFi-only restriction does not apply to HomeScreen analysis
                        // (documents are already uploaded). Handle gracefully.
                        logger.log(Level.WARNING, "WiFiRequired in TagSuggestionsViewModel - should not happen")
                        updateDocumentState(documentId, UntaggedDocAnalysisState.Idle)
                    }
                }
            } catch (e: Exception) {
                logger.log(Level.WARNING, "Error analyzing document $documentId: ${e.message}")
                updateDocumentState(
                    documentId,
                    UntaggedDocAnalysisState.Error(e.message ?: context.getString(R.string.error_unknown)),
                )
            }
        }
    }

    private fun updateDocumentState(documentId: Int, state: UntaggedDocAnalysisState) {
        _tagSuggestionsState.update { currentState ->
            currentState.copy(
                documents = currentState.documents.map { doc ->
                    if (doc.id == documentId) doc.copy(analysisState = state) else doc
                },
            )
        }
    }

    // ==================== APPLY / SKIP ====================

    fun applyTagsToDocument(documentId: Int, tagIds: List<Int>) {
        viewModelScope.launch {
            try {
                documentMetadataRepository.updateDocument(documentId, tags = tagIds)
                    .onSuccess {
                        logger.log(Level.INFO, "Applied tags $tagIds to document $documentId")

                        _tagSuggestionsState.update { state ->
                            state.copy(
                                documents = state.documents.map { doc ->
                                    if (doc.id == documentId) doc.copy(isTagged = true) else doc
                                },
                                taggedCount = state.taggedCount + 1,
                            )
                        }

                        analyticsService.trackEvent(
                            AnalyticsEvent.AiSuggestionAccepted(
                                featureType = "tag_suggestions_home",
                                suggestionCount = tagIds.size,
                            ),
                        )
                    }
                    .onFailure { error ->
                        logger.log(Level.WARNING, "Failed to apply tags: ${error.message}")
                        updateDocumentState(
                            documentId,
                            UntaggedDocAnalysisState.Error(
                                error.message ?: context.getString(R.string.error_unknown),
                            ),
                        )
                    }
            } catch (e: Exception) {
                logger.log(Level.WARNING, "Error applying tags: ${e.message}")
                // Without this update the document stays on its prior state
                // (e.g. Analyzing / Success) even though the apply silently
                // failed — surface the error so the user can retry.
                updateDocumentState(
                    documentId,
                    UntaggedDocAnalysisState.Error(
                        e.message ?: context.getString(R.string.error_unknown),
                    ),
                )
            }
        }
    }

    fun skipDocument(documentId: Int) {
        _tagSuggestionsState.update { state ->
            state.copy(
                documents = state.documents.map { doc ->
                    if (doc.id == documentId) doc.copy(isSkipped = true) else doc
                },
            )
        }
    }

    // ==================== TAG PICKER ====================

    fun openTagPicker(documentId: Int) {
        _tagSuggestionsState.update {
            it.copy(showTagPicker = true, tagPickerDocumentId = documentId)
        }
    }

    fun closeTagPicker() {
        _tagSuggestionsState.update {
            it.copy(showTagPicker = false, tagPickerDocumentId = null)
        }
    }

    fun toggleTagInPicker(documentId: Int, tagId: Int) {
        _tagSuggestionsState.update { state ->
            state.copy(
                documents = state.documents.map { doc ->
                    if (doc.id == documentId) {
                        val newSelectedIds = if (doc.selectedTagIds.contains(tagId)) {
                            doc.selectedTagIds - tagId
                        } else {
                            doc.selectedTagIds + tagId
                        }
                        doc.copy(selectedTagIds = newSelectedIds)
                    } else doc
                },
            )
        }
    }

    fun applyPickerTags(documentId: Int) {
        val document = _tagSuggestionsState.value.documents.find { it.id == documentId }
        if (document != null && document.selectedTagIds.isNotEmpty()) {
            applyTagsToDocument(documentId, document.selectedTagIds.toList())
            closeTagPicker()
        }
    }

    // ==================== TAG CREATION ====================

    fun createTag(name: String, color: String? = null) {
        viewModelScope.launch {
            _createTagState.update { CreateTagState.Creating }

            tagRepository.createTag(name = name, color = color)
                .onSuccess { newTag ->
                    logger.log(Level.INFO, "Tag created: ${newTag.name}")
                    analyticsService.trackEvent(AnalyticsEvent.TagCreated)
                    _createTagState.update { CreateTagState.Success(newTag) }
                }
                .onFailure { e ->
                    logger.log(Level.WARNING, "Failed to create tag: ${e.message}")
                    if (e.message?.contains("unique constraint") == true ||
                        e.message?.contains("already exists") == true
                    ) {
                        val existingTag = tagRepository.observeTags().first()
                            .find { it.name.equals(name, ignoreCase = true) }
                        if (existingTag != null) {
                            _createTagState.update { CreateTagState.Success(existingTag) }
                        } else {
                            _createTagState.update {
                                CreateTagState.Error(context.getString(R.string.error_create_tag))
                            }
                        }
                    } else {
                        _createTagState.update {
                            CreateTagState.Error(e.message ?: context.getString(R.string.error_create_tag))
                        }
                    }
                }
        }
    }

    fun resetCreateTagState() {
        _createTagState.update { CreateTagState.Idle }
    }

    /**
     * Creates a suggested new tag and auto-selects it for the specified
     * document, then removes it from the document's suggestedNewTags list.
     */
    fun createSuggestedTag(documentId: Int, tagName: String) {
        viewModelScope.launch {
            _createTagState.update { CreateTagState.Creating }

            tagRepository.createTag(name = tagName, color = null)
                .onSuccess { newTag ->
                    logger.log(Level.INFO, "Suggested tag created: ${newTag.name}")
                    analyticsService.trackEvent(AnalyticsEvent.TagCreated)
                    autoSelectTagForDocument(documentId, newTag.id, tagName)
                    _createTagState.update { CreateTagState.Success(newTag) }
                }
                .onFailure { e ->
                    logger.log(Level.WARNING, "Failed to create suggested tag: ${e.message}")
                    if (e.message?.contains("unique constraint") == true ||
                        e.message?.contains("already exists") == true
                    ) {
                        val existingTag = tagRepository.observeTags().first()
                            .find { it.name.equals(tagName, ignoreCase = true) }
                        if (existingTag != null) {
                            autoSelectTagForDocument(documentId, existingTag.id, tagName)
                            _createTagState.update { CreateTagState.Success(existingTag) }
                        } else {
                            _createTagState.update {
                                CreateTagState.Error(context.getString(R.string.error_create_tag))
                            }
                        }
                    } else {
                        _createTagState.update {
                            CreateTagState.Error(e.message ?: context.getString(R.string.error_create_tag))
                        }
                    }
                }
        }
    }

    private fun autoSelectTagForDocument(documentId: Int, newTagId: Int, originalSuggestionName: String) {
        _tagSuggestionsState.update { state ->
            state.copy(
                documents = state.documents.map { doc ->
                    if (doc.id == documentId) {
                        doc.copy(
                            selectedTagIds = doc.selectedTagIds + newTagId,
                            suggestedNewTags = doc.suggestedNewTags.filter {
                                !it.tagName.equals(originalSuggestionName, ignoreCase = true)
                            },
                        )
                    } else doc
                },
            )
        }
    }

    // ==================== ERROR ====================

    fun clearError() {
        _error.value = null
    }
}
