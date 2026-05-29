package com.paperless.scanner.ui.screens.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.paperless.scanner.R
import com.paperless.scanner.ui.screens.settings.PremiumUpgradeSheet
import com.paperless.scanner.ui.screens.upload.CreateTagDialog
import com.paperless.scanner.ui.components.documentlist.DocumentListSnackbar
import com.paperless.scanner.ui.components.documentlist.animateItemRemoval
import com.paperless.scanner.ui.components.documentlist.rememberHybridSwipePattern
import com.paperless.scanner.ui.screens.home.components.CompactStatsRow
import com.paperless.scanner.ui.screens.home.components.EmptyDocumentsCard
import com.paperless.scanner.ui.screens.home.components.HeroDocumentCard
import com.paperless.scanner.ui.screens.home.components.HomeHeaderSection
import com.paperless.scanner.ui.screens.home.components.ProcessingTasksSection
import com.paperless.scanner.ui.screens.home.components.RecentDocumentItem
import com.paperless.scanner.ui.screens.home.components.RecentDocumentsHeader

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToScan: () -> Unit,
    onNavigateToDocuments: () -> Unit,
    onDocumentClick: (Int) -> Unit = {},
    onNavigateToPendingSync: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToSmartTagging: () -> Unit = {},
    onNavigateToTrash: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel(),
    serverHealthViewModel: ServerHealthViewModel = hiltViewModel(),
    processingTasksViewModel: ProcessingTasksViewModel = hiltViewModel(),
    tagSuggestionsViewModel: TagSuggestionsViewModel = hiltViewModel(),
    recentDocumentsViewModel: RecentDocumentsViewModel = hiltViewModel(),
    trashOverviewViewModel: TrashOverviewViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val serverHealthUiState by serverHealthViewModel.uiState.collectAsState()
    val processingTasksUiState by processingTasksViewModel.uiState.collectAsState()
    val recentDocumentsUiState by recentDocumentsViewModel.uiState.collectAsState()
    val trashOverviewUiState by trashOverviewViewModel.uiState.collectAsState()
    val isOnline by serverHealthViewModel.isOnline.collectAsState()
    val isServerReachable by serverHealthViewModel.isServerReachable.collectAsState()
    val pendingChanges by serverHealthViewModel.pendingChangesCount.collectAsState()
    val serverUrl by recentDocumentsViewModel.serverUrl.collectAsState()
    val showThumbnails by recentDocumentsViewModel.showThumbnails.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Auto-refresh dashboard on offline -> online transition. The three
    // ViewModels share no direct reference; the screen layer is the wiring
    // point. RecentDocumentsViewModel re-syncs in parallel so newly-arrived
    // documents from the offline gap show up without a manual pull-to-refresh.
    LaunchedEffect(serverHealthViewModel) {
        serverHealthViewModel.onlineTransition.collect {
            viewModel.onNetworkReconnected()
            recentDocumentsViewModel.refreshRecentDocuments()
            trashOverviewViewModel.refreshTrashOverview(fullTrashSync = true)
        }
    }

    // Refresh dashboard stats while ProcessingTasksViewModel is polling for
    // task completion. Same no-direct-ref pattern as onlineTransition above.
    LaunchedEffect(processingTasksViewModel) {
        processingTasksViewModel.pollingTick.collect {
            viewModel.onPollingTick()
        }
    }

    // Tag Suggestions Sheet state (owned by TagSuggestionsViewModel since Phase 3)
    val showTagSuggestionsSheet by tagSuggestionsViewModel.showTagSuggestionsSheet.collectAsState()
    val tagSuggestionsState by tagSuggestionsViewModel.tagSuggestionsState.collectAsState()
    val availableTags by tagSuggestionsViewModel.availableTags.collectAsState()
    val isAiAvailable by tagSuggestionsViewModel.isAiAvailable.collectAsState()
    val tagSuggestionsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Tag creation state
    val createTagState by tagSuggestionsViewModel.createTagState.collectAsState()
    var showCreateTagDialog by remember { mutableStateOf(false) }

    // Error state
    val errorState by viewModel.errorState.collectAsState()
    val errorMessage = when (errorState) {
        is HomeError.LoadFailed -> stringResource(R.string.error_load_data)
        is HomeError.ActionFailed -> stringResource(R.string.error_action_failed)
        null -> null
    }

    // ProcessingTasksViewModel surfaces its own load errors. Route them through
    // the same generic load-failure snackbar as HomeError to preserve the
    // pre-extraction UX (formerly HomeError.LoadFailed("processingTasks", e)).
    val processingTasksError by processingTasksViewModel.error.collectAsState()
    val processingTasksErrorMessage = when (processingTasksError) {
        is ProcessingTasksError.LoadFailed -> stringResource(R.string.error_load_data)
        null -> null
    }

    // Same pipeline for TagSuggestionsViewModel — the formerly inline
    // HomeError.LoadFailed("untaggedDocuments", e) emission now lives here.
    val tagSuggestionsError by tagSuggestionsViewModel.error.collectAsState()
    val tagSuggestionsErrorMessage = when (tagSuggestionsError) {
        is TagSuggestionsError.LoadFailed -> stringResource(R.string.error_load_data)
        null -> null
    }

    // Snackbar for undo delete
    val snackbarHostState = remember { androidx.compose.material3.SnackbarHostState() }

    // Premium Upgrade Sheet state
    var showPremiumUpgradeSheet by remember { mutableStateOf(false) }

    // Pull-to-refresh state
    var isRefreshing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // BEST PRACTICE: Debounced refresh on ON_RESUME to prevent excessive server calls
    // Only refreshes if >30 seconds since last refresh (quick app switches won't trigger)
    // Pull-to-refresh always works for forced user-triggered updates
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                // Share HomeViewModel's debounce decision with the recent-docs
                // sibling so a quick app-switch doesn't trigger an extra HTTP
                // call here that the dashboard refresh just suppressed.
                if (viewModel.refreshDashboardIfNeeded()) {
                    processingTasksViewModel.refreshTasks()
                    recentDocumentsViewModel.refreshRecentDocuments()
                    trashOverviewViewModel.refreshTrashOverview(fullTrashSync = true)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Handle tag creation success/error
    LaunchedEffect(createTagState) {
        when (createTagState) {
            is CreateTagState.Success -> {
                showCreateTagDialog = false
                tagSuggestionsViewModel.resetCreateTagState()
            }
            is CreateTagState.Error -> {
                // Keep dialog open to show error, user can dismiss manually
            }
            else -> {}
        }
    }

    // Show error snackbar for load/action failures
    LaunchedEffect(errorState) {
        errorMessage?.let { msg ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(msg)
            viewModel.clearHomeError()
        }
    }

    // Same channel for ProcessingTasksViewModel errors — but defer while an
    // undo snackbar is pending (deletedDocument != null) so a background
    // polling failure can't preempt the user's chance to undo a delete.
    LaunchedEffect(processingTasksError, recentDocumentsUiState.deletedDocument?.id) {
        if (recentDocumentsUiState.deletedDocument != null) return@LaunchedEffect
        processingTasksErrorMessage?.let { msg ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(msg)
            processingTasksViewModel.clearError()
        }
    }

    // Same pipeline + undo-guard for TagSuggestionsViewModel errors.
    LaunchedEffect(tagSuggestionsError, recentDocumentsUiState.deletedDocument?.id) {
        if (recentDocumentsUiState.deletedDocument != null) return@LaunchedEffect
        tagSuggestionsErrorMessage?.let { msg ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(msg)
            tagSuggestionsViewModel.clearError()
        }
    }

    // RecentDocumentsViewModel surfaces its own load/action errors. Route them
    // through the same generic snackbar pipeline as HomeError to preserve the
    // pre-extraction UX (formerly HomeError.LoadFailed("recentDocuments", e)
    // and HomeError.ActionFailed("deleteDocument"/"restoreDocument", e)).
    val recentDocumentsError by recentDocumentsViewModel.error.collectAsState()
    val recentDocumentsErrorMessage = when (recentDocumentsError) {
        is RecentDocumentsError.LoadFailed -> stringResource(R.string.error_load_data)
        is RecentDocumentsError.ActionFailed -> stringResource(R.string.error_action_failed)
        null -> null
    }
    LaunchedEffect(recentDocumentsError, recentDocumentsUiState.deletedDocument?.id) {
        // Don't preempt the undo snackbar for a deleteDocument error — the
        // delete VM already clears deletedDocument on failure, so when this
        // fires there's no undo to preserve, only the load/restore cases.
        if (recentDocumentsUiState.deletedDocument != null) return@LaunchedEffect
        recentDocumentsErrorMessage?.let { msg ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(msg)
            recentDocumentsViewModel.clearError()
        }
    }

    // TrashOverviewViewModel surfaces its own load errors. Same generic
    // snackbar + undo-guard pattern as the other sub-VMs (formerly
    // HomeError.LoadFailed("untaggedCount"|"deletedCount"|"deletedTimestamp")).
    val trashOverviewError by trashOverviewViewModel.error.collectAsState()
    val trashOverviewErrorMessage = when (trashOverviewError) {
        is TrashOverviewError.LoadFailed -> stringResource(R.string.error_load_data)
        null -> null
    }
    LaunchedEffect(trashOverviewError, recentDocumentsUiState.deletedDocument?.id) {
        if (recentDocumentsUiState.deletedDocument != null) return@LaunchedEffect
        trashOverviewErrorMessage?.let { msg ->
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar(msg)
            trashOverviewViewModel.clearError()
        }
    }

    // Show undo snackbar when document is deleted (using shared component)
    DocumentListSnackbar(
        snackbarHostState = snackbarHostState,
        deletedDocumentId = recentDocumentsUiState.deletedDocument?.id,
        message = stringResource(R.string.documents_deleted_snackbar),
        actionLabel = stringResource(R.string.documents_undo),
        onUndo = { recentDocumentsViewModel.undoDelete() },
        onDismiss = { recentDocumentsViewModel.clearDeletedDocument() }
    )

    // BEST PRACTICE: Pull-to-refresh for user-triggered updates
    // Refreshes stats and tasks from server to catch web/multi-device changes
    Box(modifier = Modifier.fillMaxSize()) {
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = {
                isRefreshing = true
                viewModel.refreshDashboard()
                processingTasksViewModel.refreshTasks()
                recentDocumentsViewModel.refreshRecentDocuments()
                trashOverviewViewModel.refreshTrashOverview(fullTrashSync = true)
                // Reset after a short delay (UI feedback)
                coroutineScope.launch {
                    delay(1000)
                    isRefreshing = false
                }
            }
        ) {
            // HYBRID PATTERN: State for coordinated swipe-to-reveal behavior
            val listState = rememberLazyListState()
            val swipePattern = rememberHybridSwipePattern(listState = listState)

            // BEST PRACTICE: LazyColumn for entire screen enables animateItem() for smooth Gmail-style animations
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize()
            ) {
                // Offline Indicator
                item(key = "offline-indicator") {
                    com.paperless.scanner.ui.components.OfflineIndicator(
                        isOnline = isOnline,
                        pendingChanges = pendingChanges,
                        onClickShowDetails = onNavigateToPendingSync
                    )
                }

                // Header with Last Synced Indicator
                item(key = "header") {
                    HomeHeaderSection(lastSyncedAt = uiState.lastSyncedAt)
                }

                // Spacer
                item(key = "spacer-after-header") {
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Hero Document Card
                item(key = "hero-card") {
                    HeroDocumentCard(
                        totalDocuments = uiState.stats.totalDocuments,
                        processingCount = serverHealthUiState.activeUploadsCount + processingTasksUiState.activeCount,
                        onClick = onNavigateToDocuments,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                    )
                }

                // Spacer
                item(key = "spacer-after-hero") {
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Compact Stats Row (Sync, Tags, Trash)
                item(key = "stats-row") {
                    CompactStatsRow(
                        // pendingChanges already combines upload-queue + sync-pending;
                        // adding activeUploadsCount on top double-counted upload-queue items.
                        syncActiveCount = pendingChanges,
                        syncFailedCount = serverHealthUiState.failedSyncCount,
                        untaggedCount = trashOverviewUiState.untaggedCount,
                        trashCount = trashOverviewUiState.deletedCount,
                        trashExpiresInDays = null, // Expiration info shown only in Trash screen
                        onSyncClick = onNavigateToPendingSync,
                        onTagsClick = if (isServerReachable && trashOverviewUiState.untaggedCount > 0) {
                            onNavigateToSmartTagging
                        } else null,
                        onTrashClick = onNavigateToTrash,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }

                // Spacer
                item(key = "spacer-after-stats") {
                    Spacer(modifier = Modifier.height(24.dp))
                }

                // Processing Tasks Section (only show if there are tasks)
                if (processingTasksUiState.tasks.isNotEmpty()) {
                    item(key = "processing-tasks") {
                        ProcessingTasksSection(
                            uiState = processingTasksUiState,
                            onDismissAllCompleted = { processingTasksViewModel.acknowledgeCompletedTasks() },
                            onRefresh = { processingTasksViewModel.refreshTasks() },
                            onToggleShowAll = { processingTasksViewModel.toggleShowAll() },
                            onDocumentClick = onDocumentClick,
                            onAcknowledgeTask = { task -> processingTasksViewModel.acknowledgeTask(task.id) }
                        )
                    }

                    // Spacer after processing tasks
                    item(key = "spacer-after-processing") {
                        Spacer(modifier = Modifier.height(24.dp))
                    }
                }

                // Recent Documents Section Header
                item(key = "recent-header") {
                    RecentDocumentsHeader(onSeeAll = onNavigateToDocuments)
                }

                // Empty state or document list. Gate the empty-state card on
                // !isLoading so it doesn't flash on cold start before the Room
                // cache hydrates. The observer always flips isLoading=false on
                // both success and failure paths, so this can't get stuck.
                if (recentDocumentsUiState.recentDocuments.isEmpty() && !recentDocumentsUiState.isLoading) {
                    item(key = "empty-state") {
                        EmptyDocumentsCard(modifier = Modifier.padding(horizontal = 24.dp))
                    }
                } else {
                    // BEST PRACTICE: Use items() with key for smooth Gmail-style animations
                    items(
                        items = recentDocumentsUiState.recentDocuments,
                        key = { document -> document.id }
                    ) { doc ->
                        Column(
                            modifier = animateItemRemoval()  // Gmail-style slide-off animation
                                .padding(horizontal = 24.dp)
                        ) {
                            RecentDocumentItem(
                                document = doc,
                                serverUrl = serverUrl,
                                showThumbnails = showThumbnails,
                                swipePattern = swipePattern,
                                onClick = { onDocumentClick(doc.id) },
                                onDelete = { recentDocumentsViewModel.deleteRecentDocument(doc.id, doc.title) }
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }
                }

                // Bottom spacer
                item(key = "bottom-spacer") {
                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }

        // Snackbar for undo delete (same design as DocumentsScreen)
        com.paperless.scanner.ui.components.CustomSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 16.dp)
        )
    }

    // Tag Suggestions Bottom Sheet
    if (showTagSuggestionsSheet) {
        TagSuggestionsSheet(
            sheetState = tagSuggestionsSheetState,
            state = tagSuggestionsState,
            availableTags = availableTags,
            isAiAvailable = isAiAvailable,
            onDismiss = { tagSuggestionsViewModel.closeTagSuggestionsSheet() },
            onAnalyzeDocument = { documentId -> tagSuggestionsViewModel.analyzeDocument(documentId) },
            onApplyTags = { documentId, tagIds -> tagSuggestionsViewModel.applyTagsToDocument(documentId, tagIds) },
            onSkipDocument = { documentId -> tagSuggestionsViewModel.skipDocument(documentId) },
            onOpenTagPicker = { documentId -> tagSuggestionsViewModel.openTagPicker(documentId) },
            onCloseTagPicker = { tagSuggestionsViewModel.closeTagPicker() },
            onToggleTagInPicker = { documentId, tagId -> tagSuggestionsViewModel.toggleTagInPicker(documentId, tagId) },
            onApplyPickerTags = { documentId -> tagSuggestionsViewModel.applyPickerTags(documentId) },
            onUpgradeToPremium = { showPremiumUpgradeSheet = true },
            onCreateNewTag = { showCreateTagDialog = true }
        )
    }

    // Premium Upgrade Sheet (shown when non-premium user clicks AI button)
    if (showPremiumUpgradeSheet) {
        PremiumUpgradeSheet(
            onDismiss = { showPremiumUpgradeSheet = false },
            onSubscribe = { _ ->
                // Navigate to Settings where purchase flow continues
                showPremiumUpgradeSheet = false
                onNavigateToSettings()
            },
            onRestore = {
                // Navigate to Settings where restore continues
                showPremiumUpgradeSheet = false
                onNavigateToSettings()
            }
        )
    }

    // Create Tag Dialog (consistent with other screens)
    if (showCreateTagDialog) {
        CreateTagDialog(
            isCreating = createTagState is CreateTagState.Creating,
            onDismiss = {
                showCreateTagDialog = false
                tagSuggestionsViewModel.resetCreateTagState()
            },
            onCreate = { name, color ->
                tagSuggestionsViewModel.createTag(name, color)
            }
        )
    }
}
