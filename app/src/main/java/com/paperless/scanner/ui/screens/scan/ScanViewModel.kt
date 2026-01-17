package com.paperless.scanner.ui.screens.scan

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.paperless.scanner.R
import com.paperless.scanner.data.ai.SuggestionOrchestrator
import com.paperless.scanner.data.ai.models.DocumentAnalysis
import com.paperless.scanner.data.ai.models.SuggestionResult
import com.paperless.scanner.data.ai.models.SuggestionSource
import com.paperless.scanner.data.analytics.AnalyticsEvent
import com.paperless.scanner.data.analytics.AnalyticsService
import com.paperless.scanner.data.billing.PremiumFeature
import com.paperless.scanner.data.billing.PremiumFeatureManager
import com.paperless.scanner.data.repository.AiUsageRepository
import com.paperless.scanner.data.repository.AuthRepository
import com.paperless.scanner.data.repository.CorrespondentRepository
import com.paperless.scanner.data.repository.DocumentTypeRepository
import com.paperless.scanner.data.repository.TagRepository
import com.paperless.scanner.data.repository.UsageLimitStatus
import com.paperless.scanner.domain.model.Correspondent
import com.paperless.scanner.domain.model.DocumentType
import com.paperless.scanner.domain.model.Tag
import com.paperless.scanner.ui.screens.upload.AnalysisState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

data class ScannedPage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val uri: Uri,
    val pageNumber: Int,
    val rotation: Int = 0  // 0, 90, 180, 270
)

data class RemovedPageInfo(
    val page: ScannedPage,
    val originalIndex: Int
)

data class ScanUiState(
    val pages: List<ScannedPage> = emptyList(),
    val isProcessing: Boolean = false,
    val lastRemovedPage: RemovedPageInfo? = null,
    val tags: List<Tag> = emptyList(),
    val selectedTagIds: List<Int> = emptyList()
) {
    val pageCount: Int get() = pages.size
    val hasPages: Boolean get() = pages.isNotEmpty()
}

sealed class CreateTagState {
    data object Idle : CreateTagState()
    data object Creating : CreateTagState()
    data class Success(val tag: Tag) : CreateTagState()
    data class Error(val message: String) : CreateTagState()
}

@HiltViewModel
class ScanViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val analyticsService: AnalyticsService,
    private val tagRepository: TagRepository,
    private val documentTypeRepository: DocumentTypeRepository,
    private val correspondentRepository: CorrespondentRepository,
    private val suggestionOrchestrator: SuggestionOrchestrator,
    private val aiUsageRepository: AiUsageRepository,
    private val premiumFeatureManager: PremiumFeatureManager,
    val appLockManager: com.paperless.scanner.util.AppLockManager,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) : ViewModel() {

    companion object {
        private const val TAG = "ScanViewModel"
    }

    private val _uiState = MutableStateFlow(ScanUiState())
    val uiState: StateFlow<ScanUiState> = _uiState.asStateFlow()

    private val _createTagState = MutableStateFlow<CreateTagState>(CreateTagState.Idle)
    val createTagState: StateFlow<CreateTagState> = _createTagState.asStateFlow()

    // AI Suggestions State
    private val _aiSuggestions = MutableStateFlow<DocumentAnalysis?>(null)
    val aiSuggestions: StateFlow<DocumentAnalysis?> = _aiSuggestions.asStateFlow()

    private val _analysisState = MutableStateFlow<AnalysisState>(AnalysisState.Idle)
    val analysisState: StateFlow<AnalysisState> = _analysisState.asStateFlow()

    private val _suggestionSource = MutableStateFlow<SuggestionSource?>(null)
    val suggestionSource: StateFlow<SuggestionSource?> = _suggestionSource.asStateFlow()

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

    // Document Types and Correspondents
    private val _documentTypes = MutableStateFlow<List<DocumentType>>(emptyList())
    val documentTypes: StateFlow<List<DocumentType>> = _documentTypes.asStateFlow()

    private val _correspondents = MutableStateFlow<List<Correspondent>>(emptyList())
    val correspondents: StateFlow<List<Correspondent>> = _correspondents.asStateFlow()

    init {
        loadTags()
        observeDocumentTypes()
        observeCorrespondents()
        observeUsageLimits()
    }

    /**
     * Observe AI usage limits reactively.
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

    private fun loadTags() {
        viewModelScope.launch {
            tagRepository.observeTags().collect { tags ->
                _uiState.update { it.copy(tags = tags.sortedBy { tag -> tag.name.lowercase() }) }
            }
        }
    }

    private fun observeDocumentTypes() {
        viewModelScope.launch {
            documentTypeRepository.observeDocumentTypes().collect { types ->
                _documentTypes.update { types.sortedBy { it.name.lowercase() } }
            }
        }
    }

    private fun observeCorrespondents() {
        viewModelScope.launch {
            correspondentRepository.observeCorrespondents().collect { correspondentList ->
                _correspondents.update { correspondentList.sortedBy { it.name.lowercase() } }
            }
        }
    }

    fun toggleTag(tagId: Int) {
        _uiState.update { state ->
            val currentSelected = state.selectedTagIds.toMutableList()
            if (currentSelected.contains(tagId)) {
                currentSelected.remove(tagId)
            } else {
                currentSelected.add(tagId)
            }
            state.copy(selectedTagIds = currentSelected)
        }
    }

    fun createTag(name: String, color: String? = null) {
        viewModelScope.launch {
            _createTagState.update { CreateTagState.Creating }
            tagRepository.createTag(name, color).fold(
                onSuccess = { tag ->
                    // Add newly created tag to selection
                    _uiState.update { state ->
                        state.copy(
                            tags = (state.tags + tag).sortedBy { it.name.lowercase() },
                            selectedTagIds = state.selectedTagIds + tag.id
                        )
                    }
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

    fun getSelectedTagIds(): List<Int> = _uiState.value.selectedTagIds

    fun clearSelectedTags() {
        _uiState.update { it.copy(selectedTagIds = emptyList()) }
    }

    fun addPages(uris: List<Uri>) {
        _uiState.update { state ->
            val startIndex = state.pageCount
            val newPages = uris.mapIndexed { index, uri ->
                ScannedPage(
                    uri = uri,
                    pageNumber = startIndex + index + 1
                )
            }
            val newTotalPages = state.pageCount + uris.size
            analyticsService.trackEvent(AnalyticsEvent.ScanPageAdded(totalPages = newTotalPages))
            state.copy(pages = state.pages + newPages)
        }
    }

    fun removePage(pageId: String) {
        _uiState.update { state ->
            val removedIndex = state.pages.indexOfFirst { it.id == pageId }
            if (removedIndex == -1) return@update state

            analyticsService.trackEvent(AnalyticsEvent.ScanPageRemoved)
            val removedPage = state.pages[removedIndex]
            val filteredPages = state.pages.filter { it.id != pageId }
            val renumberedPages = filteredPages.mapIndexed { index, page ->
                page.copy(pageNumber = index + 1)
            }
            state.copy(
                pages = renumberedPages,
                lastRemovedPage = RemovedPageInfo(removedPage, removedIndex)
            )
        }
    }

    fun undoRemovePage() {
        _uiState.update { state ->
            val removedPageInfo = state.lastRemovedPage ?: return@update state

            val mutablePages = state.pages.toMutableList()
            mutablePages.add(removedPageInfo.originalIndex, removedPageInfo.page)

            val renumberedPages = mutablePages.mapIndexed { index, page ->
                page.copy(pageNumber = index + 1)
            }
            state.copy(
                pages = renumberedPages,
                lastRemovedPage = null
            )
        }
    }

    fun clearLastRemovedPage() {
        _uiState.update { it.copy(lastRemovedPage = null) }
    }

    fun movePage(fromIndex: Int, toIndex: Int) {
        _uiState.update { state ->
            if (fromIndex < 0 || fromIndex >= state.pageCount ||
                toIndex < 0 || toIndex >= state.pageCount) {
                return@update state
            }

            analyticsService.trackEvent(AnalyticsEvent.ScanPagesReordered)
            val mutablePages = state.pages.toMutableList()
            val page = mutablePages.removeAt(fromIndex)
            mutablePages.add(toIndex, page)

            val renumberedPages = mutablePages.mapIndexed { index, p ->
                p.copy(pageNumber = index + 1)
            }
            state.copy(pages = renumberedPages)
        }
    }

    fun rotatePage(pageId: String) {
        analyticsService.trackEvent(AnalyticsEvent.ScanPageRotated)
        _uiState.update { state ->
            val updatedPages = state.pages.map { page ->
                if (page.id == pageId) {
                    page.copy(rotation = (page.rotation + 90) % 360)
                } else {
                    page
                }
            }
            state.copy(pages = updatedPages)
        }
    }

    fun clearPages() {
        _uiState.update { it.copy(pages = emptyList()) }
    }

    fun getPageUris(): List<Uri> = _uiState.value.pages.map { it.uri }

    fun getPages(): List<ScannedPage> = _uiState.value.pages

    /**
     * Returns URIs with rotation applied. Creates rotated copies for pages with rotation != 0.
     */
    suspend fun getRotatedPageUris(): List<Uri> = withContext(Dispatchers.IO) {
        val pageCount = _uiState.value.pageCount
        analyticsService.trackEvent(AnalyticsEvent.ScanCompleted(pageCount = pageCount))
        _uiState.value.pages.map { page ->
            if (page.rotation == 0) {
                page.uri
            } else {
                rotateAndSaveImage(page.uri, page.rotation)
            }
        }
    }

    private fun rotateAndSaveImage(uri: Uri, rotation: Int): Uri {
        // Load bitmap
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: return uri
        val originalBitmap = BitmapFactory.decodeStream(inputStream)
        inputStream.close()

        // Rotate bitmap
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val rotatedBitmap = Bitmap.createBitmap(
            originalBitmap, 0, 0,
            originalBitmap.width, originalBitmap.height,
            matrix, true
        )

        // Save to cache
        val rotatedFile = File(context.cacheDir, "rotated_${System.currentTimeMillis()}.jpg")
        FileOutputStream(rotatedFile).use { out ->
            rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }

        // Cleanup
        if (rotatedBitmap != originalBitmap) {
            originalBitmap.recycle()
        }
        rotatedBitmap.recycle()

        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            rotatedFile
        )
    }

    fun logout() {
        viewModelScope.launch {
            authRepository.logout()
        }
    }

    /**
     * Analyze the first scanned page using SuggestionOrchestrator.
     * This provides AI-powered tag suggestions for the scanned document.
     */
    fun analyzeFirstPage() {
        val firstPage = _uiState.value.pages.firstOrNull() ?: return

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

                // Load bitmap from first page URI
                val inputStream = context.contentResolver.openInputStream(firstPage.uri)
                val bitmap = if (inputStream != null) {
                    BitmapFactory.decodeStream(inputStream).also { inputStream.close() }
                } else {
                    Log.w(TAG, "Failed to open input stream for: ${firstPage.uri}")
                    null
                }

                if (bitmap == null) {
                    Log.w(TAG, "Could not decode image for analysis")
                    _analysisState.update { AnalysisState.Error(context.getString(R.string.error_analyze_document)) }
                    return@launch
                }

                // Apply rotation if needed
                val rotatedBitmap = if (firstPage.rotation != 0) {
                    val matrix = Matrix().apply { postRotate(firstPage.rotation.toFloat()) }
                    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                } else {
                    bitmap
                }

                // Use SuggestionOrchestrator for centralized suggestion logic
                val result = suggestionOrchestrator.getSuggestions(
                    bitmap = rotatedBitmap,
                    extractedText = "",
                    documentId = null
                )

                // Cleanup bitmaps
                if (rotatedBitmap != bitmap) {
                    bitmap.recycle()
                }
                rotatedBitmap.recycle()

                when (result) {
                    is SuggestionResult.Success -> {
                        Log.d(TAG, "Suggestions retrieved: ${result.analysis.suggestedTags.size} tags from ${result.source}")

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
    }

    /**
     * Apply a suggested tag by adding it to the selected tags.
     */
    fun applySuggestedTag(tagId: Int) {
        _uiState.update { state ->
            if (!state.selectedTagIds.contains(tagId)) {
                state.copy(selectedTagIds = state.selectedTagIds + tagId)
            } else {
                state
            }
        }
    }
}
