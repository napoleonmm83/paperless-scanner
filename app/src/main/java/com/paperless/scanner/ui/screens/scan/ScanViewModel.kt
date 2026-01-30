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
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.api.userMessage
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
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject

/**
 * Source type for scanned pages - determines origin for UI badges
 */
enum class PageSource {
    SCANNER,  // MLKit Document Scanner
    GALLERY,  // Photo picker (PickMultipleVisualMedia)
    FILES     // File picker (OpenMultipleDocuments)
}

/**
 * Custom metadata for individual pages in batch uploads.
 * Allows different metadata per page when uploading as separate documents.
 */
data class PageMetadata(
    val title: String? = null,
    val tags: List<Int>? = null,  // Tag IDs
    val correspondent: Int? = null,  // Correspondent ID
    val documentType: Int? = null  // Document Type ID
)

data class ScannedPage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val uri: Uri,
    val pageNumber: Int,
    val rotation: Int = 0,  // 0, 90, 180, 270
    val source: PageSource = PageSource.SCANNER,  // Default to scanner for backward compatibility
    val customMetadata: PageMetadata? = null  // Custom metadata for batch uploads
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
    private val savedStateHandle: androidx.lifecycle.SavedStateHandle,
    private val authRepository: AuthRepository,
    private val analyticsService: AnalyticsService,
    private val tagRepository: TagRepository,
    private val documentTypeRepository: DocumentTypeRepository,
    private val correspondentRepository: CorrespondentRepository,
    private val suggestionOrchestrator: SuggestionOrchestrator,
    private val aiUsageRepository: AiUsageRepository,
    private val premiumFeatureManager: PremiumFeatureManager,
    private val networkMonitor: com.paperless.scanner.data.network.NetworkMonitor,
    private val tokenManager: com.paperless.scanner.data.datastore.TokenManager,
    val appLockManager: com.paperless.scanner.util.AppLockManager,
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
    private val gson: Gson
) : ViewModel() {

    companion object {
        private const val TAG = "ScanViewModel"
        private const val KEY_PAGE_URIS = "pageUris"
        private const val KEY_PAGE_IDS = "pageIds"
        private const val KEY_PAGE_ROTATIONS = "pageRotations"
        private const val KEY_PAGE_METADATA = "pageMetadata"
        private const val KEY_UPLOAD_AS_SINGLE = "uploadAsSingleDocument"
    }

    // Reactive page URIs from SavedStateHandle (survives process death)
    private val pageUrisStateFlow: StateFlow<List<Uri>> =
        savedStateHandle.getStateFlow<String?>(KEY_PAGE_URIS, null)
            .map { urisString ->
                if (urisString.isNullOrEmpty()) {
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
            }
            .stateIn(
                scope = viewModelScope,
                started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    // Reactive page IDs from SavedStateHandle
    private val pageIdsStateFlow: StateFlow<List<String>> =
        savedStateHandle.getStateFlow<String?>(KEY_PAGE_IDS, null)
            .map { idsString ->
                if (idsString.isNullOrEmpty()) {
                    emptyList()
                } else {
                    idsString.split("|")
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
                initialValue = emptyList()
            )

    // Reactive rotations map from SavedStateHandle
    private val pageRotationsStateFlow: StateFlow<Map<String, Int>> =
        savedStateHandle.getStateFlow<String?>(KEY_PAGE_ROTATIONS, null)
            .map { rotationsString ->
                if (rotationsString.isNullOrEmpty()) {
                    emptyMap()
                } else {
                    rotationsString.split("|").mapNotNull { pair ->
                        val parts = pair.split(":")
                        if (parts.size == 2) {
                            val rotation = parts[1].toIntOrNull()
                            if (rotation != null) {
                                parts[0] to rotation
                            } else null
                        } else null
                    }.toMap()
                }
            }
            .stateIn(
                scope = viewModelScope,
                started = kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000),
                initialValue = emptyMap()
            )

    // Upload mode: true = Single PDF, false = Individual Documents
    val uploadAsSingleDocument: StateFlow<Boolean> =
        savedStateHandle.getStateFlow(KEY_UPLOAD_AS_SINGLE, false)

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

    // WiFi-Only State
    private val _wifiRequired = MutableStateFlow(false)
    val wifiRequired: StateFlow<Boolean> = _wifiRequired.asStateFlow()

    private val _wifiOnlyOverride = MutableStateFlow(false)
    val wifiOnlyOverride: StateFlow<Boolean> = _wifiOnlyOverride.asStateFlow()

    // Observe WiFi status for reactive UI
    val isWifiConnected: StateFlow<Boolean> = networkMonitor.isWifiConnected

    /**
     * Whether server uses Cloudflare (detected automatically via cf-ray header).
     * Used to show timeout warnings for large uploads (Cloudflare has 100s timeout).
     */
    val usesCloudflare: StateFlow<Boolean> = tokenManager.serverUsesCloudflare
        .stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.Eagerly, false)

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
        // CRITICAL: Restore pages FIRST (synchronously) before ANY other operations
        // This prevents race conditions where scanner callbacks trigger before restoration
        restorePagesFromSavedState()

        loadTags()
        observeDocumentTypes()
        observeCorrespondents()
        observeUsageLimits()
    }

    /**
     * Restore pages from SavedStateHandle after process death/configuration change.
     * Runs ONCE synchronously in init-block to prevent race conditions with scanner callbacks.
     */
    private fun restorePagesFromSavedState() {
        // Read from SavedStateHandle directly (synchronous, no Flow overhead)
        val urisString = savedStateHandle.get<String>(KEY_PAGE_URIS)
        val idsString = savedStateHandle.get<String>(KEY_PAGE_IDS)
        val rotationsString = savedStateHandle.get<String>(KEY_PAGE_ROTATIONS)
        val metadataString = savedStateHandle.get<String>(KEY_PAGE_METADATA)

        // Only restore if we have valid data
        if (!urisString.isNullOrEmpty() && !idsString.isNullOrEmpty()) {
            try {
                // Parse URIs
                val uris = urisString.split("|").mapNotNull { uriString ->
                    try {
                        Uri.parse(uriString)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse URI: $uriString", e)
                        null
                    }
                }

                // Parse IDs
                val ids = idsString.split("|")

                // Parse rotations (optional)
                val rotations = if (!rotationsString.isNullOrEmpty()) {
                    rotationsString.split("|").mapNotNull { pair ->
                        val parts = pair.split(":")
                        if (parts.size == 2) {
                            val rotation = parts[1].toIntOrNull()
                            if (rotation != null) {
                                parts[0] to rotation
                            } else null
                        } else null
                    }.toMap()
                } else {
                    emptyMap()
                }

                // Parse custom metadata (optional)
                val metadataMap = if (!metadataString.isNullOrEmpty()) {
                    metadataString.split("|").mapNotNull { pair ->
                        try {
                            // Format: "id:json"
                            val colonIndex = pair.indexOf(':')
                            if (colonIndex > 0) {
                                val id = pair.substring(0, colonIndex)
                                val json = pair.substring(colonIndex + 1)
                                val metadata = gson.fromJson(json, PageMetadata::class.java)
                                id to metadata
                            } else null
                        } catch (e: Exception) {
                            Log.e(TAG, "Failed to parse metadata: $pair", e)
                            null
                        }
                    }.toMap()
                } else {
                    emptyMap()
                }

                // Validate data integrity
                if (uris.isNotEmpty() && uris.size == ids.size) {
                    val restoredPages = uris.mapIndexed { index, uri ->
                        val id = ids[index]
                        ScannedPage(
                            id = id,
                            uri = uri,
                            pageNumber = index + 1,
                            rotation = rotations[id] ?: 0,
                            customMetadata = metadataMap[id]
                        )
                    }

                    // Update UI state ONCE
                    _uiState.update { it.copy(pages = restoredPages) }
                    Log.d(TAG, "✅ Restored ${restoredPages.size} pages from SavedStateHandle (one-time init)")
                } else {
                    Log.w(TAG, "⚠️ Data mismatch: ${uris.size} URIs vs ${ids.size} IDs - skipping restoration")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Failed to restore pages from SavedStateHandle", e)
            }
        }
    }

    /**
     * Sync current pages to SavedStateHandle (for process death survival).
     */
    private fun syncPagesToSavedState(pages: List<ScannedPage>) {
        if (pages.isEmpty()) {
            savedStateHandle[KEY_PAGE_URIS] = null
            savedStateHandle[KEY_PAGE_IDS] = null
            savedStateHandle[KEY_PAGE_ROTATIONS] = null
            savedStateHandle[KEY_PAGE_METADATA] = null
        } else {
            // Serialize URIs as pipe-separated string
            val urisString = pages.joinToString("|") { it.uri.toString() }
            savedStateHandle[KEY_PAGE_URIS] = urisString

            // Serialize IDs as pipe-separated string
            val idsString = pages.joinToString("|") { it.id }
            savedStateHandle[KEY_PAGE_IDS] = idsString

            // Serialize rotations as "id:rotation|..." (only non-zero rotations)
            val rotationsString = pages
                .filter { it.rotation != 0 }
                .joinToString("|") { "${it.id}:${it.rotation}" }
                .ifEmpty { null }
            savedStateHandle[KEY_PAGE_ROTATIONS] = rotationsString

            // Serialize custom metadata as "id:json|..." (only pages with metadata)
            val metadataString = pages
                .filter { it.customMetadata != null }
                .joinToString("|") { page ->
                    val json = gson.toJson(page.customMetadata)
                    "${page.id}:$json"
                }
                .ifEmpty { null }
            savedStateHandle[KEY_PAGE_METADATA] = metadataString

            Log.d(TAG, "Synced ${pages.size} pages to SavedStateHandle")
        }
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

    fun getSelectedTagIds(): List<Int> = _uiState.value.selectedTagIds

    fun clearSelectedTags() {
        _uiState.update { it.copy(selectedTagIds = emptyList()) }
    }

    /**
     * Manually set processing state (used during file copying phase).
     */
    fun setProcessing(isProcessing: Boolean) {
        _uiState.update { it.copy(isProcessing = isProcessing) }
    }

    /**
     * Add pages with processing state for better UX when importing many files.
     * Shows loading indicator while pages are being added.
     */
    fun addPages(uris: List<Uri>, source: PageSource = PageSource.SCANNER) {
        viewModelScope.launch {
            // Show processing state for large batches (>5 files)
            if (uris.size > 5) {
                _uiState.update { it.copy(isProcessing = true) }
                // Small delay to ensure UI updates before processing
                kotlinx.coroutines.delay(100)
            }

            try {
                withContext(Dispatchers.Default) {
                    val startIndex = _uiState.value.pageCount
                    val newPages = uris.mapIndexed { index, uri ->
                        ScannedPage(
                            uri = uri,
                            pageNumber = startIndex + index + 1,
                            source = source
                        )
                    }

                    withContext(Dispatchers.Main) {
                        _uiState.update { state ->
                            val newTotalPages = state.pageCount + uris.size
                            analyticsService.trackEvent(AnalyticsEvent.ScanPageAdded(totalPages = newTotalPages))
                            val updatedPages = state.pages + newPages
                            syncPagesToSavedState(updatedPages)
                            state.copy(pages = updatedPages)
                        }
                    }
                }
            } finally {
                // Always reset processing state
                _uiState.update { it.copy(isProcessing = false) }
            }
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
            syncPagesToSavedState(renumberedPages)
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
            syncPagesToSavedState(renumberedPages)
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
            syncPagesToSavedState(renumberedPages)
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
            syncPagesToSavedState(updatedPages)
            state.copy(pages = updatedPages)
        }
    }

    /**
     * Crop a page and replace its URI with the cropped version.
     * Unlike rotation (which is deferred), cropping is applied immediately.
     *
     * @param pageId Page ID to crop
     * @param cropRect Crop rectangle (normalized 0-1)
     */
    fun cropPage(pageId: String, cropRect: com.paperless.scanner.ui.screens.scan.CropRect) {
        viewModelScope.launch(Dispatchers.IO) {
            // Find the page to crop
            val page = _uiState.value.pages.find { it.id == pageId } ?: return@launch

            // Perform heavy I/O operation OUTSIDE the state update block
            val croppedUri = cropAndSaveImage(page.uri, cropRect) ?: page.uri

            // Only update state with the result (fast operation)
            _uiState.update { state ->
                val updatedPages = state.pages.map { p ->
                    if (p.id == pageId) p.copy(uri = croppedUri) else p
                }
                state.copy(pages = updatedPages)
            }

            // Sync to SavedStateHandle on Main thread
            withContext(Dispatchers.Main) {
                syncPagesToSavedState(_uiState.value.pages)
            }
        }
    }

    private fun cropAndSaveImage(uri: Uri, cropRect: com.paperless.scanner.ui.screens.scan.CropRect): Uri? {
        return try {
            // Load bitmap
            val inputStream = context.contentResolver.openInputStream(uri)
                ?: return null
            val originalBitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()

            // Check if bitmap loaded successfully
            if (originalBitmap == null) {
                return null
            }

            // Calculate crop rectangle in pixel coordinates
            val left = (originalBitmap.width * cropRect.left).toInt().coerceIn(0, originalBitmap.width)
            val top = (originalBitmap.height * cropRect.top).toInt().coerceIn(0, originalBitmap.height)
            val width = (originalBitmap.width * (cropRect.right - cropRect.left)).toInt()
                .coerceIn(1, originalBitmap.width - left)
            val height = (originalBitmap.height * (cropRect.bottom - cropRect.top)).toInt()
                .coerceIn(1, originalBitmap.height - top)

            // Crop bitmap
            val croppedBitmap = Bitmap.createBitmap(
                originalBitmap,
                left,
                top,
                width,
                height
            )

            // Save to cache
            val croppedFile = File(context.cacheDir, "cropped_${System.currentTimeMillis()}.jpg")
            FileOutputStream(croppedFile).use { out ->
                croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }

            // Cleanup
            if (croppedBitmap != originalBitmap) {
                originalBitmap.recycle()
            }
            croppedBitmap.recycle()

            FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                croppedFile
            )
        } catch (e: Exception) {
            Log.e("ScanViewModel", "Failed to crop image", e)
            null
        }
    }

    fun clearPages() {
        _uiState.update { it.copy(pages = emptyList()) }
        syncPagesToSavedState(emptyList())
    }

    fun setUploadAsSingleDocument(value: Boolean) {
        savedStateHandle[KEY_UPLOAD_AS_SINGLE] = value
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
            "${context.packageName}.fileprovider",
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
                    documentId = null,
                    overrideWifiOnly = _wifiOnlyOverride.value
                )

                // Cleanup bitmaps
                if (rotatedBitmap != bitmap) {
                    bitmap.recycle()
                }
                rotatedBitmap.recycle()

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
        analyzeFirstPage()
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
