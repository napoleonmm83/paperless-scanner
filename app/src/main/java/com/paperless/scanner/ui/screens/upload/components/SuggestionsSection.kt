package com.paperless.scanner.ui.screens.upload.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paperless.scanner.R
import com.paperless.scanner.data.ai.models.DocumentAnalysis
import com.paperless.scanner.data.ai.models.SuggestionSource
import com.paperless.scanner.data.ai.models.TagSuggestion
import com.paperless.scanner.domain.model.Tag
import com.paperless.scanner.ui.screens.upload.AnalysisState

/**
 * Suggestions section for displaying AI/Paperless/Local tag suggestions.
 *
 * PHASE 1: AI features only available in debug builds.
 * Shows source indicator (AI ✨, Paperless, Local) to differentiate suggestion origins.
 *
 * @param analysisState Current state of the document analysis
 * @param suggestions The document analysis results with suggestions
 * @param suggestionSource Source of the suggestions (FIREBASE_AI, PAPERLESS_API, LOCAL_MATCHING)
 * @param existingTags List of existing tags to match suggestions
 * @param selectedTagIds Currently selected tag IDs
 * @param onAnalyzeClick Callback when "Get Suggestions" button is clicked
 * @param onApplyTagSuggestion Callback when a tag suggestion is applied
 * @param modifier Modifier for the component
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SuggestionsSection(
    analysisState: AnalysisState,
    suggestions: DocumentAnalysis?,
    suggestionSource: SuggestionSource?,
    existingTags: List<Tag>,
    selectedTagIds: Set<Int>,
    currentTitle: String,
    onAnalyzeClick: () -> Unit,
    onApplyTagSuggestion: (TagSuggestion) -> Unit,
    onApplyTitle: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    // Animated border setup
    val infiniteTransition = rememberInfiniteTransition(label = "border_animation")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "border_rotation"
    )

    // Colors for the animated gradient border
    val isDarkTheme = isSystemInDarkTheme()
    val primaryColor = MaterialTheme.colorScheme.primary
    val gradientColors = if (isDarkTheme) {
        // Dark mode: neon yellow primary with subtle variations
        listOf(
            primaryColor,
            primaryColor.copy(alpha = 0.7f),
            primaryColor.copy(alpha = 0.3f),
            Color.Transparent,
            Color.Transparent,
            primaryColor.copy(alpha = 0.3f),
            primaryColor.copy(alpha = 0.7f),
            primaryColor
        )
    } else {
        // Light mode: use primary with a teal/cyan accent for contrast
        val accentColor = Color(0xFF00BCD4) // Cyan/teal for light mode
        listOf(
            primaryColor,
            accentColor.copy(alpha = 0.8f),
            primaryColor.copy(alpha = 0.4f),
            Color.Transparent,
            Color.Transparent,
            primaryColor.copy(alpha = 0.4f),
            accentColor.copy(alpha = 0.8f),
            primaryColor
        )
    }

    val shape = RoundedCornerShape(20.dp)
    val borderWidth = 1.5f

    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(shape)
            .drawBehind {
                rotate(rotation) {
                    drawCircle(
                        brush = Brush.sweepGradient(gradientColors),
                        radius = size.maxDimension,
                        center = Offset(size.width / 2, size.height / 2)
                    )
                }
            }
            .padding(borderWidth.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            // Header with title and source badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val (title, icon) = when (suggestionSource) {
                        SuggestionSource.FIREBASE_AI -> Pair(
                            stringResource(R.string.suggestions_ai_title),
                            Icons.Default.AutoAwesome
                        )
                        SuggestionSource.PAPERLESS_API -> Pair(
                            stringResource(R.string.suggestions_title),
                            Icons.Default.Cloud
                        )
                        SuggestionSource.LOCAL_MATCHING -> Pair(
                            stringResource(R.string.suggestions_title),
                            Icons.Default.Storage
                        )
                        null -> Pair(
                            stringResource(R.string.suggestions_title),
                            Icons.Default.AutoAwesome
                        )
                    }

                    Icon(
                        imageVector = icon,
                        contentDescription = stringResource(R.string.cd_suggestions),
                        tint = if (suggestionSource == SuggestionSource.FIREBASE_AI) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(20.dp)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                // Source badge
                if (suggestionSource != null && suggestions != null) {
                    SourceBadge(source = suggestionSource)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Content based on state
            when (analysisState) {
                is AnalysisState.Idle -> {
                    // Show "Get Suggestions" button
                    OutlinedButton(
                        onClick = onAnalyzeClick,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.AutoAwesome,
                            contentDescription = stringResource(R.string.cd_ai_sparkle),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(stringResource(R.string.suggestions_get_suggestions))
                    }
                }

                is AnalysisState.Analyzing -> {
                    // Show loading indicator
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 16.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
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

                is AnalysisState.Success -> {
                    // Show suggestions
                    if (suggestions != null && (suggestions.suggestedTags.isNotEmpty() || suggestions.suggestedTitle != null)) {
                        SuggestionsContent(
                            suggestions = suggestions,
                            existingTags = existingTags,
                            selectedTagIds = selectedTagIds,
                            currentTitle = currentTitle,
                            onApplyTagSuggestion = onApplyTagSuggestion,
                            onApplyTitle = onApplyTitle
                        )
                    } else {
                        NoSuggestionsMessage()
                    }
                }

                is AnalysisState.Error -> {
                    ErrorMessage(message = analysisState.message, onRetry = onAnalyzeClick)
                }

                is AnalysisState.LimitInfo -> {
                    LimitInfoMessage(
                        remainingCalls = analysisState.remainingCalls,
                        suggestions = suggestions,
                        existingTags = existingTags,
                        selectedTagIds = selectedTagIds,
                        currentTitle = currentTitle,
                        onApplyTagSuggestion = onApplyTagSuggestion,
                        onApplyTitle = onApplyTitle
                    )
                }

                is AnalysisState.LimitWarning -> {
                    LimitWarningMessage(
                        remainingCalls = analysisState.remainingCalls,
                        suggestions = suggestions,
                        existingTags = existingTags,
                        selectedTagIds = selectedTagIds,
                        currentTitle = currentTitle,
                        onApplyTagSuggestion = onApplyTagSuggestion,
                        onApplyTitle = onApplyTitle
                    )
                }

                is AnalysisState.LimitReached -> {
                    LimitReachedMessage(
                        suggestions = suggestions,
                        existingTags = existingTags,
                        selectedTagIds = selectedTagIds,
                        currentTitle = currentTitle,
                        onApplyTagSuggestion = onApplyTagSuggestion,
                        onApplyTitle = onApplyTitle
                    )
                }
            }
        }
    }
}

@Composable
private fun SourceBadge(source: SuggestionSource) {
    val (text, backgroundColor) = when (source) {
        SuggestionSource.FIREBASE_AI -> Pair(
            stringResource(R.string.suggestions_source_ai),
            MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
        )
        SuggestionSource.PAPERLESS_API -> Pair(
            stringResource(R.string.suggestions_source_paperless),
            MaterialTheme.colorScheme.secondary.copy(alpha = 0.2f)
        )
        SuggestionSource.LOCAL_MATCHING -> Pair(
            stringResource(R.string.suggestions_source_local),
            MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f)
        )
    }

    Box(
        modifier = Modifier
            .background(
                color = backgroundColor,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = when (source) {
                SuggestionSource.FIREBASE_AI -> MaterialTheme.colorScheme.primary
                SuggestionSource.PAPERLESS_API -> MaterialTheme.colorScheme.secondary
                SuggestionSource.LOCAL_MATCHING -> MaterialTheme.colorScheme.tertiary
            }
        )
    }
}

private const val MAX_VISIBLE_TAGS = 6

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestionsContent(
    suggestions: DocumentAnalysis,
    existingTags: List<Tag>,
    selectedTagIds: Set<Int>,
    currentTitle: String,
    onApplyTagSuggestion: (TagSuggestion) -> Unit,
    onApplyTitle: (String) -> Unit
) {
    var showAllTags by remember { mutableStateOf(false) }

    // Sort tags: unselected first (so user sees what's available), then by confidence
    val sortedTags = suggestions.suggestedTags.sortedByDescending { tagSuggestion ->
        val tagId = tagSuggestion.tagId ?: existingTags.find {
            it.name.equals(tagSuggestion.tagName, ignoreCase = true)
        }?.id
        val isSelected = tagId != null && selectedTagIds.contains(tagId)
        if (isSelected) -1f else tagSuggestion.confidence
    }

    val visibleTags = if (showAllTags) sortedTags else sortedTags.take(MAX_VISIBLE_TAGS)
    val hasMoreTags = sortedTags.size > MAX_VISIBLE_TAGS

    Column {
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            visibleTags.forEach { tagSuggestion ->
                // Check both tagSuggestion.tagId AND existingTags for selection status
                val tagId = tagSuggestion.tagId ?: existingTags.find {
                    it.name.equals(tagSuggestion.tagName, ignoreCase = true)
                }?.id
                val isAlreadySelected = tagId != null && selectedTagIds.contains(tagId)
                val isNewTag = tagSuggestion.tagId == null && existingTags.none {
                    it.name.equals(tagSuggestion.tagName, ignoreCase = true)
                }

                SuggestionChip(
                    onClick = {
                        if (!isAlreadySelected) {
                            onApplyTagSuggestion(tagSuggestion)
                        }
                    },
                    label = {
                        Text(tagSuggestion.tagName)
                    },
                    icon = {
                        if (isAlreadySelected) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = stringResource(R.string.cd_selected),
                                modifier = Modifier.size(SuggestionChipDefaults.IconSize)
                            )
                        } else if (isNewTag) {
                            // New tag suggestion
                            Icon(
                                imageVector = Icons.Default.Add,
                                contentDescription = stringResource(R.string.suggestions_new_tag),
                                modifier = Modifier.size(SuggestionChipDefaults.IconSize)
                            )
                        }
                    },
                    enabled = !isAlreadySelected,
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = if (isNewTag) {
                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    )
                )
            }

            // Show "more" chip if there are hidden tags
            if (hasMoreTags && !showAllTags) {
                SuggestionChip(
                    onClick = { showAllTags = true },
                    label = {
                        Text("+${sortedTags.size - MAX_VISIBLE_TAGS}")
                    },
                    icon = {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(SuggestionChipDefaults.IconSize)
                        )
                    },
                    colors = SuggestionChipDefaults.suggestionChipColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                    )
                )
            }
        }

        // Show suggested title if available - clickable to apply
        if (suggestions.suggestedTitle != null) {
            val isTitleApplied = currentTitle == suggestions.suggestedTitle
            Spacer(modifier = Modifier.height(12.dp))
            AssistChip(
                onClick = {
                    if (!isTitleApplied) {
                        onApplyTitle(suggestions.suggestedTitle)
                    }
                },
                label = {
                    Text(
                        text = suggestions.suggestedTitle,
                        style = MaterialTheme.typography.bodyMedium
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = if (isTitleApplied) Icons.Default.Check else Icons.Default.Info,
                        contentDescription = if (isTitleApplied) {
                            stringResource(R.string.cd_selected)
                        } else {
                            stringResource(R.string.suggestions_title_suggestion)
                        },
                        modifier = Modifier.size(AssistChipDefaults.IconSize)
                    )
                },
                enabled = !isTitleApplied,
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = if (isTitleApplied) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            )
        }
    }
}

@Composable
private fun NoSuggestionsMessage() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.Info,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.suggestions_no_suggestions),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ErrorMessage(message: String, onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = stringResource(R.string.cd_warning),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onRetry) {
            Text(stringResource(R.string.retry))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LimitInfoMessage(
    remainingCalls: Int,
    suggestions: DocumentAnalysis?,
    existingTags: List<Tag>,
    selectedTagIds: Set<Int>,
    currentTitle: String,
    onApplyTagSuggestion: (TagSuggestion) -> Unit,
    onApplyTitle: (String) -> Unit
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = stringResource(R.string.cd_info),
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.ai_calls_remaining, remainingCalls),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary
            )
        }

        if (suggestions != null && (suggestions.suggestedTags.isNotEmpty() || suggestions.suggestedTitle != null)) {
            Spacer(modifier = Modifier.height(12.dp))
            SuggestionsContent(
                suggestions = suggestions,
                existingTags = existingTags,
                selectedTagIds = selectedTagIds,
                currentTitle = currentTitle,
                onApplyTagSuggestion = onApplyTagSuggestion,
                onApplyTitle = onApplyTitle
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LimitWarningMessage(
    remainingCalls: Int,
    suggestions: DocumentAnalysis?,
    existingTags: List<Tag>,
    selectedTagIds: Set<Int>,
    currentTitle: String,
    onApplyTagSuggestion: (TagSuggestion) -> Unit,
    onApplyTitle: (String) -> Unit
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = stringResource(R.string.cd_warning),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.ai_calls_remaining, remainingCalls),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        if (suggestions != null && (suggestions.suggestedTags.isNotEmpty() || suggestions.suggestedTitle != null)) {
            Spacer(modifier = Modifier.height(12.dp))
            SuggestionsContent(
                suggestions = suggestions,
                existingTags = existingTags,
                selectedTagIds = selectedTagIds,
                currentTitle = currentTitle,
                onApplyTagSuggestion = onApplyTagSuggestion,
                onApplyTitle = onApplyTitle
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LimitReachedMessage(
    suggestions: DocumentAnalysis?,
    existingTags: List<Tag>,
    selectedTagIds: Set<Int>,
    currentTitle: String,
    onApplyTagSuggestion: (TagSuggestion) -> Unit,
    onApplyTitle: (String) -> Unit
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp)
                )
                .padding(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = stringResource(R.string.cd_warning),
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.ai_fallback_active),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        // Still show suggestions (from fallback sources)
        if (suggestions != null && (suggestions.suggestedTags.isNotEmpty() || suggestions.suggestedTitle != null)) {
            Spacer(modifier = Modifier.height(12.dp))
            SuggestionsContent(
                suggestions = suggestions,
                existingTags = existingTags,
                selectedTagIds = selectedTagIds,
                currentTitle = currentTitle,
                onApplyTagSuggestion = onApplyTagSuggestion,
                onApplyTitle = onApplyTitle
            )
        }
    }
}
