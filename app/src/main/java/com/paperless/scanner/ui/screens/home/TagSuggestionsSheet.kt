package com.paperless.scanner.ui.screens.home

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.paperless.scanner.R
import com.paperless.scanner.data.ai.models.DocumentAnalysis
import com.paperless.scanner.data.ai.models.TagSuggestion
import com.paperless.scanner.domain.model.Tag
import com.paperless.scanner.ui.screens.upload.components.TagSelectionSection

/**
 * Data class representing an untagged document in the suggestions sheet.
 */
data class UntaggedDocument(
    val id: Int,
    val title: String,
    val thumbnailUrl: String?,
    val thumbnailBitmap: Bitmap? = null,
    val analysisState: UntaggedDocAnalysisState = UntaggedDocAnalysisState.Idle,
    val suggestions: DocumentAnalysis? = null,
    val selectedTagIds: Set<Int> = emptySet(),
    val suggestedNewTags: List<TagSuggestion> = emptyList(),
    val isTagged: Boolean = false,
    val isSkipped: Boolean = false
)

/**
 * Analysis state for each untagged document.
 */
sealed class UntaggedDocAnalysisState {
    data object Idle : UntaggedDocAnalysisState()
    data object Loading : UntaggedDocAnalysisState()
    data object LoadingThumbnail : UntaggedDocAnalysisState()
    data object Analyzing : UntaggedDocAnalysisState()
    data class Success(val suggestions: DocumentAnalysis) : UntaggedDocAnalysisState()
    data class Error(val message: String) : UntaggedDocAnalysisState()
}

/**
 * State for the Tag Suggestions Sheet.
 */
data class TagSuggestionsState(
    val documents: List<UntaggedDocument> = emptyList(),
    val isLoading: Boolean = false,
    val currentDocumentIndex: Int = 0,
    val taggedCount: Int = 0,
    val showTagPicker: Boolean = false,
    val tagPickerDocumentId: Int? = null
)

/**
 * Bottom sheet for managing untagged documents with AI suggestions.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagSuggestionsSheet(
    sheetState: SheetState,
    state: TagSuggestionsState,
    availableTags: List<Tag>,
    isAiAvailable: Boolean,
    onDismiss: () -> Unit,
    onAnalyzeDocument: (Int) -> Unit,
    onApplyTags: (documentId: Int, tagIds: List<Int>) -> Unit,
    onSkipDocument: (Int) -> Unit,
    onOpenTagPicker: (Int) -> Unit,
    onCloseTagPicker: () -> Unit,
    onToggleTagInPicker: (documentId: Int, tagId: Int) -> Unit,
    onApplyPickerTags: (documentId: Int) -> Unit,
    onUpgradeToPremium: () -> Unit = {},
    onCreateNewTag: () -> Unit = {}
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surface,
        dragHandle = { SheetDragHandle() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Tag,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.tag_suggestions_title),
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

            Spacer(modifier = Modifier.height(8.dp))

            // Progress indicator
            val totalDocs = state.documents.size
            val processedDocs = state.documents.count { it.isTagged || it.isSkipped }

            if (totalDocs > 0) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.tag_suggestions_progress, state.taggedCount, totalDocs),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (processedDocs == totalDocs && totalDocs > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.CheckCircle,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.tag_suggestions_complete),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                LinearProgressIndicator(
                    progress = { if (totalDocs > 0) processedDocs.toFloat() / totalDocs else 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Content
            when {
                state.isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(200.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }
                state.documents.isEmpty() -> {
                    EmptyState()
                }
                state.showTagPicker && state.tagPickerDocumentId != null -> {
                    val document = state.documents.find { it.id == state.tagPickerDocumentId }
                    if (document != null) {
                        TagPickerContent(
                            document = document,
                            availableTags = availableTags,
                            onBack = onCloseTagPicker,
                            onToggleTag = { tagId -> onToggleTagInPicker(document.id, tagId) },
                            onApply = { onApplyPickerTags(document.id) },
                            onCreateNewTag = onCreateNewTag
                        )
                    }
                }
                else -> {
                    DocumentsList(
                        documents = state.documents.filter { !it.isTagged && !it.isSkipped },
                        availableTags = availableTags,
                        isAiAvailable = isAiAvailable,
                        onAnalyze = onAnalyzeDocument,
                        onApplyTags = onApplyTags,
                        onSkip = onSkipDocument,
                        onOpenTagPicker = onOpenTagPicker,
                        onUpgradeToPremium = onUpgradeToPremium
                    )
                }
            }
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
private fun EmptyState() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.tag_suggestions_all_tagged),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.tag_suggestions_all_tagged_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun DocumentsList(
    documents: List<UntaggedDocument>,
    availableTags: List<Tag>,
    isAiAvailable: Boolean,
    onAnalyze: (Int) -> Unit,
    onApplyTags: (documentId: Int, tagIds: List<Int>) -> Unit,
    onSkip: (Int) -> Unit,
    onOpenTagPicker: (Int) -> Unit,
    onUpgradeToPremium: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(documents, key = { it.id }) { document ->
            DocumentCard(
                document = document,
                availableTags = availableTags,
                isAiAvailable = isAiAvailable,
                onAnalyze = { onAnalyze(document.id) },
                onApplyTags = { tagIds -> onApplyTags(document.id, tagIds) },
                onSkip = { onSkip(document.id) },
                onOpenTagPicker = { onOpenTagPicker(document.id) },
                onUpgradeToPremium = onUpgradeToPremium
            )
        }

        // Bottom spacing
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DocumentCard(
    document: UntaggedDocument,
    availableTags: List<Tag>,
    isAiAvailable: Boolean,
    onAnalyze: () -> Unit,
    onApplyTags: (List<Int>) -> Unit,
    onSkip: () -> Unit,
    onOpenTagPicker: () -> Unit,
    onUpgradeToPremium: () -> Unit
) {
    var selectedTagIds by remember(document.id, document.suggestions) {
        mutableStateOf(document.selectedTagIds)
    }

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
            // Document header with thumbnail
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Thumbnail
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    if (document.thumbnailBitmap != null) {
                        AsyncImage(
                            model = document.thumbnailBitmap,
                            contentDescription = document.title,
                            modifier = Modifier.size(56.dp),
                            contentScale = ContentScale.Crop
                        )
                    } else if (document.thumbnailUrl != null) {
                        AsyncImage(
                            model = document.thumbnailUrl,
                            contentDescription = document.title,
                            modifier = Modifier.size(56.dp),
                            contentScale = ContentScale.Crop
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Description,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = document.title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Analysis state content
            when (val state = document.analysisState) {
                is UntaggedDocAnalysisState.Idle -> {
                    // All buttons in one row: [AI] [Manual] [Skip]
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // AI Button (functional for premium, opens upgrade for non-premium)
                        if (isAiAvailable) {
                            OutlinedButton(
                                onClick = onAnalyze,
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.tag_suggestions_analyze_short),
                                    maxLines = 1
                                )
                            }
                        } else {
                            // Non-premium: AI button with PRO badge -> opens Premium upgrade
                            OutlinedButton(
                                onClick = onUpgradeToPremium,
                                modifier = Modifier.weight(1f),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.AutoAwesome,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = stringResource(R.string.ai_short),
                                    maxLines = 1
                                )
                                Spacer(modifier = Modifier.width(4.dp))
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
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }

                        // Manual tagging button
                        OutlinedButton(
                            onClick = onOpenTagPicker,
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.tag_suggestions_manual),
                                maxLines = 1
                            )
                        }

                        // Skip button
                        OutlinedButton(
                            onClick = onSkip
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SkipNext,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                is UntaggedDocAnalysisState.LoadingThumbnail,
                is UntaggedDocAnalysisState.Loading -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.tag_suggestions_loading_thumbnail),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                is UntaggedDocAnalysisState.Analyzing -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.suggestions_analyzing),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                is UntaggedDocAnalysisState.Success -> {
                    val suggestions = state.suggestions

                    // Tag suggestions
                    if (suggestions.suggestedTags.isNotEmpty()) {
                        Text(
                            text = stringResource(R.string.tag_suggestions_suggested_tags),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            suggestions.suggestedTags.take(6).forEach { tagSuggestion ->
                                val tagId = tagSuggestion.tagId ?: availableTags.find {
                                    it.name.equals(tagSuggestion.tagName, ignoreCase = true)
                                }?.id
                                val isSelected = tagId != null && selectedTagIds.contains(tagId)
                                val isNewTag = tagSuggestion.tagId == null && availableTags.none {
                                    it.name.equals(tagSuggestion.tagName, ignoreCase = true)
                                }

                                SuggestionChip(
                                    onClick = {
                                        if (tagId != null) {
                                            selectedTagIds = if (isSelected) {
                                                selectedTagIds - tagId
                                            } else {
                                                selectedTagIds + tagId
                                            }
                                        }
                                    },
                                    label = { Text(tagSuggestion.tagName) },
                                    icon = {
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Filled.Check,
                                                contentDescription = null,
                                                modifier = Modifier.size(SuggestionChipDefaults.IconSize)
                                            )
                                        } else if (isNewTag) {
                                            Icon(
                                                imageVector = Icons.Filled.Add,
                                                contentDescription = null,
                                                modifier = Modifier.size(SuggestionChipDefaults.IconSize)
                                            )
                                        }
                                    },
                                    colors = SuggestionChipDefaults.suggestionChipColors(
                                        containerColor = if (isSelected) {
                                            MaterialTheme.colorScheme.primaryContainer
                                        } else if (isNewTag) {
                                            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                                        } else {
                                            MaterialTheme.colorScheme.surfaceVariant
                                        }
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))
                    }

                    // Action buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Apply button
                        Button(
                            onClick = { onApplyTags(selectedTagIds.toList()) },
                            enabled = selectedTagIds.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.tag_suggestions_apply))
                        }

                        // Manual tag button
                        OutlinedButton(
                            onClick = onOpenTagPicker
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }

                        // Skip button
                        OutlinedButton(
                            onClick = onSkip
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SkipNext,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                is UntaggedDocAnalysisState.Error -> {
                    Column {
                        Text(
                            text = state.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = onAnalyze,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.retry))
                            }
                            OutlinedButton(
                                onClick = onOpenTagPicker,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.tag_suggestions_manual))
                            }
                            OutlinedButton(
                                onClick = onSkip
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.SkipNext,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TagPickerContent(
    document: UntaggedDocument,
    availableTags: List<Tag>,
    onBack: () -> Unit,
    onToggleTag: (Int) -> Unit,
    onApply: () -> Unit,
    onCreateNewTag: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onBack) {
                Text(stringResource(R.string.back))
            }
            Text(
                text = stringResource(R.string.tag_suggestions_select_tags),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium
            )
            TextButton(
                onClick = onApply,
                enabled = document.selectedTagIds.isNotEmpty()
            ) {
                Text(stringResource(R.string.tag_suggestions_apply))
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Document title
        Text(
            text = document.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Use consistent TagSelectionSection component (same as UploadScreen, EditDocumentSheet)
        TagSelectionSection(
            tags = availableTags,
            selectedTagIds = document.selectedTagIds,
            onToggleTag = onToggleTag,
            onCreateNew = onCreateNewTag
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}
