package com.paperless.scanner.ui.components.documentlist

import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * DocumentListSnackbar - Reusable snackbar handler for document deletion with undo.
 *
 * **FEATURES:**
 * - Automatic 8-second auto-dismiss timer
 * - Race condition prevention (dismisses existing snackbar before showing new one)
 * - Undo action support
 * - Manual dismiss support
 *
 * **PATTERN:**
 * This composable encapsulates the complex snackbar logic that was duplicated
 * in HomeScreen and DocumentsScreen:
 * - Waits for deletedDocumentId to change
 * - Dismisses any existing snackbar to prevent race conditions
 * - Shows new snackbar with 8-second auto-dismiss
 * - Cancels auto-dismiss if user interacts (undo/dismiss)
 *
 * **CRITICAL - RACE CONDITION FIX:**
 * When multiple deletes happen quickly, the LaunchedEffect ensures:
 * 1. Previous snackbar is dismissed before showing new one
 * 2. Auto-dismiss job is cancelled if user interacts
 * 3. Clean state cleanup after each interaction
 *
 * **USAGE:**
 * ```kotlin
 * val snackbarHostState = remember { SnackbarHostState() }
 *
 * DocumentListSnackbar(
 *     snackbarHostState = snackbarHostState,
 *     deletedDocumentId = uiState.deletedDocument?.id,
 *     message = stringResource(R.string.documents_deleted_snackbar),
 *     actionLabel = stringResource(R.string.documents_undo),
 *     onUndo = { viewModel.undoDelete() },
 *     onDismiss = { viewModel.clearDeletedDocument() }
 * )
 *
 * // In Scaffold:
 * Scaffold(
 *     snackbarHost = { CustomSnackbarHost(hostState = snackbarHostState) }
 * ) { ... }
 * ```
 *
 * @param snackbarHostState SnackbarHostState from remember
 * @param deletedDocumentId Unique ID that triggers snackbar (null = no snackbar)
 * @param message Snackbar message text
 * @param actionLabel Undo button label
 * @param onUndo Callback when undo button is pressed
 * @param onDismiss Callback when snackbar is dismissed (manually or auto)
 */
@Composable
fun DocumentListSnackbar(
    snackbarHostState: SnackbarHostState,
    deletedDocumentId: Int?,
    message: String,
    actionLabel: String,
    onUndo: () -> Unit,
    onDismiss: () -> Unit
) {
    LaunchedEffect(deletedDocumentId) {
        // Only show snackbar if deletedDocumentId is not null
        if (deletedDocumentId == null) return@LaunchedEffect

        // Dismiss any existing snackbar first to prevent race conditions
        snackbarHostState.currentSnackbarData?.dismiss()

        // Launch auto-dismiss timer in parallel
        val autoDismissJob = launch {
            delay(8000L) // 8 seconds
            snackbarHostState.currentSnackbarData?.dismiss()
        }

        try {
            val result = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = actionLabel,
                duration = SnackbarDuration.Indefinite, // Prevents auto-dismiss during state changes
                withDismissAction = true
            )

            // Cancel auto-dismiss if user interacted
            autoDismissJob.cancel()

            when (result) {
                SnackbarResult.ActionPerformed -> onUndo()
                SnackbarResult.Dismissed -> onDismiss()
            }
        } finally {
            autoDismissJob.cancel()
        }
    }
}
