package com.paperless.scanner.ui.screens.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.SkipNext
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.paperless.scanner.R
import com.paperless.scanner.data.ai.models.TagSuggestion
import com.paperless.scanner.domain.model.Tag
import com.paperless.scanner.ui.screens.settings.PremiumUpgradeSheet
import com.paperless.scanner.ui.screens.upload.CreateTagDialog
import com.paperless.scanner.ui.screens.upload.components.TagSelectionSection

/**
 * Full-screen Smart Tagging screen.
 * One document at a time with tag selection and action buttons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmartTaggingScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val tagSuggestionsState by viewModel.tagSuggestionsState.collectAsState()
    val availableTags by viewModel.availableTags.collectAsState()
    val isAiAvailable by viewModel.isAiAvailable.collectAsState()
    val createTagState by viewModel.createTagState.collectAsState()

    // Premium Upgrade Sheet state
    var showPremiumUpgradeSheet by remember { mutableStateOf(false) }

    // Create Tag Dialog state
    var showCreateTagDialog by remember { mutableStateOf(false) }

    // Load untagged documents when screen opens
    LaunchedEffect(Unit) {
        viewModel.loadUntaggedDocumentsForScreen()
    }

    // Handle tag creation success
    LaunchedEffect(createTagState) {
        when (createTagState) {
            is CreateTagState.Success -> {
                showCreateTagDialog = false
                viewModel.resetCreateTagState()
            }
            else -> {}
        }
    }

    // Get current document to display
    val remainingDocs = tagSuggestionsState.documents.filter { !it.isTagged && !it.isSkipped }
    val currentDocument = remainingDocs.firstOrNull()
    val totalDocs = tagSuggestionsState.documents.size
    val processedDocs = tagSuggestionsState.documents.count { it.isTagged || it.isSkipped }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.tag_suggestions_title),
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back)
                        )
                    }
                },
                actions = {
                    if (totalDocs > 0) {
                        Text(
                            text = "${processedDocs + 1}/$totalDocs",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 16.dp)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Progress bar
            if (totalDocs > 0) {
                LinearProgressIndicator(
                    progress = { if (totalDocs > 0) processedDocs.toFloat() / totalDocs else 0f },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }

            when {
                tagSuggestionsState.isLoading -> {
                    LoadingState()
                }
                currentDocument == null && totalDocs > 0 -> {
                    CompletedState(
                        taggedCount = tagSuggestionsState.taggedCount,
                        onDone = onNavigateBack
                    )
                }
                currentDocument == null -> {
                    EmptyState(onNavigateBack = onNavigateBack)
                }
                else -> {
                    DocumentTaggingContent(
                        document = currentDocument,
                        availableTags = availableTags,
                        suggestedNewTags = currentDocument.suggestedNewTags,
                        isCreatingTag = createTagState is CreateTagState.Creating,
                        isAiAvailable = isAiAvailable,
                        onAnalyze = { viewModel.analyzeDocument(currentDocument.id) },
                        onApplyTags = { tagIds ->
                            viewModel.applyTagsToDocument(currentDocument.id, tagIds)
                        },
                        onSkip = { viewModel.skipDocument(currentDocument.id) },
                        onToggleTag = { tagId ->
                            viewModel.toggleTagInPicker(currentDocument.id, tagId)
                        },
                        onCreateSuggestedTag = { tagName ->
                            viewModel.createSuggestedTag(currentDocument.id, tagName)
                        },
                        onUpgradeToPremium = { showPremiumUpgradeSheet = true },
                        onCreateNewTag = { showCreateTagDialog = true }
                    )
                }
            }
        }
    }

    // Premium Upgrade Sheet
    if (showPremiumUpgradeSheet) {
        PremiumUpgradeSheet(
            onDismiss = { showPremiumUpgradeSheet = false },
            onSubscribe = { _ ->
                showPremiumUpgradeSheet = false
                onNavigateToSettings()
            },
            onRestore = {
                showPremiumUpgradeSheet = false
                onNavigateToSettings()
            }
        )
    }

    // Create Tag Dialog
    if (showCreateTagDialog) {
        CreateTagDialog(
            isCreating = createTagState is CreateTagState.Creating,
            onDismiss = {
                showCreateTagDialog = false
                viewModel.resetCreateTagState()
            },
            onCreate = { name, color ->
                viewModel.createTag(name, color)
            }
        )
    }
}

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.tag_suggestions_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun EmptyState(onNavigateBack: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.tag_suggestions_all_tagged),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.tag_suggestions_all_tagged_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(onClick = onNavigateBack) {
                Text(stringResource(R.string.done))
            }
        }
    }
}

@Composable
private fun CompletedState(
    taggedCount: Int,
    onDone: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = stringResource(R.string.tag_suggestions_complete),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.smart_tagging_completed_desc, taggedCount),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(32.dp))
            Button(onClick = onDone) {
                Text(stringResource(R.string.done))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DocumentTaggingContent(
    document: UntaggedDocument,
    availableTags: List<Tag>,
    suggestedNewTags: List<TagSuggestion>,
    isCreatingTag: Boolean,
    isAiAvailable: Boolean,
    onAnalyze: () -> Unit,
    onApplyTags: (List<Int>) -> Unit,
    onSkip: () -> Unit,
    onToggleTag: (Int) -> Unit,
    onCreateSuggestedTag: (String) -> Unit,
    onUpgradeToPremium: () -> Unit,
    onCreateNewTag: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Document Card
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outline
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Large Thumbnail
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        document.thumbnailBitmap != null -> {
                            AsyncImage(
                                model = document.thumbnailBitmap,
                                contentDescription = document.title,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                        document.thumbnailUrl != null -> {
                            AsyncImage(
                                model = document.thumbnailUrl,
                                contentDescription = document.title,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                        else -> {
                            Icon(
                                imageVector = Icons.Filled.Description,
                                contentDescription = null,
                                modifier = Modifier.size(64.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Document Title
                Text(
                    text = document.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )

                // Analysis state indicator
                when (document.analysisState) {
                    is UntaggedDocAnalysisState.LoadingThumbnail,
                    is UntaggedDocAnalysisState.Loading,
                    is UntaggedDocAnalysisState.Analyzing -> {
                        Spacer(modifier = Modifier.height(12.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.suggestions_analyzing),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    is UntaggedDocAnalysisState.Error -> {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = (document.analysisState as UntaggedDocAnalysisState.Error).message,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                    }
                    else -> {}
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Tag Selection Section (consistent with other screens)
        Text(
            text = stringResource(R.string.document_edit_tags_label).uppercase(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(12.dp))

        TagSelectionSection(
            tags = availableTags,
            selectedTagIds = document.selectedTagIds,
            onToggleTag = onToggleTag,
            onCreateNew = onCreateNewTag
        )

        // Suggested New Tags Section (AI suggested tags that don't exist yet)
        if (suggestedNewTags.isNotEmpty()) {
            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.smart_tagging_suggested_new_tags).uppercase(),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.smart_tagging_suggested_new_tags_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(12.dp))

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                suggestedNewTags.forEach { suggestion ->
                    SuggestionChip(
                        onClick = {
                            if (!isCreatingTag) {
                                onCreateSuggestedTag(suggestion.tagName)
                            }
                        },
                        label = {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Add,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(suggestion.tagName)
                            }
                        },
                        enabled = !isCreatingTag,
                        colors = SuggestionChipDefaults.suggestionChipColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                            labelColor = MaterialTheme.colorScheme.primary,
                            iconContentColor = MaterialTheme.colorScheme.primary
                        ),
                        border = SuggestionChipDefaults.suggestionChipBorder(
                            enabled = true,
                            borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // AI Button
        if (document.analysisState is UntaggedDocAnalysisState.Idle ||
            document.analysisState is UntaggedDocAnalysisState.Error) {
            if (isAiAvailable) {
                OutlinedButton(
                    onClick = onAnalyze,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.smart_tagging_ai_suggest))
                }
            } else {
                OutlinedButton(
                    onClick = onUpgradeToPremium,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Icon(
                        imageVector = Icons.Filled.AutoAwesome,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(stringResource(R.string.smart_tagging_ai_suggest))
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .background(
                                color = MaterialTheme.colorScheme.primary,
                                shape = RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 6.dp, vertical = 2.dp)
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

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Skip Button
            OutlinedButton(
                onClick = onSkip,
                modifier = Modifier.weight(1f)
            ) {
                Icon(
                    imageVector = Icons.Filled.SkipNext,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.smart_tagging_skip))
            }

            // Apply Button
            Button(
                onClick = { onApplyTags(document.selectedTagIds.toList()) },
                enabled = document.selectedTagIds.isNotEmpty(),
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.smart_tagging_apply))
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }
}
