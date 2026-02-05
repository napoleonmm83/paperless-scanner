package com.paperless.scanner.ui.screens.upload.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.paperless.scanner.R
import com.paperless.scanner.data.api.models.CustomField
import com.paperless.scanner.domain.model.Correspondent
import com.paperless.scanner.domain.model.DocumentType
import com.paperless.scanner.domain.model.Tag

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DocumentTypeDropdown(
    documentTypes: List<DocumentType>,
    selectedId: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.upload_document_type),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = documentTypes.find { it.id == selectedId }?.name ?: stringResource(R.string.upload_not_selected),
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.upload_not_selected)) },
                    onClick = {
                        onSelect(null)
                        expanded = false
                    }
                )
                documentTypes.forEach { docType ->
                    DropdownMenuItem(
                        text = { Text(docType.name) },
                        onClick = {
                            onSelect(docType.id)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CorrespondentDropdown(
    correspondents: List<Correspondent>,
    selectedId: Int?,
    onSelect: (Int?) -> Unit,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Text(
            text = stringResource(R.string.upload_correspondent),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            OutlinedTextField(
                value = correspondents.find { it.id == selectedId }?.name ?: stringResource(R.string.upload_not_selected),
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth()
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.upload_not_selected)) },
                    onClick = {
                        onSelect(null)
                        expanded = false
                    }
                )
                correspondents.forEach { correspondent ->
                    DropdownMenuItem(
                        text = { Text(correspondent.name) },
                        onClick = {
                            onSelect(correspondent.id)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

private const val MAX_VISIBLE_TAGS = 8

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagSelectionSection(
    tags: List<Tag>,
    selectedTagIds: Set<Int>,
    onToggleTag: (Int) -> Unit,
    onCreateNew: () -> Unit,
    modifier: Modifier = Modifier,
    isPremiumActive: Boolean = false,
    onUpgradeToPremium: () -> Unit = {}
) {
    var showAllTags by remember { mutableStateOf(false) }

    // Sort tags: selected first, then alphabetically
    val sortedTags = tags.sortedWith(
        compareByDescending<Tag> { selectedTagIds.contains(it.id) }
            .thenBy { it.name.lowercase() }
    )

    val visibleTags = if (showAllTags) sortedTags else sortedTags.take(MAX_VISIBLE_TAGS)
    val hiddenCount = sortedTags.size - MAX_VISIBLE_TAGS
    val hasMoreTags = hiddenCount > 0

    Column(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.upload_tags_section_title),
                style = MaterialTheme.typography.titleMedium
            )
            if (tags.size > MAX_VISIBLE_TAGS) {
                Text(
                    text = stringResource(R.string.upload_tags_count, tags.size),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        FlowRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // "New" button
            AssistChip(
                onClick = onCreateNew,
                label = { Text(stringResource(R.string.upload_tag_new)) },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = stringResource(R.string.cd_create_tag),
                        modifier = Modifier.size(AssistChipDefaults.IconSize)
                    )
                }
            )

            // AI Tagging Upgrade Chip (only when Premium is NOT active)
            if (!isPremiumActive) {
                AssistChip(
                    onClick = onUpgradeToPremium,
                    label = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(AssistChipDefaults.IconSize)
                            )
                            Text(stringResource(R.string.upload_ai_tagging))
                            Box(
                                modifier = Modifier
                                    .background(
                                        color = MaterialTheme.colorScheme.primary,
                                        shape = RoundedCornerShape(4.dp)
                                    )
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.premium_badge),
                                    style = MaterialTheme.typography.labelSmall.copy(
                                        fontSize = MaterialTheme.typography.labelSmall.fontSize * 0.8f
                                    ),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                )
            }

            // Visible tags
            visibleTags.forEach { tag ->
                val isSelected = selectedTagIds.contains(tag.id)
                FilterChip(
                    selected = isSelected,
                    onClick = { onToggleTag(tag.id) },
                    label = { Text(tag.name) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primary,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimary
                    )
                )
            }

            // "Show more/less" chip
            if (hasMoreTags) {
                AssistChip(
                    onClick = { showAllTags = !showAllTags },
                    label = {
                        Text(
                            if (showAllTags) {
                                stringResource(R.string.upload_tags_show_less)
                            } else {
                                stringResource(R.string.upload_show_more_tags, hiddenCount)
                            }
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = if (showAllTags) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = null,
                            modifier = Modifier.size(AssistChipDefaults.IconSize)
                        )
                    }
                )
            }
        }
    }
}

/**
 * Section for Custom Fields input in Upload Screen.
 * Supports various field types: string, integer, monetary, date, url, boolean.
 * Collapsible section to reduce visual clutter when not needed.
 */
@Composable
fun CustomFieldsSection(
    customFields: List<CustomField>,
    customFieldValues: Map<Int, String>,
    onFieldValueChange: (fieldId: Int, value: String?) -> Unit,
    modifier: Modifier = Modifier
) {
    // Don't show section if no custom fields available
    if (customFields.isEmpty()) {
        return
    }

    var isExpanded by remember { mutableStateOf(false) }
    val filledFieldsCount = customFieldValues.count { it.value.isNotBlank() }

    Column(modifier = modifier) {
        // Header with expand/collapse
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isExpanded = !isExpanded }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.custom_fields_section),
                    style = MaterialTheme.typography.titleMedium
                )
                // Show count of filled fields if any
                if (filledFieldsCount > 0) {
                    Box(
                        modifier = Modifier
                            .background(
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(12.dp)
                            )
                            .padding(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = filledFieldsCount.toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "${customFields.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Expandable content
        if (isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                customFields.forEach { field ->
                    CustomFieldInput(
                        field = field,
                        value = customFieldValues[field.id] ?: "",
                        onValueChange = { value ->
                            onFieldValueChange(field.id, value.ifBlank { null })
                        }
                    )
                }
            }
        }
    }
}

/**
 * Individual custom field input based on data type.
 */
@Composable
private fun CustomFieldInput(
    field: CustomField,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    when (field.dataType?.lowercase()) {
        "boolean" -> {
            // Boolean: Switch
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = field.name,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Switch(
                    checked = value.lowercase() == "true",
                    onCheckedChange = { checked ->
                        onValueChange(if (checked) "true" else "false")
                    }
                )
            }
        }
        "integer", "float" -> {
            // Number: TextField with number keyboard
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(field.name) },
                placeholder = { Text(stringResource(R.string.custom_field_number_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = modifier.fillMaxWidth()
            )
        }
        "monetary" -> {
            // Monetary: TextField with decimal keyboard
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(field.name) },
                placeholder = { Text(stringResource(R.string.custom_field_number_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = modifier.fillMaxWidth()
            )
        }
        "url" -> {
            // URL: TextField with URL keyboard
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(field.name) },
                placeholder = { Text(stringResource(R.string.custom_field_url_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = modifier.fillMaxWidth()
            )
        }
        "date" -> {
            // Date: Simple text input for now (YYYY-MM-DD format)
            // TODO: Add DatePicker dialog in future enhancement
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(field.name) },
                placeholder = { Text(stringResource(R.string.custom_field_date_hint)) },
                singleLine = true,
                supportingText = { Text(stringResource(R.string.custom_field_date_format)) },
                modifier = modifier.fillMaxWidth()
            )
        }
        else -> {
            // Default: String/Text input
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                label = { Text(field.name) },
                placeholder = { Text(stringResource(R.string.custom_field_text_hint)) },
                singleLine = true,
                modifier = modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
fun UploadErrorCard(
    errorMessage: String,
    canRetry: Boolean,
    onRetry: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = stringResource(R.string.cd_warning),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(48.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.upload_failed),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (canRetry) {
                    Button(onClick = onRetry) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.cd_refresh),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(stringResource(R.string.upload_retry_button))
                    }
                }

                Button(onClick = onDismiss) {
                    Text(stringResource(R.string.upload_close))
                }
            }
        }
    }
}

@Composable
fun UploadProgressIndicator(
    progress: Float,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (progress > 0f) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.upload_progress_percent, (progress * 100).toInt()),
                style = MaterialTheme.typography.bodyMedium
            )
        } else {
            CircularProgressIndicator()
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.upload_uploading_document),
            style = MaterialTheme.typography.titleMedium
        )
    }
}
