package com.paperless.scanner.ui.screens.upload

import android.net.Uri
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.paperless.scanner.R
import com.paperless.scanner.ui.screens.upload.components.CorrespondentDropdown
import com.paperless.scanner.ui.screens.upload.components.DocumentTypeDropdown
import com.paperless.scanner.ui.screens.upload.components.SuggestionsSection
import com.paperless.scanner.ui.screens.upload.components.TagSelectionSection

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MultiPageUploadScreen(
    documentUris: List<Uri>,
    preSelectedTagIds: List<Int> = emptyList(),
    preTitle: String? = null,
    preDocumentTypeId: Int? = null,
    preCorrespondentId: Int? = null,
    onUploadSuccess: () -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: UploadViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val tags by viewModel.tags.collectAsState()
    val documentTypes by viewModel.documentTypes.collectAsState()
    val correspondents by viewModel.correspondents.collectAsState()
    val createTagState by viewModel.createTagState.collectAsState()
    val aiSuggestions by viewModel.aiSuggestions.collectAsState()
    val analysisState by viewModel.analysisState.collectAsState()
    val suggestionSource by viewModel.suggestionSource.collectAsState()
    val aiNewTagsEnabled by viewModel.aiNewTagsEnabled.collectAsState(initial = true)
    val snackbarHostState = remember { SnackbarHostState() }
    var showCreateTagDialog by remember { mutableStateOf(false) }

    var title by rememberSaveable { mutableStateOf(preTitle ?: "") }
    val selectedTagIds = remember { mutableStateListOf<Int>() }
    var selectedDocumentTypeId by rememberSaveable { mutableStateOf(preDocumentTypeId) }
    var selectedCorrespondentId by rememberSaveable { mutableStateOf(preCorrespondentId) }

    // Initialize with pre-selected tags from ScanScreen
    LaunchedEffect(preSelectedTagIds) {
        if (preSelectedTagIds.isNotEmpty() && selectedTagIds.isEmpty()) {
            selectedTagIds.addAll(preSelectedTagIds)
        }
    }

    // BEST PRACTICE: No manual loading needed!
    // UploadViewModel observes tags/types/correspondents via reactive Flows.
    // Dropdowns automatically populate and update when metadata changes.

    val successMessage = stringResource(R.string.multipage_upload_success, documentUris.size)
    val queuedMessage = stringResource(R.string.multipage_upload_queued)
    val tagCreatedMessage = stringResource(R.string.multipage_upload_tag_created)

    LaunchedEffect(uiState) {
        when (val state = uiState) {
            is UploadUiState.Success -> {
                snackbarHostState.showSnackbar(successMessage)
                onUploadSuccess()
            }
            is UploadUiState.Queued -> {
                snackbarHostState.showSnackbar(queuedMessage)
                onUploadSuccess() // Navigate back
            }
            is UploadUiState.Error -> {
                snackbarHostState.showSnackbar(state.userMessage)
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
                title = { Text(stringResource(R.string.multipage_upload_title)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.multipage_upload_back)
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
            // PDF Info Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.PictureAsPdf,
                        contentDescription = stringResource(R.string.cd_pdf),
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.multipage_upload_info, documentUris.size),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            // Page Thumbnails
            Text(
                text = stringResource(R.string.multipage_upload_preview),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(documentUris, key = { _, uri -> uri.toString() }) { index, uri ->
                    Card(
                        modifier = Modifier.width(100.dp),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                    ) {
                        Box {
                            AsyncImage(
                                model = uri,
                                contentDescription = stringResource(R.string.multipage_upload_page_description, index + 1),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .aspectRatio(0.7f),
                                contentScale = ContentScale.Crop
                            )
                            Box(
                                modifier = Modifier
                                    .align(Alignment.TopStart)
                                    .padding(4.dp)
                                    .size(24.dp)
                                    .background(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = CircleShape
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${index + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Title Input
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text(stringResource(R.string.multipage_upload_title_label)) },
                placeholder = { Text(stringResource(R.string.multipage_upload_title_placeholder)) },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

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

            // AI Suggestions Section - Only shown when AI is available (Debug/Premium)
            if (viewModel.isAiAvailable && documentUris.isNotEmpty()) {
                SuggestionsSection(
                    analysisState = analysisState,
                    suggestions = aiSuggestions,
                    suggestionSource = suggestionSource,
                    existingTags = tags,
                    selectedTagIds = selectedTagIds.toSet(),
                    currentTitle = title,
                    aiNewTagsEnabled = aiNewTagsEnabled,
                    onAnalyzeClick = {
                        // Analyze the first page
                        viewModel.analyzeDocument(documentUris.first())
                    },
                    onAiNewTagsEnabledChange = { enabled ->
                        viewModel.setAiNewTagsEnabled(enabled)
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
                        title = suggestedTitle
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

            // Error Card with Retry and expandable technical details
            val errorState = uiState as? UploadUiState.Error
            if (errorState != null) {
                var showDetails by remember { mutableStateOf(false) }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    ),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
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
                                    text = stringResource(R.string.multipage_upload_failed_title),
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Text(
                                    text = errorState.userMessage,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }

                        // Technical details (expandable)
                        if (errorState.technicalDetails != null) {
                            TextButton(
                                onClick = { showDetails = !showDetails },
                                modifier = Modifier.padding(top = 8.dp)
                            ) {
                                Text(
                                    text = if (showDetails) "Details ausblenden" else "Details anzeigen",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                                Icon(
                                    imageVector = if (showDetails) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }

                            if (showDetails) {
                                Text(
                                    text = errorState.technicalDetails,
                                    style = MaterialTheme.typography.bodySmall.copy(
                                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                                    ),
                                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 4.dp, bottom = 8.dp)
                                )
                            }
                        }

                        // Retry button
                        if (viewModel.canRetry()) {
                            OutlinedButton(
                                onClick = { viewModel.retry() },
                                modifier = Modifier.padding(top = 8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = stringResource(R.string.cd_refresh),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(stringResource(R.string.multipage_upload_retry_button))
                            }
                        }
                    }
                }
            }

            // Retrying State (Auto-Retry in progress)
            val retryingState = uiState as? UploadUiState.Retrying
            if (retryingState != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "Wiederhole Upload...",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            Text(
                                text = "Versuch ${retryingState.attempt} von ${retryingState.maxAttempts}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }

            // Upload Progress
            val uploadingState = uiState as? UploadUiState.Uploading
            if (uploadingState != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    LinearProgressIndicator(
                        progress = { uploadingState.progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.multipage_upload_progress, (uploadingState.progress * 100).toInt()),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Upload Button
            Button(
                onClick = {
                    viewModel.uploadMultiPageDocument(
                        uris = documentUris,
                        title = title.ifBlank { null },
                        tagIds = selectedTagIds.toList(),
                        documentTypeId = selectedDocumentTypeId,
                        correspondentId = selectedCorrespondentId
                    )
                },
                enabled = uiState !is UploadUiState.Uploading,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .height(56.dp)
            ) {
                if (uiState is UploadUiState.Uploading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.multipage_upload_uploading),
                        style = MaterialTheme.typography.titleMedium
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.CloudUpload,
                        contentDescription = stringResource(R.string.cd_upload)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.multipage_upload_button),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}
