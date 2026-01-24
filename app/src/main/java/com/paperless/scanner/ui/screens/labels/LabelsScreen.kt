package com.paperless.scanner.ui.screens.labels

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import androidx.hilt.navigation.compose.hiltViewModel
import com.paperless.scanner.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

data class LabelItem(
    val id: Int,
    val name: String,
    val color: Color,
    val documentCount: Int
)

// Dark Tech Precision label color palette
val labelColorOptions = listOf(
    Color(0xFFE1FF8D), // Neon Yellow/Green (Primary)
    Color(0xFF8DD7FF), // Electric Blue
    Color(0xFFFF8D8D), // Coral Red
    Color(0xFFB88DFF), // Electric Purple
    Color(0xFF8DFFB8), // Mint Green
    Color(0xFFFFB88D), // Warm Orange
    Color(0xFFFF8DFF)  // Hot Pink
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelsScreen(
    onDocumentClick: (Int) -> Unit = {},
    viewModel: LabelsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var searchQuery by remember { mutableStateOf("") }
    var showCreateSheet by remember { mutableStateOf(false) }
    var showSortFilterSheet by remember { mutableStateOf(false) }
    var editingLabel by remember { mutableStateOf<LabelItem?>(null) }

    // Pull-to-refresh state
    var isRefreshing by remember { mutableStateOf(false) }

    // BEST PRACTICE: selectedLabel moved to ViewModel to survive navigation
    // See LabelsUiState.selectedLabel

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val sortFilterSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // Handle back press in detail view
    BackHandler(enabled = uiState.selectedLabel != null) {
        viewModel.clearSelectedLabel()
    }

    // Label detail view
    if (uiState.selectedLabel != null) {
        LabelDetailView(
            label = uiState.selectedLabel!!,
            documents = uiState.documentsForLabel,
            onBack = {
                viewModel.clearSelectedLabel()
            },
            onDocumentClick = onDocumentClick
        )
        return
    }

    // Main labels list view
    // BEST PRACTICE: Pull-to-refresh for user-triggered updates
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            isRefreshing = true
            viewModel.refresh()
            // Reset after a short delay (UI feedback)
            scope.launch {
                delay(1000)
                isRefreshing = false
            }
        }
    ) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
        // Header with Add Button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 24.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.labels_title),
                    style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.ExtraBold
                )
                Text(
                    text = stringResource(R.string.labels_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Add Label Button - always visible
            Card(
                onClick = {
                    editingLabel = null
                    showCreateSheet = true
                },
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primary
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.cd_add),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                    Text(
                        text = stringResource(R.string.labels_add_new),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }

        // Search Bar with Sort/Filter Button
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
                    Text(stringResource(R.string.labels_search_placeholder))
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
                                contentDescription = stringResource(R.string.labels_search_clear),
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

            // Sort/Filter Button
            Card(
                onClick = { showSortFilterSheet = true },
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (uiState.sortOption != LabelSortOption.NAME_ASC || uiState.filterOption != LabelFilterOption.ALL) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    }
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
            ) {
                Icon(
                    imageVector = Icons.Filled.FilterList,
                    contentDescription = stringResource(R.string.labels_sort_filter),
                    modifier = Modifier.padding(12.dp),
                    tint = if (uiState.sortOption != LabelSortOption.NAME_ASC || uiState.filterOption != LabelFilterOption.ALL) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            }
        }

        // Labels List (Single Column)
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                horizontal = 24.dp,
                vertical = 8.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(uiState.labels, key = { it.id }) { label ->
                LabelCard(
                    label = label,
                    onClick = {
                        // BEST PRACTICE: Use ViewModel state to survive navigation
                        viewModel.selectLabel(label)
                    },
                    onEdit = {
                        editingLabel = label
                        showCreateSheet = true
                    },
                    onDelete = { viewModel.prepareDeleteLabel(label.id) }
                )
            }
        }
        }
    }

    // Create/Edit Label Bottom Sheet
    if (showCreateSheet) {
        ModalBottomSheet(
            onDismissRequest = {
                showCreateSheet = false
                editingLabel = null
            },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.background
        ) {
            CreateLabelSheet(
                existingLabel = editingLabel,
                onSave = { name, color ->
                    if (editingLabel != null) {
                        viewModel.updateLabel(editingLabel!!.id, name, color)
                    } else {
                        viewModel.createLabel(name, color)
                    }
                    scope.launch {
                        sheetState.hide()
                        showCreateSheet = false
                        editingLabel = null
                    }
                },
                onDismiss = {
                    scope.launch {
                        sheetState.hide()
                        showCreateSheet = false
                        editingLabel = null
                    }
                }
            )
        }
    }

    // Sort/Filter Bottom Sheet
    if (showSortFilterSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSortFilterSheet = false },
            sheetState = sortFilterSheetState,
            containerColor = MaterialTheme.colorScheme.background
        ) {
            SortFilterSheet(
                currentSort = uiState.sortOption,
                currentFilter = uiState.filterOption,
                onApply = { sort, filter ->
                    viewModel.setSortAndFilter(sort, filter)
                    scope.launch {
                        sortFilterSheetState.hide()
                        showSortFilterSheet = false
                    }
                },
                onReset = {
                    viewModel.resetSortAndFilter()
                    scope.launch {
                        sortFilterSheetState.hide()
                        showSortFilterSheet = false
                    }
                },
                onDismiss = {
                    scope.launch {
                        sortFilterSheetState.hide()
                        showSortFilterSheet = false
                    }
                }
            )
        }
    }

    // Delete Confirmation Dialog
    uiState.pendingDeleteLabel?.let { pendingDelete ->
        DeleteConfirmationDialog(
            pendingDelete = pendingDelete,
            isDeleting = uiState.isDeleting,
            onConfirm = { viewModel.confirmDeleteLabel() },
            onDismiss = { viewModel.clearPendingDelete() }
        )
    }
}

@Composable
private fun LabelCard(
    label: LabelItem,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Color indicator
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(label.color)
            )

            Spacer(modifier = Modifier.width(12.dp))

            // Label info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.labels_document_count, label.documentCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Action buttons
            Row {
                IconButton(
                    onClick = onEdit,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.labels_edit),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = onDelete,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.labels_delete),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

/**
 * Delete confirmation dialog that shows document count information.
 * Uses the new best practice strings with dynamic messages.
 */
@Composable
private fun DeleteConfirmationDialog(
    pendingDelete: PendingDeleteLabel,
    isDeleting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val message = when {
        pendingDelete.documentCount == 0 -> stringResource(
            R.string.labels_delete_dialog_message_no_docs,
            pendingDelete.name
        )
        pendingDelete.documentCount == 1 -> stringResource(
            R.string.labels_delete_dialog_message_one_doc,
            pendingDelete.name
        )
        else -> stringResource(
            R.string.labels_delete_dialog_message_with_docs,
            pendingDelete.name,
            pendingDelete.documentCount
        )
    }

    AlertDialog(
        onDismissRequest = { if (!isDeleting) onDismiss() },
        title = { Text(stringResource(R.string.labels_delete_dialog_title)) },
        text = {
            Column {
                Text(message)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.labels_delete_dialog_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !isDeleting
            ) {
                Text(
                    stringResource(R.string.labels_delete_button),
                    color = if (isDeleting) MaterialTheme.colorScheme.error.copy(alpha = 0.5f)
                           else MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isDeleting
            ) {
                Text(stringResource(R.string.labels_cancel_button))
            }
        }
    )
}

@Composable
private fun SortFilterSheet(
    currentSort: LabelSortOption,
    currentFilter: LabelFilterOption,
    onApply: (LabelSortOption, LabelFilterOption) -> Unit,
    onReset: () -> Unit,
    onDismiss: () -> Unit
) {
    var selectedSort by remember { mutableStateOf(currentSort) }
    var selectedFilter by remember { mutableStateOf(currentFilter) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(24.dp)
    ) {
        Text(
            text = stringResource(R.string.labels_sort_filter),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Sort Section
        Text(
            text = stringResource(R.string.labels_sort_title),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Column(modifier = Modifier.selectableGroup()) {
            SortOption(
                text = stringResource(R.string.labels_sort_name_asc),
                selected = selectedSort == LabelSortOption.NAME_ASC,
                onClick = { selectedSort = LabelSortOption.NAME_ASC }
            )
            SortOption(
                text = stringResource(R.string.labels_sort_name_desc),
                selected = selectedSort == LabelSortOption.NAME_DESC,
                onClick = { selectedSort = LabelSortOption.NAME_DESC }
            )
            SortOption(
                text = stringResource(R.string.labels_sort_count_desc),
                selected = selectedSort == LabelSortOption.COUNT_DESC,
                onClick = { selectedSort = LabelSortOption.COUNT_DESC }
            )
            SortOption(
                text = stringResource(R.string.labels_sort_count_asc),
                selected = selectedSort == LabelSortOption.COUNT_ASC,
                onClick = { selectedSort = LabelSortOption.COUNT_ASC }
            )
            SortOption(
                text = stringResource(R.string.labels_sort_newest),
                selected = selectedSort == LabelSortOption.NEWEST,
                onClick = { selectedSort = LabelSortOption.NEWEST }
            )
            SortOption(
                text = stringResource(R.string.labels_sort_oldest),
                selected = selectedSort == LabelSortOption.OLDEST,
                onClick = { selectedSort = LabelSortOption.OLDEST }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f))
        Spacer(modifier = Modifier.height(16.dp))

        // Filter Section
        Text(
            text = stringResource(R.string.labels_filter_title),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(8.dp))

        Column(modifier = Modifier.selectableGroup()) {
            SortOption(
                text = stringResource(R.string.labels_filter_all),
                selected = selectedFilter == LabelFilterOption.ALL,
                onClick = { selectedFilter = LabelFilterOption.ALL }
            )
            SortOption(
                text = stringResource(R.string.labels_filter_with_docs),
                selected = selectedFilter == LabelFilterOption.WITH_DOCUMENTS,
                onClick = { selectedFilter = LabelFilterOption.WITH_DOCUMENTS }
            )
            SortOption(
                text = stringResource(R.string.labels_filter_empty),
                selected = selectedFilter == LabelFilterOption.EMPTY,
                onClick = { selectedFilter = LabelFilterOption.EMPTY }
            )
            SortOption(
                text = stringResource(R.string.labels_filter_many_docs),
                selected = selectedFilter == LabelFilterOption.MANY_DOCUMENTS,
                onClick = { selectedFilter = LabelFilterOption.MANY_DOCUMENTS }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                onClick = onReset,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.labels_reset_button),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Card(
                onClick = { onApply(selectedSort, selectedFilter) },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.labels_apply_button),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun SortOption(
    text: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton
            )
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(
                selectedColor = MaterialTheme.colorScheme.primary
            )
        )
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun CreateLabelSheet(
    existingLabel: LabelItem?,
    onSave: (String, Color) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(existingLabel?.name ?: "") }
    var selectedColor by remember { mutableStateOf(existingLabel?.color ?: labelColorOptions[0]) }
    var showCustomColorPicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(24.dp)
    ) {
        Text(
            text = if (existingLabel != null) stringResource(R.string.labels_edit_title) else stringResource(R.string.labels_create_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Name input
        Text(
            text = stringResource(R.string.labels_name_label),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.labels_name_placeholder)) },
            shape = RoundedCornerShape(8.dp),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Color picker
        Text(
            text = stringResource(R.string.labels_color_label),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            labelColorOptions.forEach { color ->
                Card(
                    onClick = { selectedColor = color },
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = color
                    ),
                    border = BorderStroke(
                        width = if (selectedColor == color) 3.dp else 1.dp,
                        color = if (selectedColor == color) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.outline
                        }
                    ),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        if (selectedColor == color) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.onSurface)
                            )
                        }
                    }
                }
            }

            // Custom Color Picker Button
            Card(
                onClick = { showCustomColorPicker = true },
                modifier = Modifier
                    .weight(1f)
                    .aspectRatio(1f),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                border = BorderStroke(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.labels_custom_color),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Preview
        Text(
            text = stringResource(R.string.labels_preview_label),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(selectedColor)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = name.ifEmpty { stringResource(R.string.labels_preview_default) },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card(
                onClick = onDismiss,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.labels_cancel_button),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Card(
                onClick = {
                    if (name.isNotBlank()) {
                        onSave(name.trim(), selectedColor)
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (existingLabel != null) stringResource(R.string.labels_save_button) else stringResource(R.string.labels_create_button),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }

    // Custom Color Picker Dialog
    if (showCustomColorPicker) {
        CustomColorPickerDialog(
            initialColor = selectedColor,
            onColorSelected = { color ->
                selectedColor = color
                showCustomColorPicker = false
            },
            onDismiss = { showCustomColorPicker = false }
        )
    }
}

@Composable
private fun CustomColorPickerDialog(
    initialColor: Color,
    onColorSelected: (Color) -> Unit,
    onDismiss: () -> Unit
) {
    var hue by remember { mutableStateOf(0f) }
    var saturation by remember { mutableStateOf(1f) }
    var value by remember { mutableStateOf(1f) }

    // Convert initial color to HSV
    val hsv = remember {
        val rgb = android.graphics.Color.rgb(
            (initialColor.red * 255).toInt(),
            (initialColor.green * 255).toInt(),
            (initialColor.blue * 255).toInt()
        )
        FloatArray(3).apply {
            android.graphics.Color.colorToHSV(rgb, this)
        }
    }

    LaunchedEffect(Unit) {
        hue = hsv[0]
        saturation = hsv[1]
        value = hsv[2]
    }

    // Convert HSV to Color
    val currentColor = remember(hue, saturation, value) {
        val rgb = android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, value))
        Color(rgb)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.labels_custom_color_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Color Preview
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(currentColor)
                        .then(
                            Modifier.border(
                                width = 2.dp,
                                color = MaterialTheme.colorScheme.outline,
                                shape = RoundedCornerShape(16.dp)
                            )
                        )
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Hue Slider
                Text(
                    text = stringResource(R.string.labels_hue_label),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.align(Alignment.Start)
                )
                Spacer(modifier = Modifier.height(8.dp))
                HueSlider(
                    hue = hue,
                    onHueChange = { hue = it }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Saturation Slider
                Text(
                    text = stringResource(R.string.labels_saturation_label),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.align(Alignment.Start)
                )
                Spacer(modifier = Modifier.height(8.dp))
                SaturationSlider(
                    hue = hue,
                    saturation = saturation,
                    onSaturationChange = { saturation = it }
                )

                Spacer(modifier = Modifier.height(16.dp))

                // Brightness Slider
                Text(
                    text = stringResource(R.string.labels_brightness_label),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.align(Alignment.Start)
                )
                Spacer(modifier = Modifier.height(8.dp))
                BrightnessSlider(
                    hue = hue,
                    saturation = saturation,
                    value = value,
                    onValueChange = { value = it }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onColorSelected(currentColor) }) {
                Text(stringResource(R.string.labels_select_button))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.labels_cancel_button))
            }
        }
    )
}

@Composable
private fun HueSlider(
    hue: Float,
    onHueChange: (Float) -> Unit
) {
    var sliderPosition by remember { mutableStateOf(hue / 360f) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val newPosition = (offset.x / size.width).coerceIn(0f, 1f)
                        sliderPosition = newPosition
                        onHueChange(newPosition * 360f)
                    }
                }
        ) {
            val gradient = Brush.horizontalGradient(
                colors = listOf(
                    Color.Red,
                    Color.Yellow,
                    Color.Green,
                    Color.Cyan,
                    Color.Blue,
                    Color.Magenta,
                    Color.Red
                )
            )
            drawRect(brush = gradient)

            // Indicator
            val indicatorX = size.width * sliderPosition
            drawCircle(
                color = Color.White,
                radius = 12f,
                center = Offset(indicatorX, size.height / 2),
                style = Stroke(width = 3f)
            )
            drawCircle(
                color = Color.Black,
                radius = 12f,
                center = Offset(indicatorX, size.height / 2),
                style = Stroke(width = 1f)
            )
        }
    }
}

@Composable
private fun SaturationSlider(
    hue: Float,
    saturation: Float,
    onSaturationChange: (Float) -> Unit
) {
    var sliderPosition by remember(saturation) { mutableStateOf(saturation) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val newPosition = (offset.x / size.width).coerceIn(0f, 1f)
                        sliderPosition = newPosition
                        onSaturationChange(newPosition)
                    }
                }
        ) {
            val startColor = Color.White
            val endColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)))

            val gradient = Brush.horizontalGradient(
                colors = listOf(startColor, endColor)
            )
            drawRect(brush = gradient)

            // Indicator
            val indicatorX = size.width * sliderPosition
            drawCircle(
                color = Color.White,
                radius = 12f,
                center = Offset(indicatorX, size.height / 2),
                style = Stroke(width = 3f)
            )
            drawCircle(
                color = Color.Black,
                radius = 12f,
                center = Offset(indicatorX, size.height / 2),
                style = Stroke(width = 1f)
            )
        }
    }
}

@Composable
private fun BrightnessSlider(
    hue: Float,
    saturation: Float,
    value: Float,
    onValueChange: (Float) -> Unit
) {
    var sliderPosition by remember(value) { mutableStateOf(value) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val newPosition = (offset.x / size.width).coerceIn(0f, 1f)
                        sliderPosition = newPosition
                        onValueChange(newPosition)
                    }
                }
        ) {
            val startColor = Color.Black
            val endColor = Color(android.graphics.Color.HSVToColor(floatArrayOf(hue, saturation, 1f)))

            val gradient = Brush.horizontalGradient(
                colors = listOf(startColor, endColor)
            )
            drawRect(brush = gradient)

            // Indicator
            val indicatorX = size.width * sliderPosition
            drawCircle(
                color = Color.White,
                radius = 12f,
                center = Offset(indicatorX, size.height / 2),
                style = Stroke(width = 3f)
            )
            drawCircle(
                color = Color.Black,
                radius = 12f,
                center = Offset(indicatorX, size.height / 2),
                style = Stroke(width = 1f)
            )
        }
    }
}

@Composable
private fun LabelDetailView(
    label: LabelItem,
    documents: List<LabelDocument>,
    onBack: () -> Unit,
    onDocumentClick: (Int) -> Unit
) {
    // Handle back press
    BackHandler(onBack = onBack)

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 24.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clickable { onBack() }
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.labels_detail_back),
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.labels_detail_back),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(label.color)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = label.name,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                text = stringResource(R.string.labels_document_count, documents.size),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Document List
        if (documents.isEmpty()) {
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
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = stringResource(R.string.labels_detail_no_documents),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 24.dp,
                    vertical = 8.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(documents, key = { it.id }) { doc ->
                    Card(
                        onClick = { onDocumentClick(doc.id) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        ),
                        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Description,
                                    contentDescription = stringResource(R.string.cd_document_thumbnail),
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = doc.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = doc.date,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "•",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${doc.pageCount} ${if (doc.pageCount != 1) stringResource(R.string.labels_detail_page_count_plural) else stringResource(R.string.labels_detail_page_count_singular)}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

data class LabelDocument(
    val id: Int,
    val title: String,
    val date: String,
    val pageCount: Int
)
