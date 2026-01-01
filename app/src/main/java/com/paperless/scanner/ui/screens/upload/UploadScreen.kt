package com.paperless.scanner.ui.screens.upload

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import androidx.compose.material3.Button

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun UploadScreen(
    documentUri: Uri,
    onUploadSuccess: () -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: UploadViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val tags by viewModel.tags.collectAsState()
    val documentTypes by viewModel.documentTypes.collectAsState()
    val correspondents by viewModel.correspondents.collectAsState()
    val createTagState by viewModel.createTagState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    var showCreateTagDialog by remember { mutableStateOf(false) }

    var title by rememberSaveable { mutableStateOf("") }
    val selectedTagIds = remember { mutableStateListOf<Int>() }
    var selectedDocumentTypeId by rememberSaveable { mutableStateOf<Int?>(null) }
    var documentTypeExpanded by remember { mutableStateOf(false) }
    var selectedCorrespondentId by rememberSaveable { mutableStateOf<Int?>(null) }
    var correspondentExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.loadTags()
        viewModel.loadDocumentTypes()
        viewModel.loadCorrespondents()
    }

    LaunchedEffect(uiState) {
        when (uiState) {
            is UploadUiState.Success -> {
                snackbarHostState.showSnackbar("Dokument erfolgreich hochgeladen!")
                onUploadSuccess()
            }
            is UploadUiState.Error -> {
                snackbarHostState.showSnackbar((uiState as UploadUiState.Error).message)
            }
            else -> {}
        }
    }

    LaunchedEffect(createTagState) {
        when (createTagState) {
            is CreateTagState.Success -> {
                val newTag = (createTagState as CreateTagState.Success).tag
                selectedTagIds.add(newTag.id)
                showCreateTagDialog = false
                viewModel.resetCreateTagState()
                snackbarHostState.showSnackbar("Tag \"${newTag.name}\" erstellt")
            }
            is CreateTagState.Error -> {
                snackbarHostState.showSnackbar((createTagState as CreateTagState.Error).message)
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
                title = { Text("Dokument hochladen") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Zurück"
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
            // Document Preview
            AsyncImage(
                model = documentUri,
                contentDescription = "Document Preview",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(250.dp)
                    .padding(16.dp)
                    .clip(RoundedCornerShape(12.dp))
            )

            // Title Input
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                label = { Text("Titel (optional)") },
                placeholder = { Text("z.B. Rechnung Dezember 2024") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Document Type Dropdown
            Text(
                text = "Dokumententyp",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            ExposedDropdownMenuBox(
                expanded = documentTypeExpanded,
                onExpandedChange = { documentTypeExpanded = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                OutlinedTextField(
                    value = documentTypes.find { it.id == selectedDocumentTypeId }?.name ?: "Nicht ausgewählt",
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = documentTypeExpanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                )

                ExposedDropdownMenu(
                    expanded = documentTypeExpanded,
                    onDismissRequest = { documentTypeExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Nicht ausgewählt") },
                        onClick = {
                            selectedDocumentTypeId = null
                            documentTypeExpanded = false
                        }
                    )
                    documentTypes.forEach { docType ->
                        DropdownMenuItem(
                            text = { Text(docType.name) },
                            onClick = {
                                selectedDocumentTypeId = docType.id
                                documentTypeExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Correspondent Dropdown
            Text(
                text = "Korrespondent",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            ExposedDropdownMenuBox(
                expanded = correspondentExpanded,
                onExpandedChange = { correspondentExpanded = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                OutlinedTextField(
                    value = correspondents.find { it.id == selectedCorrespondentId }?.name ?: "Nicht ausgewählt",
                    onValueChange = {},
                    readOnly = true,
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = correspondentExpanded) },
                    modifier = Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth()
                )

                ExposedDropdownMenu(
                    expanded = correspondentExpanded,
                    onDismissRequest = { correspondentExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Nicht ausgewählt") },
                        onClick = {
                            selectedCorrespondentId = null
                            correspondentExpanded = false
                        }
                    )
                    correspondents.forEach { correspondent ->
                        DropdownMenuItem(
                            text = { Text(correspondent.name) },
                            onClick = {
                                selectedCorrespondentId = correspondent.id
                                correspondentExpanded = false
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Tags Section
            Text(
                text = "Tags auswählen",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            FlowRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Add new tag button
                AssistChip(
                    onClick = { showCreateTagDialog = true },
                    label = { Text("Neu") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = null,
                            modifier = Modifier.size(AssistChipDefaults.IconSize)
                        )
                    }
                )

                tags.forEach { tag ->
                    FilterChip(
                        selected = selectedTagIds.contains(tag.id),
                        onClick = {
                            if (selectedTagIds.contains(tag.id)) {
                                selectedTagIds.remove(tag.id)
                            } else {
                                selectedTagIds.add(tag.id)
                            }
                        },
                        label = { Text(tag.name) }
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Error Card with Retry
            if (uiState is UploadUiState.Error) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Upload fehlgeschlagen",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                text = (uiState as UploadUiState.Error).message,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        OutlinedButton(
                            onClick = { viewModel.retry() }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Retry")
                        }
                    }
                }
            }

            // Upload Progress
            if (uiState is UploadUiState.Uploading) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                ) {
                    val progress = (uiState as UploadUiState.Uploading).progress
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Hochladen: ${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Upload Button
            Button(
                onClick = {
                    viewModel.uploadDocument(
                        uri = documentUri,
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
                        text = "Wird hochgeladen...",
                        style = MaterialTheme.typography.titleMedium
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.CloudUpload,
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = "Hochladen",
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}
