package com.paperless.scanner.ui.screens.documents

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.paperless.scanner.R

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DocumentDetailScreen(
    onNavigateBack: () -> Unit,
    onOpenPdf: (Int, String) -> Unit,
    viewModel: DocumentDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    var showDeleteDialog by remember { mutableStateOf(false) }

    // Navigate back when document is deleted successfully
    LaunchedEffect(uiState.deleteSuccess) {
        if (uiState.deleteSuccess) {
            onNavigateBack()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.document_detail_back)
                )
            }
            Text(
                text = stringResource(R.string.document_detail_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.weight(1f)
            )
            if (uiState.downloadUrl != null && uiState.authToken != null) {
                IconButton(onClick = {
                    val downloadUrlWithAuth = "${uiState.downloadUrl}?auth_token=${uiState.authToken}"
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrlWithAuth))
                    context.startActivity(intent)
                }) {
                    Icon(
                        imageVector = Icons.Filled.OpenInBrowser,
                        contentDescription = stringResource(R.string.document_detail_open_browser)
                    )
                }
            }
            IconButton(
                onClick = { showDeleteDialog = true },
                enabled = !uiState.isLoading && !uiState.isDeleting
            ) {
                Icon(
                    imageVector = Icons.Filled.Delete,
                    contentDescription = stringResource(R.string.document_detail_delete),
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }

        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }
            uiState.error != null -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Filled.Error,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = uiState.error ?: stringResource(R.string.document_detail_error),
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { viewModel.loadDocument() }) {
                            Text(stringResource(R.string.document_detail_retry))
                        }
                    }
                }
            }
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp)
                ) {
                    // Thumbnail
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(0.7f),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            if (uiState.thumbnailUrl != null && uiState.authToken != null) {
                                AsyncImage(
                                    model = ImageRequest.Builder(context)
                                        .data(uiState.thumbnailUrl)
                                        .addHeader("Authorization", "Token ${uiState.authToken}")
                                        .crossfade(true)
                                        .build(),
                                    contentDescription = stringResource(R.string.document_detail_preview),
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Fit
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Filled.Description,
                                    contentDescription = null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    // Title
                    Text(
                        text = uiState.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // Tags
                    if (uiState.tags.isNotEmpty()) {
                        val primaryColor = MaterialTheme.colorScheme.primary
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            uiState.tags.forEach { tag ->
                                val tagColor = parseTagColor(tag.color, primaryColor)

                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(tagColor.copy(alpha = 0.2f))
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Box(
                                            modifier = Modifier
                                                .size(8.dp)
                                                .clip(CircleShape)
                                                .background(tagColor)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = tag.name,
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                    }

                    // Metadata Card
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            // Correspondent
                            uiState.correspondent?.let { correspondent ->
                                MetadataRow(
                                    icon = Icons.Filled.Person,
                                    label = stringResource(R.string.document_detail_correspondent),
                                    value = correspondent
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                            }

                            // Document Type
                            uiState.documentType?.let { docType ->
                                MetadataRow(
                                    icon = Icons.Filled.Folder,
                                    label = stringResource(R.string.document_detail_type),
                                    value = docType
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                            }

                            // Created Date
                            MetadataRow(
                                icon = Icons.Filled.CalendarToday,
                                label = stringResource(R.string.document_detail_created),
                                value = uiState.created
                            )

                            // Archive Serial Number
                            uiState.archiveSerialNumber?.let { asn ->
                                Spacer(modifier = Modifier.height(12.dp))
                                MetadataRow(
                                    icon = Icons.Filled.Description,
                                    label = stringResource(R.string.document_detail_asn),
                                    value = "#$asn"
                                )
                            }

                            // Original File Name
                            uiState.originalFileName?.let { fileName ->
                                Spacer(modifier = Modifier.height(12.dp))
                                MetadataRow(
                                    icon = Icons.Filled.Description,
                                    label = stringResource(R.string.document_detail_filename),
                                    value = fileName
                                )
                            }
                        }
                    }

                    // Content Preview
                    uiState.content?.takeIf { it.isNotBlank() }?.let { content ->
                        Spacer(modifier = Modifier.height(16.dp))
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Text(
                                    text = stringResource(R.string.document_detail_content),
                                    style = MaterialTheme.typography.labelLarge,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = content.take(500) + if (content.length > 500) "..." else "",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }

                    // PDF Viewer Button
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = {
                            onOpenPdf(uiState.id, uiState.title)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        shape = RoundedCornerShape(16.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Description,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.document_detail_view_pdf),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))
                }
            }
        }

        // Delete Confirmation Dialog
        if (showDeleteDialog) {
            DeleteConfirmationDialog(
                documentTitle = uiState.title,
                isDeleting = uiState.isDeleting,
                onConfirm = {
                    viewModel.deleteDocument()
                    showDeleteDialog = false
                },
                onDismiss = { showDeleteDialog = false }
            )
        }
    }
}

@Composable
private fun DeleteConfirmationDialog(
    documentTitle: String,
    isDeleting: Boolean,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { if (!isDeleting) onDismiss() },
        icon = {
            Icon(
                imageVector = Icons.Filled.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = stringResource(R.string.document_detail_delete_dialog_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold
            )
        },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.document_detail_delete_dialog_message),
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "\"$documentTitle\"",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.document_detail_delete_dialog_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                enabled = !isDeleting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                if (isDeleting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onError
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = if (isDeleting) stringResource(R.string.document_detail_delete_button_deleting) else stringResource(R.string.document_detail_delete_button),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                enabled = !isDeleting,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.document_detail_cancel_button),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
private fun MetadataRow(
    icon: ImageVector,
    label: String,
    value: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

private fun parseTagColor(colorString: String?, fallbackColor: Color): Color {
    if (colorString == null) return fallbackColor
    return try {
        if (colorString.startsWith("#")) {
            Color(android.graphics.Color.parseColor(colorString))
        } else {
            fallbackColor
        }
    } catch (e: Exception) {
        fallbackColor
    }
}
