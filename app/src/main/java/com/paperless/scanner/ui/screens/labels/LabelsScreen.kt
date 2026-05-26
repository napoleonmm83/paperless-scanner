package com.paperless.scanner.ui.screens.labels

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.paperless.scanner.R
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LabelsScreen(
    onDocumentClick: (Int) -> Unit = {},
    viewModel: LabelsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    // Issue #104: sheet/dialog state + search query live in VM SavedStateHandle
    // and survive process death + AppLock. Resolve the editing entity from
    // the live `uiState.entities` list so the sheet always sees fresh data
    // (or null if the entity was deleted while the sheet was backgrounded).
    val editingEntity: EntityItem? = uiState.editingEntityId?.let { id ->
        uiState.entities.find { it.id == id }
    }

    // Pull-to-refresh state — transient UI feedback, not in #104 scope.
    var isRefreshing by remember { mutableStateOf(false) }

    // BEST PRACTICE: selectedLabel moved to ViewModel to survive navigation
    // See LabelsUiState.selectedLabel

    val sortFilterSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // Handle back press in detail view
    BackHandler(enabled = uiState.selectedEntity != null) {
        viewModel.clearSelectedEntity()
    }

    // Label detail view
    if (uiState.selectedEntity != null) {
        // Convert EntityItem to LabelItem for detail view
        val selectedEntity = uiState.selectedEntity!!
        val labelItem = LabelItem(
            id = selectedEntity.id,
            name = selectedEntity.name,
            color = selectedEntity.color ?: Color(0xFFE1FF8D), // Default neon yellow
            documentCount = selectedEntity.documentCount
        )
        LabelDetailView(
            label = labelItem,
            documents = uiState.documentsForEntity,
            onBack = {
                viewModel.clearSelectedEntity()
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

            // Add Entity Button - label changes based on currentEntityType
            Card(
                onClick = { viewModel.openCreateSheet() },
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
                        text = when (uiState.currentEntityType) {
                            EntityType.TAG -> stringResource(R.string.labels_add_new)
                            EntityType.CORRESPONDENT -> stringResource(R.string.entity_create_correspondent)
                            EntityType.DOCUMENT_TYPE -> stringResource(R.string.entity_create_document_type)
                            EntityType.CUSTOM_FIELD -> stringResource(R.string.entity_create_custom_field)
                        },
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }

        // Entity Type Selector (Trigger Button with BottomSheet)
        EntityTypeSelector(
            selectedType = uiState.currentEntityType,
            customFieldsAvailable = uiState.customFieldsAvailable,
            onTypeSelected = viewModel::setEntityType,
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(top = 16.dp, bottom = 8.dp)
        )

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
                value = uiState.searchQuery,
                onValueChange = viewModel::search,
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
                    if (uiState.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { viewModel.search("") }) {
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
                onClick = { viewModel.openSortFilterSheet() },
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
            contentPadding = PaddingValues(
                horizontal = 24.dp,
                vertical = 8.dp
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Empty State
            if (uiState.entities.isEmpty() && !uiState.isLoading) {
                item(key = "empty-state") {
                    EntityEmptyState(entityType = uiState.currentEntityType)
                }
            }

            items(uiState.entities, key = { it.id }) { entity ->
                EntityCard(
                    entity = entity,
                    onClick = {
                        // BEST PRACTICE: Use ViewModel state to survive navigation
                        viewModel.selectEntity(entity)
                    },
                    onEdit = { viewModel.startEditingEntity(entity.id) },
                    onDelete = { viewModel.prepareDeleteEntity(entity.id) }
                )
            }
        }
        }
    }

    // Create/Edit Entity Dialog
    if (uiState.showCreateSheet) {
        CreateEntityDialog(
            entityType = uiState.currentEntityType,
            existingEntity = editingEntity,
            isCreating = false, // TODO: Add isCreating state to ViewModel
            onDismiss = { viewModel.closeCreateSheet() },
            onCreate = { name, color, dataType ->
                if (editingEntity != null) {
                    viewModel.updateEntity(editingEntity.id, name, color, dataType)
                } else {
                    viewModel.createEntity(name, color, dataType)
                }
                viewModel.closeCreateSheet()
            }
        )
    }

    // Sort/Filter Bottom Sheet
    if (uiState.showSortFilterSheet) {
        ModalBottomSheet(
            onDismissRequest = { viewModel.closeSortFilterSheet() },
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
                        viewModel.closeSortFilterSheet()
                    }
                },
                onReset = {
                    viewModel.resetSortAndFilter()
                    scope.launch {
                        sortFilterSheetState.hide()
                        viewModel.closeSortFilterSheet()
                    }
                },
                onDismiss = {
                    scope.launch {
                        sortFilterSheetState.hide()
                        viewModel.closeSortFilterSheet()
                    }
                }
            )
        }
    }

    // Delete Confirmation Dialog
    uiState.pendingDeleteEntity?.let { pendingDelete ->
        DeleteConfirmationDialog(
            pendingDelete = pendingDelete,
            isDeleting = uiState.isDeleting,
            onConfirm = { viewModel.confirmDeleteEntity() },
            onDismiss = { viewModel.clearPendingDelete() }
        )
    }
}
