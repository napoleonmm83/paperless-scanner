package com.paperless.scanner.ui.screens.labels

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.paperless.scanner.R
import com.paperless.scanner.ui.theme.PaperlessScannerTheme

@Composable
fun DeleteConfirmationDialog(
    pendingDelete: PendingDeleteEntity,
    isDeleting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val message = when {
        pendingDelete.documentCount == 0 -> stringResource(
            R.string.labels_delete_dialog_message_no_docs,
            pendingDelete.name
        )
        pendingDelete.documentCount == 1 -> stringResource(
            R.string.labels_delete_dialog_message_one_doc,
            pendingDelete.name
        )
        else -> stringResource(
            R.string.labels_delete_dialog_message_with_docs,
            pendingDelete.name,
            pendingDelete.documentCount
        )
    }

    AlertDialog(
        onDismissRequest = { if (!isDeleting) onDismiss() },
        title = { Text(stringResource(R.string.labels_delete_dialog_title)) },
        text = {
            Column {
                Text(message)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.labels_delete_dialog_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = !isDeleting
            ) {
                Text(
                    stringResource(R.string.labels_delete_button),
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isDeleting
            ) {
                Text(stringResource(R.string.labels_cancel_button))
            }
        }
    )
}

@Preview
@Composable
private fun DeleteConfirmationDialogPreview() {
    PaperlessScannerTheme {
        DeleteConfirmationDialog(
            pendingDelete = PendingDeleteEntity(
                id = 1,
                name = "Invoices",
                documentCount = 5,
                entityType = EntityType.TAG
            ),
            isDeleting = false,
            onConfirm = {},
            onDismiss = {}
        )
    }
}
