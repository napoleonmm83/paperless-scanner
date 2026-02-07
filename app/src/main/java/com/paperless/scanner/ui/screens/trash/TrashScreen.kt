package com.paperless.scanner.ui.screens.trash

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import kotlinx.coroutines.delay
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.paperless.scanner.R
import com.paperless.scanner.ui.components.CustomSnackbarHost
import com.paperless.scanner.ui.components.SnackbarIcon
import com.paperless.scanner.ui.components.showTypedSnackbar
import com.paperless.scanner.ui.theme.PaperlessAnimations

/**
 * TrashScreen - Display and manage soft-deleted documents.
 *
 * Features:
 * - List of deleted documents from Room cache (reactive Flow)
 * - Restore single or all documents
 * - Permanently delete single document or empty trash
 * - Auto-delete countdown display (days until permanent deletion)
 * - Material 3 Dark Tech Precision Pro theme
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TrashScreen(
    onNavigateBack: () -> Unit,
    viewModel: TrashViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    // Confirmation dialog state (only for empty trash bulk operation)
    var showEmptyTrashDialog by remember { mutableStateOf(false) }

    // Pull-to-refresh state
    var isRefreshing by remember { mutableStateOf(false) }

    // Show error as Snackbar
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            snackbarHostState.showTypedSnackbar(error, SnackbarIcon.ERROR)
            viewModel.clearError()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = stringResource(R.string.trash_title),
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.ExtraBold
                                )
                            )
                            if (uiState.totalCount > 0) {
                                Text(
                                    text = stringResource(R.string.documents_count, uiState.totalCount),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onNavigateBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.onboarding_back)
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
            // Bulk Actions (outside pull-to-refresh to stay fixed)
            if (uiState.documents.isNotEmpty()) {
                BulkActionsBar(
                    onRestoreAll = viewModel::restoreAllDocuments,
                    onEmptyTrash = { showEmptyTrashDialog = true },
                    isRestoring = uiState.isRestoring,
                    isDeleting = uiState.isDeleting
                )
            }

            // BEST PRACTICE: Pull-to-refresh for user-triggered server sync
            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = {
                    isRefreshing = true
                    viewModel.refreshTrash()
                    // Reset after delay (UI feedback)
                    coroutineScope.launch {
                        delay(1000)
                        isRefreshing = false
                    }
                }
            ) {
                // Document List
                when {
                    uiState.isLoading && !isRefreshing -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    uiState.documents.isEmpty() -> {
                        EmptyTrashState()
                    }
                    else -> {
                        TrashDocumentList(
                            documents = uiState.documents,
                            pendingDeletes = uiState.pendingDeletes,
                            onRestore = viewModel::restoreDocument,
                            onStartPendingDelete = viewModel::startPendingDelete,
                            onCancelPendingDelete = viewModel::cancelPendingDelete,
                            isRestoring = uiState.isRestoring,
                            isDeleting = uiState.isDeleting
                        )
                    }
                }
            }
        }
    }

        // Custom Snackbar with Dark Tech Precision Pro Design
        CustomSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }

    // Empty Trash Confirmation Dialog
    if (showEmptyTrashDialog) {
        AlertDialog(
            onDismissRequest = { showEmptyTrashDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.trash_confirm_empty_title),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold
                    )
                )
            },
            text = {
                Text(stringResource(R.string.trash_confirm_empty_message, uiState.totalCount))
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.emptyTrash()
                        showEmptyTrashDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text(stringResource(R.string.trash_empty_all))
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyTrashDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            }
        )
    }
}

/**
 * Bulk Actions Bar - Restore All / Empty Trash buttons.
 */
@Composable
private fun BulkActionsBar(
    onRestoreAll: () -> Unit,
    onEmptyTrash: () -> Unit,
    isRestoring: Boolean,
    isDeleting: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Restore All Button
        OutlinedButton(
            onClick = onRestoreAll,
            enabled = !isRestoring && !isDeleting,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.primary
            ),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Icon(
                imageVector = Icons.Default.RestoreFromTrash,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.trash_restore_all))
        }

        // Empty Trash Button
        Button(
            onClick = onEmptyTrash,
            enabled = !isRestoring && !isDeleting,
            modifier = Modifier.weight(1f),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            )
        ) {
            Icon(
                imageVector = Icons.Default.DeleteForever,
                contentDescription = null,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.trash_empty_all))
        }
    }
}

/**
 * Empty Trash State - Display when trash is empty.
 */
@Composable
private fun EmptyTrashState() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Delete,
                contentDescription = null,
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.outline
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(R.string.trash_empty),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.trash_empty_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * Trash Document List - LazyColumn of deleted documents.
 */
@Composable
private fun TrashDocumentList(
    documents: List<TrashDocumentItem>,
    pendingDeletes: Map<Int, PendingDeleteState>,
    onRestore: (Int) -> Unit,
    onStartPendingDelete: (Int) -> Unit,
    onCancelPendingDelete: (Int) -> Unit,
    isRestoring: Boolean,
    isDeleting: Boolean
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(
            items = documents,
            key = { it.id }
        ) { document ->
            val pendingDeleteState = pendingDeletes[document.id]
            TrashDocumentCard(
                document = document,
                pendingDeleteState = pendingDeleteState,
                onRestore = { onRestore(document.id) },
                onStartPendingDelete = { onStartPendingDelete(document.id) },
                onCancelPendingDelete = { onCancelPendingDelete(document.id) },
                isRestoring = isRestoring,
                isDeleting = isDeleting
            )
        }
    }
}

/**
 * Trash Document Card with Flip Animation and Countdown.
 *
 * Features:
 * - Front: Normal document card with Restore/Delete buttons
 * - Back: Red countdown card with progress indicator
 * - Tap back card to undo (flip back to front)
 * - After 30 seconds, document is permanently deleted
 *
 * BEST PRACTICE: Countdown state managed by ViewModel (not UI).
 * This ensures the countdown survives UI recomposition when cards
 * scroll out of view in LazyColumn.
 *
 * Dark Tech Precision Pro Theme:
 * - Corner Radius: 20dp
 * - Border: 1dp outline
 * - No Elevation (0.dp)
 * - Flip Animation: 300ms with EaseInOut
 */
@Composable
private fun TrashDocumentCard(
    document: TrashDocumentItem,
    pendingDeleteState: PendingDeleteState?,
    onRestore: () -> Unit,
    onStartPendingDelete: () -> Unit,
    onCancelPendingDelete: () -> Unit,
    isRestoring: Boolean,
    isDeleting: Boolean
) {
    val density = LocalDensity.current

    // Card is flipped if there's a pending delete state from ViewModel
    val isFlipped = pendingDeleteState != null
    val progress = pendingDeleteState?.progress ?: 1f
    val secondsRemaining = pendingDeleteState?.secondsRemaining ?: 30

    // Flip animation
    val rotation by animateFloatAsState(
        targetValue = if (isFlipped) 180f else 0f,
        animationSpec = tween(
            durationMillis = PaperlessAnimations.DURATION_MEDIUM,
            easing = PaperlessAnimations.EaseInOut
        ),
        label = "cardFlip"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                rotationY = rotation
                cameraDistance = 12f * density.density
            },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (rotation > 90f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    // Flip content on back side so it's not mirrored
                    rotationY = if (rotation > 90f) 180f else 0f
                }
        ) {
            if (rotation <= 90f) {
                // FRONT SIDE: Normal document card
                TrashDocumentCardFront(
                    document = document,
                    onRestore = onRestore,
                    onDelete = onStartPendingDelete,
                    isRestoring = isRestoring,
                    isDeleting = isDeleting
                )
            } else {
                // BACK SIDE: Red countdown card
                TrashDocumentCardBack(
                    document = document,
                    progress = progress,
                    secondsRemaining = secondsRemaining,
                    onUndo = onCancelPendingDelete
                )
            }
        }
    }
}

/**
 * Front side of card - normal document display with buttons.
 */
@Composable
private fun TrashDocumentCardFront(
    document: TrashDocumentItem,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
    isRestoring: Boolean,
    isDeleting: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
    ) {
        // Title
        Text(
            text = document.title,
            style = MaterialTheme.typography.titleMedium.copy(
                fontWeight = FontWeight.Bold
            ),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Deletion Info
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = stringResource(R.string.trash_deleted_at, document.deletedAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = document.daysUntilAutoDelete,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Action Buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Restore Button
            OutlinedButton(
                onClick = onRestore,
                enabled = !isRestoring && !isDeleting,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.primary
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Icon(
                    imageVector = Icons.Default.RestoreFromTrash,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.trash_restore))
            }

            // Delete Button
            Button(
                onClick = onDelete,
                enabled = !isRestoring && !isDeleting,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(
                    imageVector = Icons.Default.DeleteForever,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.trash_delete_permanent))
            }
        }
    }
}

/**
 * Back side of card - red countdown with progress.
 *
 * Features:
 * - Full height red background that empties from right to left
 * - Smooth animation via animateFloatAsState
 * - White text/icons for contrast
 * - Tap anywhere to undo
 */
@Composable
private fun TrashDocumentCardBack(
    document: TrashDocumentItem,
    progress: Float,
    secondsRemaining: Int,
    onUndo: () -> Unit
) {
    // Animate progress smoothly for fluid right-to-left emptying
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(
            durationMillis = 100, // Match countdown step duration
            easing = PaperlessAnimations.EaseInOut
        ),
        label = "progressAnimation"
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(160.dp) // Fixed height for full card
            .clickable(onClick = onUndo),
        contentAlignment = Alignment.Center
    ) {
        // Dark background (empty state)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
        )

        // Red progress bar (fills from right to left as it empties)
        // Using fillMaxWidth(animatedProgress) creates smooth right-to-left animation
        Box(
            modifier = Modifier
                .fillMaxWidth(animatedProgress) // Shrinks from right
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.9f))
                .align(Alignment.CenterStart) // Anchor to left, empties from right
        )

        // Content overlay (always centered)
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.DeleteForever,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.trash_deleting_in_seconds, secondsRemaining),
                style = MaterialTheme.typography.titleLarge.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = Color.White
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.trash_tap_to_undo),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.9f)
            )
        }
    }
}
