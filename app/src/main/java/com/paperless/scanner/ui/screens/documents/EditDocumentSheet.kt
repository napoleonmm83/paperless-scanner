package com.paperless.scanner.ui.screens.documents

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paperless.scanner.R
import com.paperless.scanner.data.ai.models.DocumentAnalysis
import com.paperless.scanner.data.ai.models.SuggestionSource
import com.paperless.scanner.data.ai.models.TagSuggestion
import com.paperless.scanner.domain.model.Correspondent
import com.paperless.scanner.domain.model.DocumentType
import com.paperless.scanner.domain.model.Tag
import com.paperless.scanner.ui.screens.upload.AnalysisState
import com.paperless.scanner.ui.screens.upload.components.SuggestionsSection
import com.paperless.scanner.ui.screens.upload.components.TagSelectionSection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditDocumentSheet(
    title: String,
    selectedTagIds: List<Int>,
    selectedCorrespondentId: Int?,
    selectedDocumentTypeId: Int?,
    archiveSerialNumber: Int?,
    availableTags: List<Tag>,
    availableCorrespondents: List<Correspondent>,
    availableDocumentTypes: List<DocumentType>,
    isUpdating: Boolean,
    newlyCreatedTagId: Int? = null,
    // AI Suggestions
    isAiAvailable: Boolean = false,
    analysisState: AnalysisState = AnalysisState.Idle,
    aiSuggestions: DocumentAnalysis? = null,
    suggestionSource: SuggestionSource? = null,
    wifiRequired: Boolean = false,
    isWifiConnected: Boolean = true,
    aiNewTagsEnabled: Boolean = true,
    onAnalyzeClick: () -> Unit = {},
    onOverrideWifiOnly: () -> Unit = {},
    onApplyTagSuggestion: (TagSuggestion) -> Unit = {},
    onAiNewTagsEnabledChange: (Boolean) -> Unit = {},
    onCreateNewTag: () -> Unit,
    onSave: (
        title: String,
        tagIds: List<Int>,
        correspondentId: Int?,
        documentTypeId: Int?,
        archiveSerialNumber: String?
    ) -> Unit
) {
    var editedTitle by remember { mutableStateOf(title) }
    var editedTagIds by remember { mutableStateOf(selectedTagIds) }

    // Add newly created tag to selection
    LaunchedEffect(newlyCreatedTagId) {
        if (newlyCreatedTagId != null && !editedTagIds.contains(newlyCreatedTagId)) {
            editedTagIds = editedTagIds + newlyCreatedTagId
        }
    }
    var editedCorrespondentId by remember { mutableStateOf(selectedCorrespondentId) }
    var editedDocumentTypeId by remember { mutableStateOf(selectedDocumentTypeId) }
    var editedAsn by remember { mutableStateOf(archiveSerialNumber?.toString() ?: "") }

    var correspondentExpanded by remember { mutableStateOf(false) }
    var documentTypeExpanded by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Text(
            text = stringResource(R.string.document_edit_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Title Field
        Text(
            text = stringResource(R.string.document_edit_title_label),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = editedTitle,
            onValueChange = { editedTitle = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.document_edit_title_label)) },
            shape = RoundedCornerShape(8.dp),
            singleLine = true,
            enabled = !isUpdating
        )

        Spacer(modifier = Modifier.height(20.dp))

        // WiFi Banner - shown when AI requires WiFi but device is not connected
        if (wifiRequired && !isWifiConnected) {
            WifiRequiredBanner(
                onUseAnywayClick = onOverrideWifiOnly
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

        // AI Suggestions Section - Only shown when AI is available
        if (isAiAvailable) {
            SuggestionsSection(
                analysisState = analysisState,
                suggestions = aiSuggestions,
                suggestionSource = suggestionSource,
                existingTags = availableTags,
                selectedTagIds = editedTagIds.toSet(),
                currentTitle = editedTitle,
                aiNewTagsEnabled = aiNewTagsEnabled,
                onAnalyzeClick = onAnalyzeClick,
                onAiNewTagsEnabledChange = onAiNewTagsEnabledChange,
                onApplyTagSuggestion = { tagSuggestion ->
                    // CRITICAL: Always verify tag exists in local list, even if tagId is provided
                    // AI might return invalid tagIds that don't exist on the server
                    val existingTag = availableTags.find {
                        // Match by ID if provided AND exists in local list
                        (tagSuggestion.tagId != null && it.id == tagSuggestion.tagId) ||
                        // Otherwise match by name (case-insensitive)
                        it.name.equals(tagSuggestion.tagName, ignoreCase = true)
                    }

                    if (existingTag != null) {
                        if (!editedTagIds.contains(existingTag.id)) {
                            editedTagIds = editedTagIds + existingTag.id
                        }
                    } else {
                        onApplyTagSuggestion(tagSuggestion)
                    }
                },
                onApplyTitle = { suggestedTitle ->
                    editedTitle = suggestedTitle
                }
            )

            Spacer(modifier = Modifier.height(20.dp))
        }

        // Tags Selection with create new functionality
        TagSelectionSection(
            tags = availableTags,
            selectedTagIds = editedTagIds.toSet(),
            onToggleTag = { tagId ->
                editedTagIds = if (editedTagIds.contains(tagId)) {
                    editedTagIds - tagId
                } else {
                    editedTagIds + tagId
                }
            },
            onCreateNew = onCreateNewTag
        )

        Spacer(modifier = Modifier.height(20.dp))

        // Correspondent Dropdown
        Text(
            text = stringResource(R.string.document_edit_correspondent_label),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(8.dp))

        ExposedDropdownMenuBox(
            expanded = correspondentExpanded && !isUpdating,
            onExpandedChange = { if (!isUpdating) correspondentExpanded = it }
        ) {
            OutlinedTextField(
                value = editedCorrespondentId?.let { id ->
                    availableCorrespondents.find { it.id == id }?.name
                } ?: stringResource(R.string.document_edit_no_correspondent),
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = correspondentExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                enabled = !isUpdating,
                shape = RoundedCornerShape(8.dp)
            )

            ExposedDropdownMenu(
                expanded = correspondentExpanded && !isUpdating,
                onDismissRequest = { correspondentExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.document_edit_no_correspondent)) },
                    onClick = {
                        editedCorrespondentId = null
                        correspondentExpanded = false
                    }
                )
                availableCorrespondents.forEach { correspondent ->
                    DropdownMenuItem(
                        text = { Text(correspondent.name) },
                        onClick = {
                            editedCorrespondentId = correspondent.id
                            correspondentExpanded = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Document Type Dropdown
        Text(
            text = stringResource(R.string.document_edit_document_type_label),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium
        )

        Spacer(modifier = Modifier.height(8.dp))

        ExposedDropdownMenuBox(
            expanded = documentTypeExpanded && !isUpdating,
            onExpandedChange = { if (!isUpdating) documentTypeExpanded = it }
        ) {
            OutlinedTextField(
                value = editedDocumentTypeId?.let { id ->
                    availableDocumentTypes.find { it.id == id }?.name
                } ?: stringResource(R.string.document_edit_no_document_type),
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = documentTypeExpanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
                enabled = !isUpdating,
                shape = RoundedCornerShape(8.dp)
            )

            ExposedDropdownMenu(
                expanded = documentTypeExpanded && !isUpdating,
                onDismissRequest = { documentTypeExpanded = false }
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.document_edit_no_document_type)) },
                    onClick = {
                        editedDocumentTypeId = null
                        documentTypeExpanded = false
                    }
                )
                availableDocumentTypes.forEach { docType ->
                    DropdownMenuItem(
                        text = { Text(docType.name) },
                        onClick = {
                            editedDocumentTypeId = docType.id
                            documentTypeExpanded = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Archive Serial Number
        Text(
            text = stringResource(R.string.document_edit_asn_label),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = editedAsn,
            onValueChange = { editedAsn = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(stringResource(R.string.document_edit_asn_label)) },
            shape = RoundedCornerShape(8.dp),
            singleLine = true,
            enabled = !isUpdating
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Save Button
        Button(
            onClick = {
                onSave(
                    editedTitle.trim(),
                    editedTagIds,
                    editedCorrespondentId,
                    editedDocumentTypeId,
                    editedAsn.takeIf { it.isNotBlank() }
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            enabled = editedTitle.isNotBlank() && !isUpdating,
            shape = RoundedCornerShape(8.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            if (isUpdating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
            } else {
                Text(
                    text = stringResource(R.string.document_edit_save),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/**
 * WiFi Required Banner - shown when AI analysis requires WiFi but device is not connected.
 * Provides "Use anyway" button to override the restriction for current session.
 */
@Composable
private fun WifiRequiredBanner(
    onUseAnywayClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.ErrorOutline,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.ai_wifi_only_banner),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.ai_wifi_required_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedButton(
                onClick = onUseAnywayClick,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
            ) {
                Text(
                    text = stringResource(R.string.ai_wifi_only_override_button),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}
