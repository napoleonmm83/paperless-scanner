package com.paperless.scanner.ui.screens.home.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.paperless.scanner.R
import com.paperless.scanner.ui.screens.home.ProcessingTask
import com.paperless.scanner.ui.screens.home.ProcessingTasksUiState
import com.paperless.scanner.ui.screens.home.TaskStatus

/**
 * Processing Tasks dashboard section: header (dismiss-all + refresh actions),
 * the list of task cards, and a show-more/less toggle.
 */
@Composable
fun ProcessingTasksSection(
    uiState: ProcessingTasksUiState,
    onDismissAllCompleted: () -> Unit,
    onRefresh: () -> Unit,
    onToggleShowAll: () -> Unit,
    onDocumentClick: (Int) -> Unit,
    onAcknowledgeTask: (ProcessingTask) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.home_processing),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Dismiss All button (only show if there are completed tasks)
                if (uiState.hasCompleted) {
                    IconButton(
                        onClick = onDismissAllCompleted,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.DoneAll,
                            contentDescription = stringResource(R.string.home_dismiss_all_completed),
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                IconButton(
                    onClick = onRefresh,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = stringResource(R.string.home_refresh),
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        uiState.displayed.forEach { task ->
            // Stable key so rememberSwipeToDismissBoxState/dismissTriggered
            // inside ProcessingTaskCard stays attached to the same task
            // identity when the list mutates (e.g. after acknowledgeTask).
            key(task.id) {
                ProcessingTaskCard(
                    task = task,
                    onClick = {
                        task.documentId?.let { onDocumentClick(it) }
                    },
                    onDismiss = { onAcknowledgeTask(task) }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        // Show more/less button
        if (uiState.tasks.size > ProcessingTasksUiState.DISPLAY_LIMIT) {
            TextButton(
                onClick = onToggleShowAll,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (uiState.showAll) {
                        stringResource(R.string.home_processing_show_less)
                    } else {
                        stringResource(R.string.home_processing_show_more, uiState.hiddenCount)
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

private data class TaskStyle(
    val backgroundColor: Color,
    val statusIcon: ImageVector?,
    val iconColor: Color,
    val textColor: Color,
    val statusText: String
)

@Composable
private fun getTaskStyle(
    task: ProcessingTask,
    isDuplicate: Boolean
): TaskStyle {
    val statusWaiting = stringResource(R.string.home_task_waiting)
    val statusProcessing = stringResource(R.string.home_task_processing)
    val statusSuccess = stringResource(R.string.home_task_success)
    val statusFailure = stringResource(R.string.home_task_failure)
    val statusDuplicate = stringResource(R.string.home_task_duplicate)

    return when {
        isDuplicate -> TaskStyle(
            backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
            statusIcon = Icons.Filled.ContentCopy,
            iconColor = MaterialTheme.colorScheme.primary,
            textColor = MaterialTheme.colorScheme.primary,
            statusText = statusDuplicate
        )
        task.status == TaskStatus.PENDING -> TaskStyle(
            backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
            statusIcon = null,
            iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
            statusText = statusWaiting
        )
        task.status == TaskStatus.PROCESSING -> TaskStyle(
            backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
            statusIcon = null,
            iconColor = MaterialTheme.colorScheme.primary,
            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
            statusText = statusProcessing
        )
        task.status == TaskStatus.SUCCESS -> TaskStyle(
            backgroundColor = MaterialTheme.colorScheme.primary,
            statusIcon = Icons.Filled.CheckCircle,
            iconColor = MaterialTheme.colorScheme.onPrimary,
            textColor = MaterialTheme.colorScheme.onPrimary,
            statusText = statusSuccess
        )
        task.status == TaskStatus.FAILURE -> TaskStyle(
            backgroundColor = MaterialTheme.colorScheme.errorContainer,
            statusIcon = Icons.Filled.Error,
            iconColor = MaterialTheme.colorScheme.onErrorContainer,
            textColor = MaterialTheme.colorScheme.onErrorContainer,
            statusText = statusFailure
        )
        else -> TaskStyle(
            backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
            statusIcon = null,
            iconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            textColor = MaterialTheme.colorScheme.onSurfaceVariant,
            statusText = statusWaiting
        )
    }
}

/**
 * Processing task card with swipe-to-dismiss functionality.
 *
 * SWIPE BEHAVIOR:
 * - Swipe left to dismiss (acknowledge) completed tasks
 * - Only enabled for SUCCESS and FAILURE status
 * - PENDING tasks cannot be swiped (still processing)
 * - X button remains as alternative dismiss method
 *
 * VISUAL FEEDBACK:
 * - Progressive background reveal during swipe
 * - Haptic feedback when threshold is reached
 * - 40% threshold prevents accidental dismisses
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProcessingTaskCard(
    task: ProcessingTask,
    onClick: () -> Unit,
    onDismiss: () -> Unit
) {
    // Detect duplicate error
    val isDuplicate = task.status == TaskStatus.FAILURE &&
            task.resultMessage?.contains("duplicate", ignoreCase = true) == true

    val style = getTaskStyle(task, isDuplicate)

    // Only enable swipe for completed tasks (SUCCESS or FAILURE)
    val canSwipe = task.status == TaskStatus.SUCCESS || task.status == TaskStatus.FAILURE

    if (canSwipe) {
        // Track if dismiss was already triggered for this swipe
        var dismissTriggered by remember { mutableStateOf(false) }
        val hapticFeedback = LocalHapticFeedback.current

        val dismissState = rememberSwipeToDismissBoxState(
            confirmValueChange = { dismissValue ->
                if (dismissValue == SwipeToDismissBoxValue.EndToStart && !dismissTriggered) {
                    dismissTriggered = true
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                    onDismiss()
                    true
                } else {
                    false
                }
            },
            positionalThreshold = { totalDistance -> totalDistance * 0.4f }
        )

        // Reset flag when task changes
        LaunchedEffect(task.id) {
            dismissTriggered = false
        }

        SwipeToDismissBox(
            state = dismissState,
            modifier = Modifier.fillMaxWidth(),
            backgroundContent = {
                // Progressive visual feedback
                val swipeProgress = dismissState.progress
                val normalizedProgress = ((swipeProgress - 0.05f) / 0.35f).coerceIn(0f, 1f)
                val backgroundAlpha = normalizedProgress * 0.9f + 0.1f * (if (swipeProgress > 0.05f) 1f else 0f)

                val backgroundColor = if (swipeProgress > 0.05f) {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = backgroundAlpha.coerceIn(0f, 1f))
                } else {
                    Color.Transparent
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(backgroundColor, RoundedCornerShape(20.dp))
                        .padding(horizontal = 24.dp),
                    contentAlignment = Alignment.CenterEnd
                ) {
                    if (swipeProgress > 0.05f) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.alpha(normalizedProgress)
                        ) {
                            Text(
                                text = stringResource(R.string.home_dismiss),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Medium
                            )
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            },
            enableDismissFromStartToEnd = false,
            enableDismissFromEndToStart = true
        ) {
            ProcessingTaskCardContent(
                task = task,
                style = style,
                isDuplicate = isDuplicate,
                onClick = onClick,
                onDismiss = onDismiss,
                showDismissButton = true
            )
        }
    } else {
        // PENDING tasks: No swipe, just show the card
        ProcessingTaskCardContent(
            task = task,
            style = style,
            isDuplicate = isDuplicate,
            onClick = onClick,
            onDismiss = onDismiss,
            showDismissButton = false
        )
    }
}

/**
 * Inner content of ProcessingTaskCard, extracted to avoid duplication.
 */
@Composable
private fun ProcessingTaskCardContent(
    task: ProcessingTask,
    style: TaskStyle,
    isDuplicate: Boolean,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
    showDismissButton: Boolean
) {
    // Only SUCCESS tasks that point at a document are actionable. For
    // everything else, use the NON-clickable Card overload so the card is not
    // exposed to accessibility services as a (disabled) button.
    val isClickable = task.status == TaskStatus.SUCCESS && task.documentId != null

    val cardContent: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Status indicator
                Box(
                    modifier = Modifier.size(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (style.statusIcon != null) {
                        Icon(
                            imageVector = style.statusIcon,
                            contentDescription = style.statusText,
                            modifier = Modifier.size(24.dp),
                            tint = style.iconColor
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = style.iconColor
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = task.fileName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = if (isDuplicate) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            Color.Unspecified
                        },
                        maxLines = 1
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = style.statusText,
                            style = MaterialTheme.typography.labelSmall,
                            color = style.textColor
                        )
                        Text(
                            text = "•",
                            style = MaterialTheme.typography.labelSmall,
                            color = style.textColor.copy(alpha = 0.7f)
                        )
                        Text(
                            text = task.timeAgo,
                            style = MaterialTheme.typography.labelSmall,
                            color = style.textColor.copy(alpha = 0.7f)
                        )
                    }
                }

                // Dismiss button for completed tasks (alternative to swipe)
                if (showDismissButton) {
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = stringResource(R.string.home_dismiss),
                            modifier = Modifier.size(16.dp),
                            tint = style.textColor.copy(alpha = 0.7f)
                        )
                    }
                }
            }

            // Show result message for failures
            if (task.status == TaskStatus.FAILURE && task.resultMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))

                val displayMessage = if (isDuplicate) {
                    stringResource(R.string.home_task_duplicate_message)
                } else {
                    task.resultMessage
                }

                Text(
                    text = displayMessage,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isDuplicate) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    maxLines = 2
                )
            }
        }
    }

    val cardShape = RoundedCornerShape(20.dp)
    val cardColors = CardDefaults.cardColors(containerColor = style.backgroundColor)
    val cardElevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    val cardBorder = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)

    if (isClickable) {
        Card(
            onClick = onClick,
            modifier = Modifier.fillMaxWidth(),
            shape = cardShape,
            colors = cardColors,
            elevation = cardElevation,
            border = cardBorder
        ) {
            cardContent()
        }
    } else {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = cardShape,
            colors = cardColors,
            elevation = cardElevation,
            border = cardBorder
        ) {
            cardContent()
        }
    }
}

@Preview
@Composable
private fun ProcessingTasksSectionPreview() {
    MaterialTheme {
        ProcessingTasksSection(
            uiState = ProcessingTasksUiState(
                tasks = listOf(
                    ProcessingTask(id = 1, taskId = "t1", fileName = "Invoice.pdf", status = TaskStatus.SUCCESS, timeAgo = "2m ago", documentId = 1),
                    ProcessingTask(id = 2, taskId = "t2", fileName = "Receipt.jpg", status = TaskStatus.PROCESSING, timeAgo = "just now")
                )
            ),
            onDismissAllCompleted = {},
            onRefresh = {},
            onToggleShowAll = {},
            onDocumentClick = {},
            onAcknowledgeTask = {}
        )
    }
}
