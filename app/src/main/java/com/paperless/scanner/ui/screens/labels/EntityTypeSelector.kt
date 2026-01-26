package com.paperless.scanner.ui.screens.labels

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.paperless.scanner.R
import kotlinx.coroutines.launch

/**
 * Trigger button that opens a BottomSheet to select entity type.
 * Displays current selection with icon and dropdown chevron.
 *
 * @param selectedType Currently selected entity type
 * @param customFieldsAvailable Whether Custom Fields are available
 * @param onTypeSelected Callback when entity type is selected
 * @param modifier Optional modifier
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EntityTypeSelector(
    selectedType: EntityType,
    customFieldsAvailable: Boolean,
    onTypeSelected: (EntityType) -> Unit,
    modifier: Modifier = Modifier
) {
    var showSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    // Trigger Button
    Card(
        onClick = { showSheet = true },
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Icon + Current Selection
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Filter Icon (neutral trigger indicator)
                Icon(
                    imageVector = Icons.Default.FilterList,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )

                // Current Selection
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = getEntityTypeIcon(selectedType),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = getEntityTypeName(selectedType),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }

            // Right: Dropdown Chevron
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = stringResource(R.string.cd_expand),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    // BottomSheet with Card-Style Options
    if (showSheet) {
        EntityTypeSelectorSheet(
            sheetState = sheetState,
            selectedType = selectedType,
            customFieldsAvailable = customFieldsAvailable,
            onTypeSelected = { type ->
                onTypeSelected(type)
                scope.launch {
                    sheetState.hide()
                    showSheet = false
                }
            },
            onDismiss = {
                scope.launch {
                    sheetState.hide()
                    showSheet = false
                }
            }
        )
    }
}

/**
 * BottomSheet with card-style entity type options.
 * Shows each option as a card with icon, name, and optional entry count.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun EntityTypeSelectorSheet(
    sheetState: SheetState,
    selectedType: EntityType,
    customFieldsAvailable: Boolean,
    onTypeSelected: (EntityType) -> Unit,
    onDismiss: () -> Unit
) {
    val availableTypes = remember(customFieldsAvailable) {
        buildList {
            add(EntityType.TAG)
            add(EntityType.CORRESPONDENT)
            add(EntityType.DOCUMENT_TYPE)
            if (customFieldsAvailable) {
                add(EntityType.CUSTOM_FIELD)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.background
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
        ) {
            // Header
            Text(
                text = stringResource(R.string.entity_selector_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Text(
                text = stringResource(R.string.entity_selector_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 24.dp)
            )

            // Options as Cards
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 8.dp)
            ) {
                items(availableTypes) { type ->
                    EntityTypeOptionCard(
                        entityType = type,
                        isSelected = type == selectedType,
                        onClick = { onTypeSelected(type) }
                    )
                }
            }
        }
    }
}

/**
 * Individual card option for entity type selection.
 * Displays icon, name, and selection indicator.
 */
@Composable
private fun EntityTypeOptionCard(
    entityType: EntityType,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        border = BorderStroke(
            width = if (isSelected) 2.dp else 1.dp,
            color = if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.outline
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Left: Icon + Info
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Icon in colored background
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .background(
                            color = if (isSelected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                            shape = RoundedCornerShape(12.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = getEntityTypeIcon(entityType),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = if (isSelected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                // Name and Description
                Column {
                    Text(
                        text = getEntityTypeName(entityType),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
                    )
                    Text(
                        text = getEntityTypeDescription(entityType),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            // Right: Checkmark if selected
            if (isSelected) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(8.dp)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = stringResource(R.string.cd_selected),
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
            }
        }
    }
}

/**
 * Returns the icon for a given entity type.
 */
private fun getEntityTypeIcon(entityType: EntityType): ImageVector {
    return when (entityType) {
        EntityType.TAG -> Icons.Default.Label
        EntityType.CORRESPONDENT -> Icons.Default.Person
        EntityType.DOCUMENT_TYPE -> Icons.Default.Description
        EntityType.CUSTOM_FIELD -> Icons.Default.DataObject
    }
}

/**
 * Get localized name for entity type.
 */
@Composable
private fun getEntityTypeName(entityType: EntityType): String {
    return when (entityType) {
        EntityType.TAG -> stringResource(R.string.entity_type_tags)
        EntityType.CORRESPONDENT -> stringResource(R.string.entity_type_correspondents)
        EntityType.DOCUMENT_TYPE -> stringResource(R.string.entity_type_document_types)
        EntityType.CUSTOM_FIELD -> stringResource(R.string.entity_type_custom_fields)
    }
}

/**
 * Get localized description for entity type.
 */
@Composable
private fun getEntityTypeDescription(entityType: EntityType): String {
    return when (entityType) {
        EntityType.TAG -> stringResource(R.string.entity_description_tags)
        EntityType.CORRESPONDENT -> stringResource(R.string.entity_description_correspondents)
        EntityType.DOCUMENT_TYPE -> stringResource(R.string.entity_description_document_types)
        EntityType.CUSTOM_FIELD -> stringResource(R.string.entity_description_custom_fields)
    }
}
