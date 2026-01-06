package com.paperless.scanner.ui.screens.batchimport

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.paperless.scanner.R

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BatchImportScreen(
    imageUris: List<Uri>,
    onImportSuccess: () -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: BatchImportViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val tags by viewModel.tags.collectAsState()
    val documentTypes by viewModel.documentTypes.collectAsState()
    val correspondents by viewModel.correspondents.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    val selectedTagIds = remember { mutableStateListOf<Int>() }
    var selectedDocumentTypeId by rememberSaveable { mutableStateOf<Int?>(null) }
    var documentTypeExpanded by remember { mutableStateOf(false) }
    var selectedCorrespondentId by rememberSaveable { mutableStateOf<Int?>(null) }
    var correspondentExpanded by remember { mutableStateOf(false) }
    var uploadAsSingleDocument by rememberSaveable { mutableStateOf(false) }

    // BEST PRACTICE: No manual loading needed!
    // BatchImportViewModel observes tags/types/correspondents via reactive Flows.
    // Dropdowns automatically populate and update when metadata changes.

    val successSingleMessage = stringResource(R.string.batch_import_success_single)
    val successMultipleMessage = stringResource(R.string.batch_import_success_multiple)

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.batch_import_title)) },
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
            // Image Count Card
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Photo,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.batch_import_images_selected, imageUris.size),
                            style = MaterialTheme.typography.titleLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = if (uploadAsSingleDocument)
                                stringResource(R.string.batch_import_mode_single)
                            else
                                stringResource(R.string.batch_import_mode_individual),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            // Upload Mode Selection
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                SegmentedButton(
                    selected = !uploadAsSingleDocument,
                    onClick = { uploadAsSingleDocument = false },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                ) {
                    Text(stringResource(R.string.batch_import_button_individual))
                }
                SegmentedButton(
                    selected = uploadAsSingleDocument,
                    onClick = { uploadAsSingleDocument = true },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                ) {
                    Text(stringResource(R.string.batch_import_button_single))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Image Grid Preview
            Text(
                text = stringResource(R.string.batch_import_preview),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(0.dp)
            ) {
                itemsIndexed(imageUris.take(9), key = { _, uri -> uri.toString() }) { index, uri ->
                    Box {
                        AsyncImage(
                            model = uri,
                            contentDescription = stringResource(R.string.batch_import_image_description, index + 1),
                            modifier = Modifier
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop
                        )
                        if (index == 8 && imageUris.size > 9) {
                            Box(
                                modifier = Modifier
                                    .matchParentSize()
                                    .clip(RoundedCornerShape(8.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = stringResource(R.string.batch_import_more_images, imageUris.size - 9),
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Document Type Dropdown
            Text(
                text = stringResource(R.string.batch_import_document_type),
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
                    value = documentTypes.find { it.id == selectedDocumentTypeId }?.name ?: stringResource(R.string.batch_import_not_selected),
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
                        text = { Text(stringResource(R.string.batch_import_not_selected)) },
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
                text = stringResource(R.string.batch_import_correspondent),
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
                    value = correspondents.find { it.id == selectedCorrespondentId }?.name ?: stringResource(R.string.batch_import_not_selected),
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
                        text = { Text(stringResource(R.string.batch_import_not_selected)) },
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
                text = stringResource(R.string.batch_import_tags),
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

            // Progress/Success Indicator
            when (val state = uiState) {
                is BatchImportUiState.Queuing -> {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            LinearProgressIndicator(
                                progress = { state.current.toFloat() / state.total.toFloat() },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = stringResource(R.string.batch_import_adding_progress, state.current, state.total),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
                else -> {}
            }

            // Import Button
            Button(
                onClick = {
                    viewModel.queueBatchImport(
                        imageUris = imageUris,
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
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.batch_import_button, imageUris.size),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }
    }
}
