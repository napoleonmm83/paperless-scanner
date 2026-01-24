package com.paperless.scanner.ui.screens.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.outlined.AccessTime
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.paperless.scanner.R
import com.paperless.scanner.ui.screens.settings.PremiumUpgradeSheet
import com.paperless.scanner.ui.screens.upload.CreateTagDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToScan: () -> Unit,
    onNavigateToDocuments: () -> Unit,
    onDocumentClick: (Int) -> Unit = {},
    onNavigateToPendingSync: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToSmartTagging: () -> Unit = {},
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val isOnline by viewModel.isOnline.collectAsState()
    val isServerReachable by viewModel.isServerReachable.collectAsState()
    val pendingChanges by viewModel.pendingChangesCount.collectAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    // Tag Suggestions Sheet state
    val showTagSuggestionsSheet by viewModel.showTagSuggestionsSheet.collectAsState()
    val tagSuggestionsState by viewModel.tagSuggestionsState.collectAsState()
    val availableTags by viewModel.availableTags.collectAsState()
    val isAiAvailable by viewModel.isAiAvailable.collectAsState()
    val tagSuggestionsSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Tag creation state
    val createTagState by viewModel.createTagState.collectAsState()
    var showCreateTagDialog by remember { mutableStateOf(false) }

    // Premium Upgrade Sheet state
    var showPremiumUpgradeSheet by remember { mutableStateOf(false) }

    // Pull-to-refresh state
    var isRefreshing by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    // BEST PRACTICE: Refresh dashboard (stats + tasks) when screen becomes visible
    // This catches changes made via web interface or other devices
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshDashboard()  // Immediate refresh
                // Delayed refresh to catch newly created tasks (after uploads/deletes)
                coroutineScope.launch {
                    delay(1500)
                    viewModel.refreshDashboard()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // Handle tag creation success/error
    LaunchedEffect(createTagState) {
        when (createTagState) {
            is CreateTagState.Success -> {
                showCreateTagDialog = false
                viewModel.resetCreateTagState()
            }
            is CreateTagState.Error -> {
                // Keep dialog open to show error, user can dismiss manually
            }
            else -> {}
        }
    }

    // BEST PRACTICE: Pull-to-refresh for user-triggered updates
    // Refreshes stats and tasks from server to catch web/multi-device changes
    PullToRefreshBox(
        isRefreshing = isRefreshing,
        onRefresh = {
            isRefreshing = true
            viewModel.refreshDashboard()
            // Reset after a short delay (UI feedback)
            coroutineScope.launch {
                delay(1000)
                isRefreshing = false
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
        // Offline Indicator
        com.paperless.scanner.ui.components.OfflineIndicator(
            isOnline = isOnline,
            pendingChanges = pendingChanges,
            onClickShowDetails = onNavigateToPendingSync
        )

        // Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 24.dp, bottom = 16.dp)
        ) {
            Text(
                text = stringResource(R.string.home_welcome_back),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.home_your_archive),
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.ExtraBold
            )
        }

        // Stats Grid
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                icon = Icons.Filled.Description,
                value = "${uiState.stats.totalDocuments}",
                label = stringResource(R.string.home_stat_documents),
                isPrimary = true,
                modifier = Modifier.weight(1f)
            )
            StatCard(
                icon = Icons.Filled.Inbox,
                value = "${uiState.stats.pendingUploads}",
                label = stringResource(R.string.home_stat_pending),
                isPrimary = false,
                modifier = Modifier.weight(1f),
                onClick = onNavigateToPendingSync
            )
            StatCard(
                icon = Icons.Filled.Tag,
                value = "${uiState.untaggedCount}",
                label = stringResource(R.string.home_stat_untagged),
                isPrimary = false,
                modifier = Modifier.weight(1f),
                onClick = if (isServerReachable && uiState.untaggedCount > 0) {
                    onNavigateToSmartTagging
                } else {
                    null
                }
            )
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Processing Tasks Section (only show if there are tasks)
        if (uiState.processingTasks.isNotEmpty()) {
            Column(
                modifier = Modifier
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
                    IconButton(
                        onClick = { viewModel.refreshTasks() },
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

                Spacer(modifier = Modifier.height(12.dp))

                uiState.processingTasks.forEach { task ->
                    ProcessingTaskCard(
                        task = task,
                        onClick = {
                            task.documentId?.let { onDocumentClick(it) }
                        },
                        onDismiss = { viewModel.acknowledgeTask(task.id) }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        // Recent Documents Section
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.home_recently_added),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(
                    modifier = Modifier.clickable { onNavigateToDocuments() },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.home_see_all),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = stringResource(R.string.home_see_all),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (uiState.recentDocuments.isEmpty()) {
                // Empty state
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Description,
                            contentDescription = stringResource(R.string.home_no_documents),
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.home_no_documents),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = stringResource(R.string.home_scan_first),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                        )
                    }
                }
            } else {
                // Document list
                uiState.recentDocuments.forEach { doc ->
                    RecentDocumentCard(
                        document = doc,
                        onClick = { onDocumentClick(doc.id) }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // Tag Suggestions Bottom Sheet
    if (showTagSuggestionsSheet) {
        TagSuggestionsSheet(
            sheetState = tagSuggestionsSheetState,
            state = tagSuggestionsState,
            availableTags = availableTags,
            isAiAvailable = isAiAvailable,
            onDismiss = { viewModel.closeTagSuggestionsSheet() },
            onAnalyzeDocument = { documentId -> viewModel.analyzeDocument(documentId) },
            onApplyTags = { documentId, tagIds -> viewModel.applyTagsToDocument(documentId, tagIds) },
            onSkipDocument = { documentId -> viewModel.skipDocument(documentId) },
            onOpenTagPicker = { documentId -> viewModel.openTagPicker(documentId) },
            onCloseTagPicker = { viewModel.closeTagPicker() },
            onToggleTagInPicker = { documentId, tagId -> viewModel.toggleTagInPicker(documentId, tagId) },
            onApplyPickerTags = { documentId -> viewModel.applyPickerTags(documentId) },
            onUpgradeToPremium = { showPremiumUpgradeSheet = true },
            onCreateNewTag = { showCreateTagDialog = true }
        )
    }

    // Premium Upgrade Sheet (shown when non-premium user clicks AI button)
    if (showPremiumUpgradeSheet) {
        PremiumUpgradeSheet(
            onDismiss = { showPremiumUpgradeSheet = false },
            onSubscribe = { _ ->
                // Navigate to Settings where purchase flow continues
                showPremiumUpgradeSheet = false
                onNavigateToSettings()
            },
            onRestore = {
                // Navigate to Settings where restore continues
                showPremiumUpgradeSheet = false
                onNavigateToSettings()
            }
        )
    }

    // Create Tag Dialog (consistent with other screens)
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
private fun StatCard(
    icon: ImageVector,
    value: String,
    label: String,
    isPrimary: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    // Dark Tech Precision Pro Style Guide:
    // - Surface background (#141414)
    // - 1dp border with outline (#27272A) or primary (#E1FF8D) for primary card
    // - No elevation
    // - 20dp corner radius, 16dp padding
    val borderColor = if (isPrimary) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline
    }

    Card(
        onClick = onClick ?: {},
        enabled = onClick != null,
        modifier = modifier.border(
            width = 1.dp,
            color = borderColor,
            shape = RoundedCornerShape(20.dp)
        ),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(28.dp),
                tint = if (isPrimary) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = label.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RecentDocumentCard(
    document: RecentDocument,
    onClick: () -> Unit
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
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Document icon
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Description,
                    contentDescription = stringResource(R.string.cd_document_thumbnail),
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onPrimary
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = document.title,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Outlined.AccessTime,
                            contentDescription = document.timeAgo,
                            modifier = Modifier.size(12.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = document.timeAgo,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    document.tagName?.let { tag ->
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .background(
                                    document.tagColor?.let { Color(it).copy(alpha = 0.2f) }
                                        ?: MaterialTheme.colorScheme.primaryContainer
                                )
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = tag,
                                style = MaterialTheme.typography.labelSmall,
                                color = document.tagColor?.let { Color(it) }
                                    ?: MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }

            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = stringResource(R.string.cd_expand),
                modifier = Modifier.size(20.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// Helper for task styling
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

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = style.backgroundColor
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        enabled = task.status == TaskStatus.SUCCESS && task.documentId != null
    ) {
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

                // Dismiss button for completed tasks
                if (task.status == TaskStatus.SUCCESS || task.status == TaskStatus.FAILURE) {
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
}
