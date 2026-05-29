package com.paperless.scanner.ui.screens.documents

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paperless.scanner.R

@Composable
fun MetadataTabContent(uiState: DocumentDetailUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(
            text = stringResource(R.string.tab_metadata),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(24.dp))

        MetadataItem(stringResource(R.string.tab_metadata_created), uiState.created)
        Spacer(modifier = Modifier.height(12.dp))
        MetadataItem(stringResource(R.string.tab_metadata_added), uiState.added)
        Spacer(modifier = Modifier.height(12.dp))
        MetadataItem(stringResource(R.string.tab_metadata_modified), uiState.modified)
        Spacer(modifier = Modifier.height(12.dp))
        MetadataItem(stringResource(R.string.tab_metadata_document_id), uiState.id.toString())

        uiState.originalFileName?.let {
            Spacer(modifier = Modifier.height(12.dp))
            MetadataItem(stringResource(R.string.tab_metadata_original_filename), it)
        }

        uiState.archiveSerialNumber?.let {
            Spacer(modifier = Modifier.height(12.dp))
            MetadataItem(stringResource(R.string.tab_metadata_archive_serial), it.toString())
        }
    }
}

@Composable
private fun MetadataItem(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
    }
}
