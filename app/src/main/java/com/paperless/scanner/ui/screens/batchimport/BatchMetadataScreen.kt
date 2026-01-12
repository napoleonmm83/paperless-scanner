package com.paperless.scanner.ui.screens.batchimport

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.paperless.scanner.R
import com.paperless.scanner.ui.screens.upload.CreateTagDialog
import com.paperless.scanner.ui.screens.upload.components.CorrespondentDropdown
import com.paperless.scanner.ui.screens.upload.components.DocumentTypeDropdown
import com.paperless.scanner.ui.screens.upload.components.SuggestionsSection
import com.paperless.scanner.ui.screens.upload.components.TagSelectionSection
import com.paperless.scanner.util.FileUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BatchMetadataScreen(
    imageUris: List<Uri>,
    uploadAsSingleDocument: Boolean,
    onImportSuccess: () -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: BatchImportViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val tags by viewModel.tags.collectAsState()
    val documentTypes by viewModel.documentTypes.collectAsState()
    val correspondents by viewModel.correspondents.collectAsState()
    val createTagState by viewModel.createTagState.collectAsState()
    val aiSuggestions by viewModel.aiSuggestions.collectAsState()
    val analysisState by viewModel.analysisState.collectAsState()
    val suggestionSource by viewModel.suggestionSource.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showCreateTagDialog by remember { mutableStateOf(false) }

    // Only show title for single document mode
    var title by rememberSaveable { mutableStateOf("") }
    val selectedTagIds = remember { mutableStateListOf<Int>() }
    var selectedDocumentTypeId by rememberSaveable { mutableStateOf<Int?>(null) }
    var selectedCorrespondentId by rememberSaveable { mutableStateOf<Int?>(null) }
    val context = LocalContext.current

    val successSingleMessage = stringResource(R.string.batch_import_success_single)
    val successMultipleMessage = stringResource(R.string.batch_import_success_multiple)
    val tagCreatedMessage = stringResource(R.string.batch_import_tag_created)

    LaunchedEffect(uiState) {
        when (uiState) {
            is BatchImportUiState.Success -> {
                val count = (uiState as BatchImportUiState.Success).count
                val message = if (count == 1) successSingleMessage
                              else successMultipleMessage.format(count)
                snackbarHostState.showSnackbar(message)
                onImportSuccess()
            }
            is BatchImportUiState.Error -> {
                snackbarHostState.showSnackbar((uiState as BatchImportUiState.Error).message)
                viewModel.resetState()
            }
            else -> {}
        }
    }

    LaunchedEffect(createTagState) {
        when (val tagState = createTagState) {
            is CreateTagState.Success -> {
                selectedTagIds.add(tagState.tag.id)
                showCreateTagDialog = false
                viewModel.resetCreateTagState()
                snackbarHostState.showSnackbar(tagCreatedMessage.format(tagState.tag.name))
            }
            is CreateTagState.Error -> {
                snackbarHostState.showSnackbar(tagState.message)
                viewModel.resetCreateTagState()
            }
            else -> {}
        }
    }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.batch_metadata_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.batch_import_back)
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            // Preview Thumbnails
            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.height(80.dp)
            ) {
                itemsIndexed(imageUris.take(10), key = { _, uri -> uri.toString() }) { index, uri ->
                    SmallPreviewItem(uri = uri, index = index, totalCount = imageUris.size)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Mode Info Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = if (uploadAsSingleDocument) {
                            stringResource(R.string.batch_metadata_mode_single_info, imageUris.size)
                        } else {
                            stringResource(R.string.batch_metadata_mode_individual_info, imageUris.size)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Title Input - Only for single document mode
            if (uploadAsSingleDocument) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text(stringResource(R.string.batch_import_title_label)) },
                    placeholder = { Text(stringResource(R.string.batch_import_title_placeholder)) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))
            }

            // Document Type Dropdown
            DocumentTypeDropdown(
                documentTypes = documentTypes,
                selectedId = selectedDocumentTypeId,
                onSelect = { selectedDocumentTypeId = it }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Correspondent Dropdown
            CorrespondentDropdown(
                correspondents = correspondents,
                selectedId = selectedCorrespondentId,
                onSelect = { selectedCorrespondentId = it }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // AI Suggestions Section
            if (viewModel.isAiAvailable && imageUris.isNotEmpty()) {
                SuggestionsSection(
                    analysisState = analysisState,
                    suggestions = aiSuggestions,
                    suggestionSource = suggestionSource,
                    existingTags = tags,
                    selectedTagIds = selectedTagIds.toSet(),
                    currentTitle = title,
                    onAnalyzeClick = {
                        viewModel.analyzeDocument(imageUris.first())
                    },
                    onApplyTagSuggestion = { tagSuggestion ->
                        val tagId = tagSuggestion.tagId ?: tags.find {
                            it.name.equals(tagSuggestion.tagName, ignoreCase = true)
                        }?.id

                        if (tagId != null) {
                            if (!selectedTagIds.contains(tagId)) {
                                selectedTagIds.add(tagId)
                            }
                        } else {
                            viewModel.createTag(tagSuggestion.tagName)
                        }
                    },
                    onApplyTitle = { suggestedTitle ->
                        if (uploadAsSingleDocument) {
                            title = suggestedTitle
                        }
                    }
                )

                Spacer(modifier = Modifier.height(24.dp))
            }

            // Tags Section
            TagSelectionSection(
                tags = tags,
                selectedTagIds = selectedTagIds.toSet(),
                onToggleTag = { tagId ->
                    if (selectedTagIds.contains(tagId)) {
                        selectedTagIds.remove(tagId)
                    } else {
                        selectedTagIds.add(tagId)
                    }
                },
                onCreateNew = { showCreateTagDialog = true }
            )

            Spacer(modifier = Modifier.weight(1f))

            // Error Card
            val errorState = uiState as? BatchImportUiState.Error
            if (errorState != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = stringResource(R.string.cd_error),
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.batch_import_failed_title),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = errorState.message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        OutlinedButton(
                            onClick = { viewModel.resetState() }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.cd_refresh),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.batch_import_retry))
                        }
                    }
                }
            }

            // Progress Indicator
            val queuingState = uiState as? BatchImportUiState.Queuing
            if (queuingState != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    LinearProgressIndicator(
                        progress = { queuingState.current.toFloat() / queuingState.total.toFloat() },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.batch_import_adding_progress, queuingState.current, queuingState.total),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Import Button
            Button(
                onClick = {
                    viewModel.queueBatchImport(
                        imageUris = imageUris,
                        title = if (uploadAsSingleDocument) title.ifBlank { null } else null,
                        tagIds = selectedTagIds.toList(),
                        documentTypeId = selectedDocumentTypeId,
                        correspondentId = selectedCorrespondentId,
                        uploadAsSingleDocument = uploadAsSingleDocument
                    )
                },
                enabled = uiState is BatchImportUiState.Idle,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .height(56.dp)
            ) {
                if (uiState is BatchImportUiState.Queuing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(stringResource(R.string.batch_import_adding))
                } else {
                    Icon(
                        imageVector = Icons.Default.CloudUpload,
                        contentDescription = stringResource(R.string.cd_upload)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.batch_import_button),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}

@Composable
private fun SmallPreviewItem(
    uri: Uri,
    index: Int,
    totalCount: Int
) {
    val context = LocalContext.current
    val fileType = remember(uri) { getMetadataFileType(context, uri) }

    // For PDFs, render the first page as a thumbnail asynchronously
    val pdfThumbnail by produceState<Bitmap?>(initialValue = null, uri, fileType) {
        if (fileType == MetadataFileType.PDF) {
            value = withContext(Dispatchers.IO) {
                FileUtils.renderPdfFirstPage(context, uri, maxWidth = 200)
            }
        }
    }

    Box(
        modifier = Modifier
            .size(60.dp)
            .clip(RoundedCornerShape(8.dp))
    ) {
        when (fileType) {
            MetadataFileType.PDF -> {
                if (pdfThumbnail != null) {
                    // Show rendered PDF page thumbnail
                    Image(
                        bitmap = pdfThumbnail!!.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    // Show loading/placeholder while rendering
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.PictureAsPdf,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            MetadataFileType.IMAGE -> {
                AsyncImage(
                    model = uri,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }
            MetadataFileType.OTHER -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.InsertDriveFile,
                        contentDescription = null,
                        modifier = Modifier.size(28.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        // Show "+N" overlay on the last visible item if there are more
        if (index == 9 && totalCount > 10) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.7f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "+${totalCount - 10}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

private enum class MetadataFileType {
    PDF, IMAGE, OTHER
}

private fun getMetadataFileType(context: android.content.Context, uri: Uri): MetadataFileType {
    val mimeType = try {
        context.contentResolver.getType(uri)
    } catch (e: Exception) {
        null
    }

    if (mimeType != null) {
        return when {
            mimeType == "application/pdf" -> MetadataFileType.PDF
            mimeType.startsWith("image/") -> MetadataFileType.IMAGE
            else -> MetadataFileType.OTHER
        }
    }

    val uriString = uri.toString().lowercase()
    val path = uri.path?.lowercase() ?: ""

    return when {
        uriString.endsWith(".pdf") || path.endsWith(".pdf") -> MetadataFileType.PDF
        uriString.endsWith(".jpg") || uriString.endsWith(".jpeg") ||
        uriString.endsWith(".png") || uriString.endsWith(".gif") ||
        uriString.endsWith(".webp") || uriString.endsWith(".bmp") ||
        path.endsWith(".jpg") || path.endsWith(".jpeg") ||
        path.endsWith(".png") || path.endsWith(".gif") ||
        path.endsWith(".webp") || path.endsWith(".bmp") -> MetadataFileType.IMAGE
        else -> MetadataFileType.OTHER
    }
}
