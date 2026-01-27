package com.paperless.scanner.ui.screens.documents

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paperless.scanner.R
import com.paperless.scanner.domain.model.Correspondent
import com.paperless.scanner.domain.model.DocumentFilter
import com.paperless.scanner.domain.model.DocumentSortField
import com.paperless.scanner.domain.model.DocumentType
import com.paperless.scanner.domain.model.SortOrder
import com.paperless.scanner.domain.model.Tag
import com.paperless.scanner.ui.components.TagAutocomplete
import com.paperless.scanner.ui.components.DateRangePickerField

/**
 * Advanced document filter bottom sheet.
 *
 * Supports 12 filter criteria:
 * - Full-text search (query)
 * - Multi-tag selection (OR logic)
 * - Correspondent (single select)
 * - Document Type (single select)
 * - Date ranges (created, added, modified) - MVP: simple text input
 * - Archive status (has ASN / no ASN / all)
 *
 * Dark Tech Precision Pro Style: 20dp corners, 1dp border, no elevation.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DocumentFilterSheet(
    sheetState: SheetState,
    currentFilter: DocumentFilter,
    availableTags: List<Tag>,
    availableCorrespondents: List<Correspondent> = emptyList(),
    availableDocumentTypes: List<DocumentType> = emptyList(),
    onDismiss: () -> Unit,
    onApply: (DocumentFilter) -> Unit
) {
    // Local state for filter editing
    var editingFilter by remember(currentFilter) {
        mutableStateOf(currentFilter)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { SheetDragHandle() }
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.FilterList,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.filter_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.close),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Active Filters Count
            item {
                val activeCount = editingFilter.activeFilterCount()
                if (activeCount > 0) {
                    Text(
                        text = stringResource(R.string.filter_active_count, activeCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Section: Tags (Multi-Select with Autocomplete)
            item {
                FilterSection(title = stringResource(R.string.filter_section_tags)) {
                    if (availableTags.isEmpty()) {
                        Text(
                            text = stringResource(R.string.filter_no_tags),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        TagAutocomplete(
                            allTags = availableTags,
                            selectedTagIds = editingFilter.tagIds,
                            onTagsChanged = { tagIds ->
                                editingFilter = editingFilter.copy(tagIds = tagIds)
                            }
                        )
                    }
                }
            }

            // Section: Correspondent
            if (availableCorrespondents.isNotEmpty()) {
                item {
                    FilterSection(title = stringResource(R.string.filter_section_correspondent)) {
                        EntityDropdown(
                            items = availableCorrespondents,
                            selectedId = editingFilter.correspondentId,
                            onSelect = { editingFilter = editingFilter.copy(correspondentId = it) },
                            itemLabel = { it.name },
                            placeholder = stringResource(R.string.filter_correspondent_placeholder)
                        )
                    }
                }
            }

            // Section: Document Type
            if (availableDocumentTypes.isNotEmpty()) {
                item {
                    FilterSection(title = stringResource(R.string.filter_section_document_type)) {
                        EntityDropdown(
                            items = availableDocumentTypes,
                            selectedId = editingFilter.documentTypeId,
                            onSelect = { editingFilter = editingFilter.copy(documentTypeId = it) },
                            itemLabel = { it.name },
                            placeholder = stringResource(R.string.filter_document_type_placeholder)
                        )
                    }
                }
            }

            // Section: Archive Status
            item {
                FilterSection(title = stringResource(R.string.filter_section_archive_status)) {
                    ArchiveStatusSelector(
                        hasArchiveSerialNumber = editingFilter.hasArchiveSerialNumber,
                        onSelect = { editingFilter = editingFilter.copy(hasArchiveSerialNumber = it) }
                    )
                }
            }

            // Section: Created Date Range
            item {
                DateRangePickerField(
                    label = stringResource(R.string.filter_section_created_date),
                    startDate = editingFilter.createdDateFrom,
                    endDate = editingFilter.createdDateTo,
                    onDateRangeSelected = { start, end ->
                        editingFilter = editingFilter.copy(
                            createdDateFrom = start,
                            createdDateTo = end
                        )
                    }
                )
            }

            // Section: Added Date Range
            item {
                DateRangePickerField(
                    label = stringResource(R.string.filter_section_added_date),
                    startDate = editingFilter.addedDateFrom,
                    endDate = editingFilter.addedDateTo,
                    onDateRangeSelected = { start, end ->
                        editingFilter = editingFilter.copy(
                            addedDateFrom = start,
                            addedDateTo = end
                        )
                    }
                )
            }

            // Section: Modified Date Range
            item {
                DateRangePickerField(
                    label = stringResource(R.string.filter_section_modified_date),
                    startDate = editingFilter.modifiedDateFrom,
                    endDate = editingFilter.modifiedDateTo,
                    onDateRangeSelected = { start, end ->
                        editingFilter = editingFilter.copy(
                            modifiedDateFrom = start,
                            modifiedDateTo = end
                        )
                    }
                )
            }

            // Section: Sort
            item {
                FilterSection(title = stringResource(R.string.filter_section_sort)) {
                    SortDropdown(
                        currentSort = editingFilter.sortBy,
                        currentOrder = editingFilter.sortOrder,
                        onSortChanged = { sortBy, sortOrder ->
                            editingFilter = editingFilter.copy(
                                sortBy = sortBy,
                                sortOrder = sortOrder
                            )
                        }
                    )
                }
            }

            // Action Buttons
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            editingFilter = DocumentFilter.empty()
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.filter_clear_all))
                    }

                    Button(
                        onClick = {
                            onApply(editingFilter)
                            onDismiss()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(stringResource(R.string.filter_apply))
                    }
                }
            }

            // Bottom Spacing
            item { Spacer(modifier = Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun SheetDragHandle() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(32.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
        )
    }
}

@Composable
private fun FilterSection(
    title: String,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun <T : Any> SelectableList(
    items: List<T>,
    selectedId: Int?,
    onSelect: (Int?) -> Unit,
    onClear: () -> Unit,
    itemLabel: (T) -> String
) {
    // Assuming items have an 'id' property (Tag, Correspondent, DocumentType all have it)
    val getItemId: (T) -> Int = { item ->
        when (item) {
            is Tag -> item.id
            is Correspondent -> item.id
            is DocumentType -> item.id
            else -> 0
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // Clear button
        if (selectedId != null) {
            OutlinedButton(
                onClick = onClear,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.filter_clear_selection))
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Item list (max 5 items visible, then scroll)
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items.take(10).forEach { item ->
                val itemId = getItemId(item)
                val isSelected = selectedId == itemId

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                        )
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = isSelected,
                        onClick = {
                            onSelect(if (isSelected) null else itemId)
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = itemLabel(item),
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            if (items.size > 10) {
                Text(
                    text = stringResource(R.string.filter_more_items, items.size - 10),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun ArchiveStatusSelector(
    hasArchiveSerialNumber: Boolean?,
    onSelect: (Boolean?) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // All documents
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (hasArchiveSerialNumber == null) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = hasArchiveSerialNumber == null,
                onClick = { onSelect(null) }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.filter_archive_all),
                style = MaterialTheme.typography.bodyMedium,
                color = if (hasArchiveSerialNumber == null) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface
            )
        }

        // Only with ASN
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (hasArchiveSerialNumber == true) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = hasArchiveSerialNumber == true,
                onClick = { onSelect(true) }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.filter_archive_with_asn),
                style = MaterialTheme.typography.bodyMedium,
                color = if (hasArchiveSerialNumber == true) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface
            )
        }

        // Only without ASN
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (hasArchiveSerialNumber == false) MaterialTheme.colorScheme.primaryContainer
                    else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            RadioButton(
                selected = hasArchiveSerialNumber == false,
                onClick = { onSelect(false) }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.filter_archive_without_asn),
                style = MaterialTheme.typography.bodyMedium,
                color = if (hasArchiveSerialNumber == false) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T : Any> EntityDropdown(
    items: List<T>,
    selectedId: Int?,
    onSelect: (Int?) -> Unit,
    itemLabel: (T) -> String,
    placeholder: String
) {
    // Find selected item
    val getItemId: (T) -> Int = { item ->
        when (item) {
            is Correspondent -> item.id
            is DocumentType -> item.id
            else -> 0
        }
    }

    val selectedItem = items.firstOrNull { getItemId(it) == selectedId }
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selectedItem?.let { itemLabel(it) } ?: "",
            onValueChange = {},
            readOnly = true,
            placeholder = {
                Text(
                    text = placeholder,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingIcon = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Clear button (only show when item selected)
                    if (selectedItem != null) {
                        IconButton(
                            onClick = {
                                onSelect(null)
                                expanded = false
                            },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = stringResource(R.string.filter_clear_selection),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            ),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            items.forEach { item ->
                val itemId = getItemId(item)
                val isSelected = selectedId == itemId

                DropdownMenuItem(
                    text = {
                        Text(
                            text = itemLabel(item),
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    onClick = {
                        onSelect(if (isSelected) null else itemId)
                        expanded = false
                    },
                    leadingIcon = if (isSelected) {
                        {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else null
                )
            }
        }
    }
}

/**
 * Dropdown for selecting document sort field and order.
 *
 * Displays all 10 sort options (5 fields x 2 directions) with check icon for selected option.
 * No clear button - a sort option is always selected (default: ADDED DESC).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SortDropdown(
    currentSort: DocumentSortField,
    currentOrder: SortOrder,
    onSortChanged: (DocumentSortField, SortOrder) -> Unit,
    modifier: Modifier = Modifier
) {
    // All 10 sort options as pairs
    val sortOptions = listOf(
        DocumentSortField.ADDED to SortOrder.DESC,
        DocumentSortField.ADDED to SortOrder.ASC,
        DocumentSortField.CREATED to SortOrder.DESC,
        DocumentSortField.CREATED to SortOrder.ASC,
        DocumentSortField.TITLE to SortOrder.ASC,
        DocumentSortField.TITLE to SortOrder.DESC,
        DocumentSortField.MODIFIED to SortOrder.DESC,
        DocumentSortField.MODIFIED to SortOrder.ASC,
        DocumentSortField.ASN to SortOrder.ASC,
        DocumentSortField.ASN to SortOrder.DESC
    )

    // Map current sort to its string resource
    val currentLabel = when (currentSort to currentOrder) {
        DocumentSortField.ADDED to SortOrder.DESC -> stringResource(R.string.sort_added_desc)
        DocumentSortField.ADDED to SortOrder.ASC -> stringResource(R.string.sort_added_asc)
        DocumentSortField.CREATED to SortOrder.DESC -> stringResource(R.string.sort_created_desc)
        DocumentSortField.CREATED to SortOrder.ASC -> stringResource(R.string.sort_created_asc)
        DocumentSortField.TITLE to SortOrder.ASC -> stringResource(R.string.sort_title_asc)
        DocumentSortField.TITLE to SortOrder.DESC -> stringResource(R.string.sort_title_desc)
        DocumentSortField.MODIFIED to SortOrder.DESC -> stringResource(R.string.sort_modified_desc)
        DocumentSortField.MODIFIED to SortOrder.ASC -> stringResource(R.string.sort_modified_asc)
        DocumentSortField.ASN to SortOrder.ASC -> stringResource(R.string.sort_asn_asc)
        DocumentSortField.ASN to SortOrder.DESC -> stringResource(R.string.sort_asn_desc)
        else -> ""
    }
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = currentLabel,
            onValueChange = {},
            readOnly = true,
            placeholder = {
                Text(
                    text = stringResource(R.string.sort_placeholder),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outline
            ),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )

        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            sortOptions.forEach { (field, order) ->
                val isSelected = currentSort == field && currentOrder == order
                val label = when (field to order) {
                    DocumentSortField.ADDED to SortOrder.DESC -> stringResource(R.string.sort_added_desc)
                    DocumentSortField.ADDED to SortOrder.ASC -> stringResource(R.string.sort_added_asc)
                    DocumentSortField.CREATED to SortOrder.DESC -> stringResource(R.string.sort_created_desc)
                    DocumentSortField.CREATED to SortOrder.ASC -> stringResource(R.string.sort_created_asc)
                    DocumentSortField.TITLE to SortOrder.ASC -> stringResource(R.string.sort_title_asc)
                    DocumentSortField.TITLE to SortOrder.DESC -> stringResource(R.string.sort_title_desc)
                    DocumentSortField.MODIFIED to SortOrder.DESC -> stringResource(R.string.sort_modified_desc)
                    DocumentSortField.MODIFIED to SortOrder.ASC -> stringResource(R.string.sort_modified_asc)
                    DocumentSortField.ASN to SortOrder.ASC -> stringResource(R.string.sort_asn_asc)
                    DocumentSortField.ASN to SortOrder.DESC -> stringResource(R.string.sort_asn_desc)
                    else -> ""
                }

                DropdownMenuItem(
                    text = {
                        Text(
                            text = label,
                            color = if (isSelected) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface
                        )
                    },
                    onClick = {
                        onSortChanged(field, order)
                        expanded = false
                    },
                    leadingIcon = if (isSelected) {
                        {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    } else null
                )
            }
        }
    }
}
