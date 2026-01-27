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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
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
import com.paperless.scanner.domain.model.DocumentType
import com.paperless.scanner.domain.model.Tag
import com.paperless.scanner.ui.components.TagAutocomplete

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
                        SelectableList(
                            items = availableCorrespondents,
                            selectedId = editingFilter.correspondentId,
                            onSelect = { editingFilter = editingFilter.copy(correspondentId = it) },
                            onClear = { editingFilter = editingFilter.copy(correspondentId = null) },
                            itemLabel = { it.name }
                        )
                    }
                }
            }

            // Section: Document Type
            if (availableDocumentTypes.isNotEmpty()) {
                item {
                    FilterSection(title = stringResource(R.string.filter_section_document_type)) {
                        SelectableList(
                            items = availableDocumentTypes,
                            selectedId = editingFilter.documentTypeId,
                            onSelect = { editingFilter = editingFilter.copy(documentTypeId = it) },
                            onClear = { editingFilter = editingFilter.copy(documentTypeId = null) },
                            itemLabel = { it.name }
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
