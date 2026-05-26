package com.paperless.scanner.ui.screens.labels

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.paperless.scanner.R
import com.paperless.scanner.ui.theme.PaperlessScannerTheme

/**
 * Empty state composable with entity-type-specific messages and icons.
 */
@Composable
fun EntityEmptyState(entityType: EntityType) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Entity-specific icon
        Icon(
            imageVector = when (entityType) {
                EntityType.TAG -> Icons.Default.Label
                EntityType.CORRESPONDENT -> Icons.Default.Person
                EntityType.DOCUMENT_TYPE -> Icons.Default.Description
                EntityType.CUSTOM_FIELD -> Icons.Default.DataObject
            },
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Entity-specific message
        Text(
            text = when (entityType) {
                EntityType.TAG -> stringResource(R.string.empty_tags)
                EntityType.CORRESPONDENT -> stringResource(R.string.empty_correspondents)
                EntityType.DOCUMENT_TYPE -> stringResource(R.string.empty_document_types)
                EntityType.CUSTOM_FIELD -> stringResource(R.string.empty_custom_fields)
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
    }
}

@Preview
@Composable
private fun EntityEmptyStatePreview() {
    PaperlessScannerTheme {
        EntityEmptyState(entityType = EntityType.TAG)
    }
}
