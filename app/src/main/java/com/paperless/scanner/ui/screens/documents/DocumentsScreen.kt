package com.paperless.scanner.ui.screens.documents

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.paging.LoadState
import androidx.paging.compose.collectAsLazyPagingItems
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.paperless.scanner.R
import com.paperless.scanner.ui.components.CustomSnackbarHost
import com.paperless.scanner.ui.theme.LocalWindowSizeClass
import com.paperless.scanner.ui.components.documentlist.SwipeableDocumentCardContainer
import com.paperless.scanner.ui.components.documentlist.DocumentCardBase
import com.paperless.scanner.ui.components.documentlist.DocumentListSnackbar
import com.paperless.scanner.ui.components.documentlist.animateItemRemoval
import com.paperless.scanner.ui.components.documentlist.rememberHybridSwipePattern

data class DocumentItem(
    val id: Int,
    val title: String,
    val date: String,
    val correspondent: String?,
    val tags: List<String> = emptyList()
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DocumentsScreen(
    onDocumentClick: (Int) -> Unit = {},
    viewModel: DocumentsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val pagedDocuments = viewModel.pagedDocuments.collectAsLazyPagingItems()
    val serverUrl by viewModel.serverUrl.collectAsState()
    val showThumbnails by viewModel.showThumbnails.collectAsState()
    var searchQuery by remember { mutableStateOf("") }

    // Pull-to-refresh state
    var isRefreshing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // Filter sheet state
    var showFilterSheet by remember { mutableStateOf(false) }
    val filterSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // LazyGrid state for scroll control
    val gridState = rememberLazyGridState()

    // HYBRID PATTERN: State for coordinated swipe-to-reveal behavior
    val swipePattern = rememberHybridSwipePattern(gridState = gridState)

    // Snackbar state for undo delete
    val snackbarHostState = remember { SnackbarHostState() }

    // Show undo snackbar when document is deleted (using shared component)
    DocumentListSnackbar(
        snackbarHostState = snackbarHostState,
        deletedDocumentId = uiState.deletedDocument?.id,
        message = stringResource(R.string.documents_deleted_snackbar),
        actionLabel = stringResource(R.string.documents_undo),
        onUndo = { viewModel.undoDelete() },
        onDismiss = { viewModel.clearDeletedDocument() }
    )

    // Auto-scroll to top when filter or sort changes
    // Wait for LoadState.refresh to finish before scrolling to ensure new data is loaded
    LaunchedEffect(uiState.currentFilter, pagedDocuments.loadState.refresh) {
        if (pagedDocuments.loadState.refresh is LoadState.NotLoading && pagedDocuments.itemCount > 0) {
            gridState.animateScrollToItem(0)
        }
    }

    // BEST PRACTICE: Room Flow automatically updates UI when documents change in DB.
    // Pull-to-refresh allows users to manually trigger server sync.
    // See DocumentsViewModel.observeDocumentsReactively()

    Scaffold(
        snackbarHost = { CustomSnackbarHost(hostState = snackbarHostState) }
    ) { scaffoldPadding ->
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            isRefreshing = true
            viewModel.refresh()
            // Reset after a short delay (UI feedback)
            coroutineScope.launch {
                delay(1000)
                isRefreshing = false
            }
        },
        modifier = Modifier.padding(scaffoldPadding)
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
        // Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 24.dp, bottom = 8.dp)
        ) {
            Text(
                text = stringResource(R.string.documents_title),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = if (uiState.isLoading) stringResource(R.string.loading) else stringResource(R.string.documents_count, uiState.totalCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // Search Bar + Filter Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = {
                    searchQuery = it
                    viewModel.search(it)
                },
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(stringResource(R.string.documents_search_placeholder))
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = stringResource(R.string.cd_search),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = {
                            searchQuery = ""
                            viewModel.search("")
                        }) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.documents_search_clear),
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                },
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedBorderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
                ),
                singleLine = true
            )

            // Filter Button with Badge
            val activeFilterCount = uiState.currentFilter.activeFilterCount()
            BadgedBox(
                badge = {
                    if (activeFilterCount > 0) {
                        Badge(
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary
                        ) {
                            Text(activeFilterCount.toString())
                        }
                    }
                }
            ) {
                IconButton(
                    onClick = { showFilterSheet = true },
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            color = if (activeFilterCount > 0) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(8.dp)
                        )
                ) {
                    Icon(
                        imageVector = Icons.Filled.FilterList,
                        contentDescription = stringResource(R.string.filter_button),
                        tint = if (activeFilterCount > 0) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Active Filter Chips
        if (!uiState.currentFilter.isEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Tags chip
                if (uiState.currentFilter.tagIds.isNotEmpty()) {
                    FilterChip(
                        selected = false,
                        onClick = { viewModel.updateFilter { it.copy(tagIds = emptyList()) } },
                        label = { Text(stringResource(R.string.filter_chip_tags, uiState.currentFilter.tagIds.size)) },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize)
                            )
                        }
                    )
                }

                // Correspondent chip
                if (uiState.currentFilter.correspondentId != null) {
                    FilterChip(
                        selected = false,
                        onClick = { viewModel.updateFilter { it.copy(correspondentId = null) } },
                        label = { Text(stringResource(R.string.filter_chip_correspondent)) },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize)
                            )
                        }
                    )
                }

                // Document Type chip
                if (uiState.currentFilter.documentTypeId != null) {
                    FilterChip(
                        selected = false,
                        onClick = { viewModel.updateFilter { it.copy(documentTypeId = null) } },
                        label = { Text(stringResource(R.string.filter_chip_document_type)) },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize)
                            )
                        }
                    )
                }

                // Archive Status chip
                if (uiState.currentFilter.hasArchiveSerialNumber != null) {
                    FilterChip(
                        selected = false,
                        onClick = { viewModel.updateFilter { it.copy(hasArchiveSerialNumber = null) } },
                        label = { Text(stringResource(R.string.filter_chip_archive_status)) },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize)
                            )
                        }
                    )
                }

                // Created Date chip
                if (uiState.currentFilter.createdDateFrom != null || uiState.currentFilter.createdDateTo != null) {
                    FilterChip(
                        selected = false,
                        onClick = { viewModel.updateFilter { it.copy(createdDateFrom = null, createdDateTo = null) } },
                        label = {
                            Text(
                                stringResource(
                                    R.string.filter_chip_created_date,
                                    formatDateRangeForChip(
                                        uiState.currentFilter.createdDateFrom,
                                        uiState.currentFilter.createdDateTo
                                    )
                                )
                            )
                        },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize)
                            )
                        }
                    )
                }

                // Added Date chip
                if (uiState.currentFilter.addedDateFrom != null || uiState.currentFilter.addedDateTo != null) {
                    FilterChip(
                        selected = false,
                        onClick = { viewModel.updateFilter { it.copy(addedDateFrom = null, addedDateTo = null) } },
                        label = {
                            Text(
                                stringResource(
                                    R.string.filter_chip_added_date,
                                    formatDateRangeForChip(
                                        uiState.currentFilter.addedDateFrom,
                                        uiState.currentFilter.addedDateTo
                                    )
                                )
                            )
                        },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize)
                            )
                        }
                    )
                }

                // Modified Date chip
                if (uiState.currentFilter.modifiedDateFrom != null || uiState.currentFilter.modifiedDateTo != null) {
                    FilterChip(
                        selected = false,
                        onClick = { viewModel.updateFilter { it.copy(modifiedDateFrom = null, modifiedDateTo = null) } },
                        label = {
                            Text(
                                stringResource(
                                    R.string.filter_chip_modified_date,
                                    formatDateRangeForChip(
                                        uiState.currentFilter.modifiedDateFrom,
                                        uiState.currentFilter.modifiedDateTo
                                    )
                                )
                            )
                        },
                        trailingIcon = {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = null,
                                modifier = Modifier.size(FilterChipDefaults.IconSize)
                            )
                        }
                    )
                }

                // Clear All chip
                FilterChip(
                    selected = false,
                    onClick = { viewModel.clearFilter() },
                    label = { Text(stringResource(R.string.filter_clear_all)) },
                    colors = FilterChipDefaults.filterChipColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        labelColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
        }

        // Document List (Paging 3 Infinite Scroll)
        if (pagedDocuments.itemCount == 0 && !uiState.isLoading) {
            // Empty state
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Filled.Description,
                        contentDescription = stringResource(R.string.cd_no_documents),
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (searchQuery.isNotEmpty()) stringResource(R.string.documents_empty_no_results) else stringResource(R.string.documents_empty_no_documents),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (searchQuery.isNotEmpty())
                            stringResource(R.string.documents_empty_hint_search)
                        else
                            stringResource(R.string.documents_empty_hint_scan),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            // Responsive grid: 1 column on phones, 2 on tablets portrait, 3 on tablets landscape
            val windowSizeClass = LocalWindowSizeClass.current
            val columns = when (windowSizeClass.widthSizeClass) {
                WindowWidthSizeClass.Compact -> 1
                WindowWidthSizeClass.Medium -> 2
                WindowWidthSizeClass.Expanded -> 3
                else -> 1
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    horizontal = 24.dp,
                    vertical = 8.dp
                ),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    count = pagedDocuments.itemCount,
                    key = { index -> pagedDocuments[index]?.id ?: index }
                ) { index ->
                    val document = pagedDocuments[index]
                    if (document != null) {
                        // Using shared swipeable container with Hybrid Pattern
                        // MULTI-TOUCH RACE CONDITION FIX: Pass swipePatternState for
                        // sequential close-then-open animations via Mutex
                        SwipeableDocumentCardContainer(
                            documentId = document.id,
                            externallyRevealed = swipePattern.isRevealed(document.id),
                            swipePatternState = swipePattern,
                            onDelete = { viewModel.deleteDocument(document.id, document.title) },
                            modifier = animateItemRemoval()  // Gmail-style slide-off animation
                        ) {
                            DocumentCardBase(
                                documentId = document.id,
                                serverUrl = serverUrl,
                                showThumbnails = showThumbnails,
                                onClick = { onDocumentClick(document.id) }
                            ) {
                                // DocumentsScreen-specific metadata
                                Text(
                                    text = document.title,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(top = 4.dp)
                                ) {
                                    Text(
                                        text = document.date,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    document.correspondent?.let { correspondent ->
                                        Text(
                                            text = " • $correspondent",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                                // Tags - FlowRow for automatic wrapping, maxVisibleTags = 5
                                if (document.tags.isNotEmpty()) {
                                    val maxVisibleTags = 5
                                    FlowRow(
                                        modifier = Modifier.padding(top = 8.dp),
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        document.tags.take(maxVisibleTags).forEach { tag ->
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(50))
                                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = tag,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }
                                        if (document.tags.size > maxVisibleTags) {
                                            Box(
                                                modifier = Modifier
                                                    .clip(RoundedCornerShape(50))
                                                    .background(MaterialTheme.colorScheme.secondaryContainer)
                                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = "+${document.tags.size - maxVisibleTags}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Append LoadState (Loading at bottom while scrolling)
                when (val appendState = pagedDocuments.loadState.append) {
                    is LoadState.Loading -> {
                        item(span = { GridItemSpan(columns) }) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(32.dp),
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    is LoadState.Error -> {
                        item(span = { GridItemSpan(columns) }) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                ),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = stringResource(R.string.error_loading_more),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Button(
                                        onClick = { pagedDocuments.retry() }
                                    ) {
                                        Text(stringResource(R.string.retry))
                                    }
                                }
                            }
                        }
                    }
                    is LoadState.NotLoading -> {
                        // No additional UI needed
                    }
                }
            }
        }
        }
    }

    // Filter Sheet
    if (showFilterSheet) {
        DocumentFilterSheet(
            sheetState = filterSheetState,
            currentFilter = uiState.currentFilter,
            availableTags = uiState.availableTags,
            availableCorrespondents = uiState.availableCorrespondents,
            availableDocumentTypes = uiState.availableDocumentTypes,
            onDismiss = { showFilterSheet = false },
            onApply = { filter ->
                viewModel.applyFilter(filter)
            }
        )
    }
    } // Scaffold
}

/**
 * Format date range for filter chip display.
 * Examples:
 * - "01.01 - 31.01" (both dates set)
 * - "Ab 01.01" (only start date)
 * - "Bis 31.01" (only end date)
 */
private fun formatDateRangeForChip(startDate: String?, endDate: String?): String {
    return when {
        startDate != null && endDate != null -> {
            val start = formatShortDate(startDate)
            val end = formatShortDate(endDate)
            "$start - $end"
        }
        startDate != null -> "Ab ${formatShortDate(startDate)}"
        endDate != null -> "Bis ${formatShortDate(endDate)}"
        else -> ""
    }
}

/**
 * Format ISO date (YYYY-MM-DD) to short display format (DD.MM).
 */
private fun formatShortDate(isoDate: String): String {
    return try {
        val parts = isoDate.split("-")
        if (parts.size == 3) {
            "${parts[2]}.${parts[1]}"
        } else {
            isoDate
        }
    } catch (e: Exception) {
        isoDate
    }
}
