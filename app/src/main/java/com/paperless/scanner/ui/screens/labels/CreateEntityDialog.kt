package com.paperless.scanner.ui.screens.labels

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.paperless.scanner.R

/**
 * Unified create/edit dialog for all entity types.
 * Shows conditional UI based on entityType:
 * - Tags: Name + Color Picker
 * - Correspondents: Name only
 * - Document Types: Name only
 * - Custom Fields: Name + Data Type Selector
 *
 * @param entityType The type of entity to create/edit
 * @param existingEntity Existing entity to edit (null for create)
 * @param isCreating Loading state for create/update operation
 * @param onDismiss Callback when dialog is dismissed
 * @param onCreate Callback when entity is created/updated (name, color, dataType)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateEntityDialog(
    entityType: EntityType,
    existingEntity: EntityItem? = null,
    isCreating: Boolean = false,
    onDismiss: () -> Unit,
    onCreate: (name: String, color: Color?, dataType: String?) -> Unit
) {
    var name by remember { mutableStateOf(existingEntity?.name ?: "") }
    var selectedColor by remember {
        mutableStateOf(existingEntity?.color ?: labelColorOptions.first())
    }
    var selectedDataType by remember {
        mutableStateOf(existingEntity?.dataType ?: "string")
    }

    val isEditing = existingEntity != null

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp)
            ) {
                // Title
                Text(
                    text = if (isEditing) {
                        when (entityType) {
                            EntityType.TAG -> stringResource(R.string.labels_edit_title)
                            EntityType.CORRESPONDENT -> stringResource(R.string.entity_edit_correspondent)
                            EntityType.DOCUMENT_TYPE -> stringResource(R.string.entity_edit_document_type)
                            EntityType.CUSTOM_FIELD -> stringResource(R.string.entity_edit_custom_field)
                        }
                    } else {
                        when (entityType) {
                            EntityType.TAG -> stringResource(R.string.labels_create_title)
                            EntityType.CORRESPONDENT -> stringResource(R.string.entity_create_correspondent)
                            EntityType.DOCUMENT_TYPE -> stringResource(R.string.entity_create_document_type)
                            EntityType.CUSTOM_FIELD -> stringResource(R.string.entity_create_custom_field)
                        }
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Name Input
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
                    placeholder = {
                        Text(
                            when (entityType) {
                                EntityType.TAG -> stringResource(R.string.labels_name_placeholder)
                                EntityType.CORRESPONDENT -> stringResource(R.string.entity_correspondent_placeholder)
                                EntityType.DOCUMENT_TYPE -> stringResource(R.string.entity_document_type_placeholder)
                                EntityType.CUSTOM_FIELD -> stringResource(R.string.entity_custom_field_placeholder)
                            }
                        )
                    },
                    shape = RoundedCornerShape(8.dp),
                    singleLine = true,
                    enabled = !isCreating
                )

                // Conditional: Color Picker for Tags
                if (entityType == EntityType.TAG) {
                    Spacer(modifier = Modifier.height(20.dp))
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
                            ColorOption(
                                color = color,
                                isSelected = selectedColor == color,
                                onClick = { selectedColor = color },
                                enabled = !isCreating
                            )
                        }
                    }
                }

                // Conditional: Data Type Selector for Custom Fields
                if (entityType == EntityType.CUSTOM_FIELD && !isEditing) {
                    Spacer(modifier = Modifier.height(20.dp))
                    Text(
                        text = stringResource(R.string.entity_data_type_label),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    var expanded by remember { mutableStateOf(false) }
                    val dataTypes = listOf(
                        "string" to stringResource(R.string.entity_data_type_string),
                        "integer" to stringResource(R.string.entity_data_type_integer),
                        "float" to stringResource(R.string.entity_data_type_float),
                        "monetary" to stringResource(R.string.entity_data_type_monetary),
                        "date" to stringResource(R.string.entity_data_type_date),
                        "boolean" to stringResource(R.string.entity_data_type_boolean)
                    )

                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { if (!isCreating) expanded = it }
                    ) {
                        OutlinedTextField(
                            value = dataTypes.find { it.first == selectedDataType }?.second ?: "",
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(),
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                            },
                            shape = RoundedCornerShape(8.dp),
                            enabled = !isCreating
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            dataTypes.forEach { (value, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        selectedDataType = value
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Action Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(
                        onClick = onDismiss,
                        enabled = !isCreating
                    ) {
                        Text(stringResource(R.string.labels_cancel_button))
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Button(
                        onClick = {
                            val color = if (entityType == EntityType.TAG) selectedColor else null
                            val dataType = if (entityType == EntityType.CUSTOM_FIELD) selectedDataType else null
                            onCreate(name, color, dataType)
                        },
                        enabled = !isCreating && name.isNotBlank()
                    ) {
                        if (isCreating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            if (isEditing) {
                                stringResource(R.string.labels_save_button)
                            } else {
                                stringResource(R.string.labels_create_button)
                            }
                        )
                    }
                }
            }
        }
    }
}

/**
 * Color option card for color picker.
 */
@Composable
private fun RowScope.ColorOption(
    color: Color,
    isSelected: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .weight(1f)
            .aspectRatio(1f),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = color),
        border = BorderStroke(
            width = if (isSelected) 3.dp else 1.dp,
            color = if (isSelected) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.outline
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        enabled = enabled
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            if (isSelected) {
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
