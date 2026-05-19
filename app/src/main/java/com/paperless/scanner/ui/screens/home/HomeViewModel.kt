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
import com.paperless.scanner.data.billing.PremiumFeature
import com.paperless.scanner.data.billing.PremiumFeatureManager
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.domain.model.Tag
import com.paperless.scanner.data.repository.DocumentCountRepository
import com.paperless.scanner.data.repository.DocumentListRepository
import com.paperless.scanner.data.repository.DocumentMetadataRepository
import com.paperless.scanner.data.repository.TagRepository
import com.paperless.scanner.data.repository.TrashRepository
import com.paperless.scanner.data.repository.UploadQueueRepository
import com.paperless.scanner.util.asUiResult
import com.paperless.scanner.util.formatTimeAgo
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL
import java.util.logging.Level
import java.util.logging.Logger
import javax.inject.Inject

data class DocumentStat(
    val totalDocuments: Int = 0,
    val thisMonth: Int = 0,
    val pendingUploads: Int = 0
)

data class RecentDocument(
    val id: Int,
    val title: String,
    val timeAgo: String,
    val tagName: String?,
    val tagColor: Long?
)

data class ProcessingTask(
    val id: Int,
    val taskId: String,
    val fileName: String,
    val status: TaskStatus,
    val timeAgo: String,
    val resultMessage: String? = null,
    val documentId: Int? = null
)

enum class TaskStatus {
    PENDING,
    PROCESSING,
    SUCCESS,
    FAILURE
}

// Tag creation state
sealed class CreateTagState {
    data object Idle : CreateTagState()
    data object Creating : CreateTagState()
    data class Success(val tag: Tag) : CreateTagState()
    data class Error(val message: String) : CreateTagState()
}

/**
 * Tracks deleted document for undo functionality.
 */
data class DeletedDocumentInfo(
    val id: Int,
    val title: String
)

data class HomeUiState(
    val stats: DocumentStat = DocumentStat(),
    val recentDocuments: List<RecentDocument> = emptyList(),
    val deletedDocument: DeletedDocumentInfo? = null, // For undo snackbar
    val untaggedCount: Int = 0,
    val deletedCount: Int = 0,
    val oldestDeletedTimestamp: Long? = null, // For "Expires in X days" calculation
    val lastSyncedAt: Long? = null,            // Timestamp of last successful sync
    val isLoading: Boolean = true
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val documentListRepository: DocumentListRepository,
    private val documentCountRepository: DocumentCountRepository,
    private val trashRepository: TrashRepository,
    private val documentMetadataRepository: DocumentMetadataRepository,
    private val tagRepository: TagRepository,
    private val uploadQueueRepository: UploadQueueRepository,
    private val syncManager: com.paperless.scanner.data.sync.SyncManager,
    private val analyticsService: AnalyticsService,
    private val suggestionOrchestrator: SuggestionOrchestrator,
    private val tokenManager: TokenManager,
    private val premiumFeatureManager: PremiumFeatureManager
) : ViewModel() {

    companion object {
        private val logger = Logger.getLogger(HomeViewModel::class.java.name)
        // Debounce: Only refresh if more than 30 seconds since last refresh
        private const val REFRESH_DEBOUNCE_MS = 30_000L
    }

    // Track last refresh timestamp for debounce
    private var lastRefreshTimestamp = 0L

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /**
     * Combined pending-changes flow used internally by [observePendingUploads]
     * and [loadStats]. The screen consumes the public counterpart from
     * [ServerHealthViewModel.pendingChangesCount]; both flows are derived from
     * the same repositories so values stay aligned.
     */
    private val pendingChangesCountInternal: StateFlow<Int> = kotlinx.coroutines.flow.combine(
        uploadQueueRepository.pendingCount,
        syncManager.pendingChangesCount
    ) { uploadQueueCount, syncPendingCount ->
        uploadQueueCount + syncPendingCount
    }.stateIn(viewModelScope, kotlinx.coroutines.flow.SharingStarted.WhileSubscribed(5000), 0)

    // Server URL for constructing thumbnail URLs
    val serverUrl: StateFlow<String> = tokenManager.serverUrl
        .map { it ?: "" }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = ""
        )

    // Whether to show document thumbnails (user preference)
    val showThumbnails: StateFlow<Boolean> = tokenManager.showThumbnails
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = true
        )

    // Thread-safe tag cache. Written from observeTagsReactively, read by
    // observeRecentDocumentsReactively (via combine) and on-demand from
    // tag-suggestion code paths via .value. Atomic StateFlow setter replaces
    // the previous unsynchronized `var Map`.
    private val _tagMap = MutableStateFlow<Map<Int, Tag>>(emptyMap())

    private val _errorState = MutableStateFlow<HomeError?>(null)
    val errorState: StateFlow<HomeError?> = _errorState.asStateFlow()

    // Tag Suggestions Sheet state
    private val _tagSuggestionsState = MutableStateFlow(TagSuggestionsState())
    val tagSuggestionsState: StateFlow<TagSuggestionsState> = _tagSuggestionsState.asStateFlow()

    private val _showTagSuggestionsSheet = MutableStateFlow(false)
    val showTagSuggestionsSheet: StateFlow<Boolean> = _showTagSuggestionsSheet.asStateFlow()

    // Available tags for the tag picker
    val availableTags: StateFlow<List<Tag>> = tagRepository.observeTags()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Check if AI is available (premium feature)
    val isAiAvailable: StateFlow<Boolean> = premiumFeatureManager.isAiEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    // Tag creation state
    private val _createTagState = MutableStateFlow<CreateTagState>(CreateTagState.Idle)
    val createTagState: StateFlow<CreateTagState> = _createTagState.asStateFlow()

    init {
        analyticsService.trackEvent(AnalyticsEvent.AppOpened)
        loadDashboardData()
        observePendingUploads()
        observeTagsReactively()
        observeRecentDocumentsReactively()
        observeUntaggedCountReactively()
        observeDeletedCountReactively()
        observeOldestDeletedTimestampReactively()
    }

    /**
     * Trigger an auto-refresh of the dashboard when [ServerHealthViewModel]
     * observes an offline -> online transition. Wired in HomeScreen.
     */
    fun onNetworkReconnected() {
        logger.log(Level.INFO, "Network reconnected - auto-refreshing data")
        loadDashboardData()
    }

    private var pollingRefreshJob: Job? = null

    /**
     * Refresh dashboard stats while [ProcessingTasksViewModel] is polling for
     * task completion. Wired via pollingTick in HomeScreen. Guarded so
     * overlapping ticks don't queue concurrent network calls that could let an
     * older response overwrite a newer one.
     */
    fun onPollingTick() {
        if (pollingRefreshJob?.isActive == true) return
        pollingRefreshJob = viewModelScope.launch {
            val stats = loadStats()
            _uiState.update { it.copy(stats = stats) }
        }
    }

    /**
     * BEST PRACTICE: Reactive Flow for tags.
     * Atomically refreshes [_tagMap] when tags are added/modified/deleted.
     */
    private fun observeTagsReactively() {
        viewModelScope.launch {
            tagRepository.observeTags()
                .asUiResult()
                .collect { result ->
                    result.onSuccess { tags ->
                        _tagMap.value = tags.associateBy { it.id }
                    }.onFailure { e ->
                        _errorState.value = HomeError.LoadFailed("tags", e)
                    }
                }
        }
    }

    /**
     * BEST PRACTICE: Reactive Flow for recent documents.
     *
     * Combines the documents flow with [_tagMap] so a tag rename / recolor
     * propagates to the recently-added cards immediately, instead of waiting
     * for the next document update. Also removes the prior race where this
     * collector could read a partial [_tagMap] mid-refresh.
     */
    private fun observeRecentDocumentsReactively() {
        viewModelScope.launch {
            combine(
                documentListRepository.observeDocuments(page = 1, pageSize = 5),
                _tagMap
            ) { documents, currentTagMap ->
                documents.map { doc ->
                    val firstTagId = doc.tags.firstOrNull()
                    val tag = firstTagId?.let { currentTagMap[it] }
                    RecentDocument(
                        id = doc.id,
                        title = doc.title,
                        timeAgo = formatTimeAgo(context, doc.added),
                        tagName = tag?.name,
                        tagColor = tag?.color?.let { parseColorToLong(it) }
                    )
                }
            }
                .asUiResult()
                .collect { result ->
                    result.onSuccess { recentDocs ->
                        _uiState.update { it.copy(recentDocuments = recentDocs, isLoading = false) }
                    }.onFailure { e ->
                        _errorState.value = HomeError.LoadFailed("recentDocuments", e)
                    }
                }
        }
    }

    /**
     * BEST PRACTICE: Reactive Flow for untagged documents count.
     * Automatically updates when documents are tagged or new documents are added.
     */
    private fun observeUntaggedCountReactively() {
        viewModelScope.launch {
            documentCountRepository.observeUntaggedDocumentsCount()
                .asUiResult()
                .collect { result ->
                    result.onSuccess { count ->
                        _uiState.update { it.copy(untaggedCount = count) }
                    }.onFailure { e ->
                        _errorState.value = HomeError.LoadFailed("untaggedCount", e)
                    }
                }
        }
    }

    /**
     * BEST PRACTICE: Reactive Flow for trash count.
     * Automatically updates UI when documents are deleted/restored.
     */
    private fun observeDeletedCountReactively() {
        viewModelScope.launch {
            trashRepository.observeTrashedDocumentsCount()
                .asUiResult()
                .collect { result ->
                    result.onSuccess { count ->
                        _uiState.update { it.copy(deletedCount = count) }
                    }.onFailure { e ->
                        _errorState.value = HomeError.LoadFailed("deletedCount", e)
                    }
                }
        }
    }

    /**
     * BEST PRACTICE: Reactive Flow for oldest deleted document timestamp.
     * Automatically updates UI for "Expires in X days" countdown on TrashCard.
     */
    private fun observeOldestDeletedTimestampReactively() {
        viewModelScope.launch {
            trashRepository.observeOldestDeletedTimestamp()
                .asUiResult()
                .collect { result ->
                    result.onSuccess { timestamp ->
                        _uiState.update { it.copy(oldestDeletedTimestamp = timestamp) }
                    }.onFailure { e ->
                        _errorState.value = HomeError.LoadFailed("deletedTimestamp", e)
                    }
                }
        }
    }

    fun loadDashboardData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }

            val stats = loadStats()

            // Refresh untagged count from API
            var untaggedCount = 0
            documentCountRepository.getUntaggedCount().onSuccess { count ->
                untaggedCount = count
            }

            // Refresh recent documents from API (updates Room cache, triggers reactive Flow)
            documentListRepository.getDocuments(
                page = 1,
                pageSize = 10,
                ordering = "-added",
                forceRefresh = true
            )

            // Sync trash documents (page 1 for quick init, full sync on pull-to-refresh)
            trashRepository.getTrashDocuments(page = 1, pageSize = 100)

            // Update lastSyncedAt on initial load too
            lastRefreshTimestamp = System.currentTimeMillis()

            _uiState.update { currentState ->
                currentState.copy(
                    stats = stats,
                    untaggedCount = untaggedCount,
                    lastSyncedAt = lastRefreshTimestamp,
                    isLoading = false
                )
            }
        }
    }

    /**
     * BEST PRACTICE: Refresh both stats and tasks.
     * Use this for:
     * - Pull-to-refresh user actions (forceRefresh = true)
     * - After upload/delete operations (forceRefresh = true)
     * - Network reconnect scenarios (forceRefresh = true)
     *
     * For ON_RESUME events, use refreshDashboardIfNeeded() to apply debounce.
     */
    fun refreshDashboard() {
        viewModelScope.launch {
            // Refresh stats from server (forceRefresh = true)
            val stats = loadStats(forceRefresh = true)

            // Update lastSyncedAt timestamp
            val now = System.currentTimeMillis()
            lastRefreshTimestamp = now

            _uiState.update { it.copy(stats = stats, lastSyncedAt = now) }

            // Refresh untagged count from API
            documentCountRepository.getUntaggedCount().onSuccess { count ->
                _uiState.update { it.copy(untaggedCount = count) }
            }

            // Refresh recent documents from API (updates Room cache, triggers reactive Flow)
            documentListRepository.getDocuments(
                page = 1,
                pageSize = 10,
                ordering = "-added",
                forceRefresh = true
            )

            // Full trash sync: fetch all pages and cleanup orphans
            syncTrashDocuments()
        }
    }

    /**
     * Full trash sync: fetches all pages from server and cleans up orphaned local docs.
     */
    private suspend fun syncTrashDocuments() {
        var page = 1
        var hasMore = true
        val serverTrashIds = mutableSetOf<Int>()

        while (hasMore) {
            trashRepository.getTrashDocuments(page = page, pageSize = 100)
                .onSuccess { response ->
                    serverTrashIds.addAll(response.results.map { it.id })
                    hasMore = response.next != null && response.results.isNotEmpty()
                    page++
                }
                .onFailure {
                    hasMore = false
                }
        }

        // Clean up local trash docs that no longer exist on server
        if (serverTrashIds.isNotEmpty()) {
            trashRepository.cleanupOrphanedTrashDocs(serverTrashIds)
        }
    }

    /**
     * BEST PRACTICE: Debounced refresh for ON_RESUME events.
     * Only refreshes if more than 30 seconds since last refresh.
     * Reduces server load and battery usage for quick app switches.
     */
    fun refreshDashboardIfNeeded() {
        val now = System.currentTimeMillis()
        if (now - lastRefreshTimestamp > REFRESH_DEBOUNCE_MS) {
            refreshDashboard()
        } else {
            logger.log(Level.FINE, "Skipping refresh - last refresh was ${(now - lastRefreshTimestamp) / 1000}s ago")
        }
    }

    private suspend fun loadStats(forceRefresh: Boolean = true): DocumentStat {
        val pendingCount = pendingChangesCountInternal.value // Use live flow value
        var totalDocuments = 0
        var thisMonth = 0

        // BEST PRACTICE: Always fetch stats from server (forceRefresh = true by default)
        // to ensure accurate counts in multi-client scenarios (web + mobile)
        documentCountRepository.getDocumentCount(forceRefresh = forceRefresh).onSuccess { count ->
            totalDocuments = count
        }

        // Get this month's document count
        documentListRepository.getDocuments(
            page = 1,
            pageSize = 1,
            ordering = "-added",
            forceRefresh = forceRefresh
        ).onSuccess { response ->
            // Count documents added this month from response.count
            // Note: A more accurate approach would use date filtering if API supports it
            thisMonth = minOf(response.count, 30) // Approximation
        }

        return DocumentStat(
            totalDocuments = totalDocuments,
            thisMonth = thisMonth,
            pendingUploads = pendingCount
        )
    }


    private fun parseColorToLong(colorString: String): Long? {
        return try {
            if (colorString.startsWith("#")) {
                java.lang.Long.parseLong(colorString.removePrefix("#"), 16) or 0xFF000000
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    fun resetState() {
        _uiState.update { HomeUiState() }
    }

    fun clearHomeError() {
        _errorState.value = null
    }

    fun deleteRecentDocument(documentId: Int, documentTitle: String) {
        viewModelScope.launch {
            // Set deleted document info for undo snackbar
            _uiState.update {
                it.copy(deletedDocument = DeletedDocumentInfo(documentId, documentTitle))
            }

            try {
                trashRepository.deleteDocument(documentId).onSuccess {
                    // Recent documents list updates automatically via reactive Flow
                    analyticsService.trackEvent(AnalyticsEvent.DocumentDeleted)
                }.onFailure { error ->
                    _uiState.update { it.copy(deletedDocument = null) }
                    _errorState.value = HomeError.ActionFailed(
                        "deleteDocument",
                        error
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update { it.copy(deletedDocument = null) }
                _errorState.value = HomeError.ActionFailed(
                    "deleteDocument",
                    e
                )
            }
        }
    }

    /**
     * Undo the most recent document deletion.
     * Restores the document by clearing its soft-delete flag.
     */
    fun undoDelete() {
        val deletedDoc = _uiState.value.deletedDocument ?: return

        viewModelScope.launch {
            // Clear deleted document state immediately for responsive UX
            _uiState.update { it.copy(deletedDocument = null) }

            // Restore document via repository
            try {
                trashRepository.restoreDocument(deletedDoc.id).onFailure {
                    _errorState.value = HomeError.ActionFailed(
                        "restoreDocument",
                        it
                    )
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _errorState.value = HomeError.ActionFailed(
                    "restoreDocument",
                    e
                )
            }
        }
    }

    /**
     * Clear the deleted document info (when undo snackbar is dismissed).
     */
    fun clearDeletedDocument() {
        _uiState.update { it.copy(deletedDocument = null) }
    }

    private fun observePendingUploads() {
        viewModelScope.launch {
            pendingChangesCountInternal
                .asUiResult()
                .collect { result ->
                    result.onSuccess { count ->
                        _uiState.update { currentState ->
                            currentState.copy(stats = currentState.stats.copy(pendingUploads = count))
                        }
                    }.onFailure { e ->
                        _errorState.value = HomeError.LoadFailed("pendingUploads", e)
                    }
                }
        }
    }

    // onCleared() — no overrides needed after Phase 2 (polling lifecycle moved
    // to ProcessingTasksViewModel.onCleared()).

    // ==================== TAG SUGGESTIONS SHEET ====================

    fun openTagSuggestionsSheet() {
        _showTagSuggestionsSheet.value = true
        loadUntaggedDocuments()
    }

    /**
     * Load untagged documents for the SmartTaggingScreen.
     * This is called when navigating to the full-screen tagging experience.
     */
    fun loadUntaggedDocumentsForScreen() {
        loadUntaggedDocuments()
    }

    fun closeTagSuggestionsSheet() {
        _showTagSuggestionsSheet.value = false
        _tagSuggestionsState.value = TagSuggestionsState()
    }

    private fun loadUntaggedDocuments() {
        viewModelScope.launch {
            _tagSuggestionsState.update { it.copy(isLoading = true) }

            // Get ALL untagged documents from local cache (no limit)
            documentListRepository.getUntaggedDocuments().onSuccess { documents ->
                val serverUrl = tokenManager.serverUrl.first() ?: ""
                val authToken = tokenManager.token.first() ?: ""

                // Map to UI model
                val untaggedDocs = documents.map { doc ->
                    UntaggedDocument(
                        id = doc.id,
                        title = doc.title,
                        thumbnailUrl = if (serverUrl.isNotEmpty() && authToken.isNotEmpty()) {
                            "$serverUrl/api/documents/${doc.id}/thumb/"
                        } else null,
                        analysisState = UntaggedDocAnalysisState.Idle
                    )
                }

                _tagSuggestionsState.update {
                    it.copy(
                        documents = untaggedDocs,
                        isLoading = false
                    )
                }

                // Pre-load thumbnails for all documents in background
                if (serverUrl.isNotEmpty() && authToken.isNotEmpty()) {
                    untaggedDocs.forEach { doc ->
                        loadThumbnailForDocument(doc.id, serverUrl, authToken)
                    }
                }
            }.onFailure { error ->
                logger.log(Level.WARNING, "Failed to load untagged documents: ${error.message}")
                _tagSuggestionsState.update { it.copy(isLoading = false) }
                _errorState.value = HomeError.LoadFailed("untaggedDocuments", error)
            }
        }
    }

    private fun loadThumbnailForDocument(documentId: Int, serverUrl: String, authToken: String) {
        viewModelScope.launch {
            val thumbnailUrl = "$serverUrl/api/documents/$documentId/thumb/"
            val bitmap = withContext(Dispatchers.IO) {
                try {
                    val connection = URL(thumbnailUrl).openConnection()
                    connection.setRequestProperty("Authorization", "Token $authToken")
                    connection.connectTimeout = com.paperless.scanner.util.NetworkConfig.THUMBNAIL_TIMEOUT_MS
                    connection.readTimeout = com.paperless.scanner.util.NetworkConfig.THUMBNAIL_TIMEOUT_MS
                    connection.getInputStream().use { inputStream ->
                        BitmapFactory.decodeStream(inputStream)
                    }
                } catch (e: Exception) {
                    logger.log(Level.WARNING, "Failed to load thumbnail for doc $documentId: ${e.message}")
                    null
                }
            }

            if (bitmap != null) {
                _tagSuggestionsState.update { state ->
                    state.copy(
                        documents = state.documents.map { doc ->
                            if (doc.id == documentId) {
                                doc.copy(thumbnailBitmap = bitmap)
                            } else doc
                        }
                    )
                }
            }
        }
    }

    fun analyzeDocument(documentId: Int) {
        viewModelScope.launch {
            // Update state to loading
            updateDocumentState(documentId, UntaggedDocAnalysisState.LoadingThumbnail)

            try {
                val serverUrl = tokenManager.serverUrl.first() ?: ""
                val authToken = tokenManager.token.first() ?: ""

                if (serverUrl.isEmpty() || authToken.isEmpty()) {
                    updateDocumentState(documentId, UntaggedDocAnalysisState.Error(
                        context.getString(R.string.error_not_authenticated)
                    ))
                    return@launch
                }

                // Download thumbnail for AI analysis
                val thumbnailUrl = "$serverUrl/api/documents/$documentId/thumb/"
                val bitmap = withContext(Dispatchers.IO) {
                    try {
                        val connection = URL(thumbnailUrl).openConnection()
                        connection.setRequestProperty("Authorization", "Token $authToken")
                        connection.connectTimeout = 10000
                        connection.readTimeout = 10000
                        connection.getInputStream().use { inputStream ->
                            BitmapFactory.decodeStream(inputStream)
                        }
                    } catch (e: Exception) {
                        logger.log(Level.WARNING, "Failed to download thumbnail: ${e.message}")
                        null
                    }
                }

                if (bitmap == null) {
                    updateDocumentState(documentId, UntaggedDocAnalysisState.Error(
                        context.getString(R.string.tag_suggestions_error_thumbnail)
                    ))
                    return@launch
                }

                // Update document with bitmap and change state to analyzing
                _tagSuggestionsState.update { state ->
                    state.copy(
                        documents = state.documents.map { doc ->
                            if (doc.id == documentId) {
                                doc.copy(
                                    thumbnailBitmap = bitmap,
                                    analysisState = UntaggedDocAnalysisState.Analyzing
                                )
                            } else doc
                        }
                    )
                }

                // Run AI analysis using the orchestrator
                val result = suggestionOrchestrator.getSuggestions(
                    bitmap = bitmap,
                    documentId = documentId
                )

                when (result) {
                    is SuggestionResult.Success -> {
                        // Separate suggested tags into existing and new
                        val existingTags = _tagMap.value.values.toList()

                        // Find tags that exist in the system and pre-select them
                        val preSelectedIds = result.analysis.suggestedTags
                            .mapNotNull { suggestion ->
                                suggestion.tagId ?: existingTags.find {
                                    it.name.equals(suggestion.tagName, ignoreCase = true)
                                }?.id
                            }
                            .toSet()

                        // Find tags that DON'T exist in the system (new tag suggestions)
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
                                            suggestedNewTags = newTagSuggestions
                                        )
                                    } else doc
                                }
                            )
                        }

                        analyticsService.trackEvent(AnalyticsEvent.AiFeatureUsed(
                            featureType = "tag_suggestions_home",
                            inputTokens = 0, // Not tracked at this level
                            outputTokens = 0,
                            estimatedCostUsd = 0.0,
                            subscriptionType = "unknown",
                            success = true
                        ))
                    }
                    is SuggestionResult.Error -> {
                        updateDocumentState(documentId, UntaggedDocAnalysisState.Error(result.message))
                    }
                    is SuggestionResult.Loading -> {
                        // Already in loading state, no action needed
                    }
                    is SuggestionResult.WiFiRequired -> {
                        // WiFi-only restriction does not apply to HomeScreen analysis
                        // (documents are already uploaded, only analyzing existing thumbnails)
                        // This case should not occur here, but handle gracefully
                        logger.log(Level.WARNING, "WiFiRequired in HomeViewModel - should not happen")
                        updateDocumentState(documentId, UntaggedDocAnalysisState.Idle)
                    }
                }
            } catch (e: Exception) {
                logger.log(Level.WARNING, "Error analyzing document $documentId: ${e.message}")
                updateDocumentState(documentId, UntaggedDocAnalysisState.Error(
                    e.message ?: context.getString(R.string.error_unknown)
                ))
            }
        }
    }

    private fun updateDocumentState(documentId: Int, state: UntaggedDocAnalysisState) {
        _tagSuggestionsState.update { currentState ->
            currentState.copy(
                documents = currentState.documents.map { doc ->
                    if (doc.id == documentId) {
                        doc.copy(analysisState = state)
                    } else doc
                }
            )
        }
    }

    fun applyTagsToDocument(documentId: Int, tagIds: List<Int>) {
        viewModelScope.launch {
            try {
                // Update document via repository
                documentMetadataRepository.updateDocument(documentId, tags = tagIds)
                    .onSuccess {
                        logger.log(Level.INFO, "Applied tags $tagIds to document $documentId")

                        // Mark as tagged and increment counter
                        _tagSuggestionsState.update { state ->
                            state.copy(
                                documents = state.documents.map { doc ->
                                    if (doc.id == documentId) {
                                        doc.copy(isTagged = true)
                                    } else doc
                                },
                                taggedCount = state.taggedCount + 1
                            )
                        }

                        analyticsService.trackEvent(AnalyticsEvent.AiSuggestionAccepted(
                            featureType = "tag_suggestions_home",
                            suggestionCount = tagIds.size
                        ))
                    }
                    .onFailure { error ->
                        logger.log(Level.WARNING, "Failed to apply tags: ${error.message}")
                        updateDocumentState(documentId, UntaggedDocAnalysisState.Error(
                            error.message ?: context.getString(R.string.error_unknown)
                        ))
                    }
            } catch (e: Exception) {
                logger.log(Level.WARNING, "Error applying tags: ${e.message}")
            }
        }
    }

    fun skipDocument(documentId: Int) {
        _tagSuggestionsState.update { state ->
            state.copy(
                documents = state.documents.map { doc ->
                    if (doc.id == documentId) {
                        doc.copy(isSkipped = true)
                    } else doc
                }
            )
        }
    }

    // Tag Picker methods
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
                }
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
                    // BEST PRACTICE: Tag list updates automatically via observeTagsReactively()
                    _createTagState.update { CreateTagState.Success(newTag) }
                }
                .onFailure { e ->
                    logger.log(Level.WARNING, "Failed to create tag: ${e.message}")
                    // Handle duplicate tag error
                    if (e.message?.contains("unique constraint") == true ||
                        e.message?.contains("already exists") == true) {
                        // Try to find existing tag
                        val existingTag = tagRepository.observeTags().first()
                            .find { it.name.equals(name, ignoreCase = true) }
                        if (existingTag != null) {
                            _createTagState.update { CreateTagState.Success(existingTag) }
                        } else {
                            _createTagState.update { CreateTagState.Error(
                                context.getString(R.string.error_create_tag)
                            ) }
                        }
                    } else {
                        _createTagState.update { CreateTagState.Error(
                            e.message ?: context.getString(R.string.error_create_tag)
                        ) }
                    }
                }
        }
    }

    fun resetCreateTagState() {
        _createTagState.update { CreateTagState.Idle }
    }

    /**
     * Creates a suggested new tag and automatically selects it for the specified document.
     * Also removes it from the suggestedNewTags list.
     */
    fun createSuggestedTag(documentId: Int, tagName: String) {
        viewModelScope.launch {
            _createTagState.update { CreateTagState.Creating }

            tagRepository.createTag(name = tagName, color = null)
                .onSuccess { newTag ->
                    logger.log(Level.INFO, "Suggested tag created: ${newTag.name}")
                    analyticsService.trackEvent(AnalyticsEvent.TagCreated)

                    // Auto-select the newly created tag for this document
                    // and remove it from suggestedNewTags
                    _tagSuggestionsState.update { state ->
                        state.copy(
                            documents = state.documents.map { doc ->
                                if (doc.id == documentId) {
                                    doc.copy(
                                        selectedTagIds = doc.selectedTagIds + newTag.id,
                                        suggestedNewTags = doc.suggestedNewTags.filter {
                                            !it.tagName.equals(tagName, ignoreCase = true)
                                        }
                                    )
                                } else doc
                            }
                        )
                    }

                    _createTagState.update { CreateTagState.Success(newTag) }
                }
                .onFailure { e ->
                    logger.log(Level.WARNING, "Failed to create suggested tag: ${e.message}")
                    // Handle duplicate tag error - tag might have been created meanwhile
                    if (e.message?.contains("unique constraint") == true ||
                        e.message?.contains("already exists") == true) {
                        val existingTag = tagRepository.observeTags().first()
                            .find { it.name.equals(tagName, ignoreCase = true) }
                        if (existingTag != null) {
                            // Auto-select the existing tag
                            _tagSuggestionsState.update { state ->
                                state.copy(
                                    documents = state.documents.map { doc ->
                                        if (doc.id == documentId) {
                                            doc.copy(
                                                selectedTagIds = doc.selectedTagIds + existingTag.id,
                                                suggestedNewTags = doc.suggestedNewTags.filter {
                                                    !it.tagName.equals(tagName, ignoreCase = true)
                                                }
                                            )
                                        } else doc
                                    }
                                )
                            }
                            _createTagState.update { CreateTagState.Success(existingTag) }
                        } else {
                            _createTagState.update { CreateTagState.Error(
                                context.getString(R.string.error_create_tag)
                            ) }
                        }
                    } else {
                        _createTagState.update { CreateTagState.Error(
                            e.message ?: context.getString(R.string.error_create_tag)
                        ) }
                    }
                }
        }
    }
}
