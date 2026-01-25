package com.paperless.scanner.ui.screens.labels

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.paperless.scanner.R

/**
 * Material 3 Card for displaying any entity type (Tag, Correspondent, DocumentType, CustomField).
 * Shows conditional UI based on entityType:
 * - Tags: Color dot indicator
 * - Correspondents: Person icon
 * - Document Types: Description icon
 * - Custom Fields: DataObject icon
 *
 * @param entity The entity to display
 * @param onClick Callback when card is clicked
 * @param onEdit Callback when edit button is clicked
 * @param onDelete Callback when delete button is clicked
 */
@Composable
fun EntityCard(
    entity: EntityItem,
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
            // Conditional: Color dot for Tags, Icon for others
            if (entity.entityType == EntityType.TAG && entity.color != null) {
                // Color dot indicator for Tags
                Box(
                    modifier = Modifier
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(entity.color)
                )
            } else {
                // Icon for other entity types
                Icon(
                    imageVector = when (entity.entityType) {
                        EntityType.CORRESPONDENT -> Icons.Default.Person
                        EntityType.DOCUMENT_TYPE -> Icons.Default.Description
                        EntityType.CUSTOM_FIELD -> Icons.Default.DataObject
                        EntityType.TAG -> Icons.Default.Label // Fallback if no color
                    },
                    contentDescription = when (entity.entityType) {
                        EntityType.CORRESPONDENT -> stringResource(R.string.entity_type_correspondents)
                        EntityType.DOCUMENT_TYPE -> stringResource(R.string.entity_type_document_types)
                        EntityType.CUSTOM_FIELD -> stringResource(R.string.entity_type_custom_fields)
                        EntityType.TAG -> stringResource(R.string.entity_type_tags)
                    },
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Entity info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = entity.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = stringResource(R.string.labels_document_count, entity.documentCount),
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
