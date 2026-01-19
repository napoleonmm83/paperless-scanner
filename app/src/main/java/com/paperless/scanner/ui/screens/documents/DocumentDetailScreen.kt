package com.paperless.scanner.ui.screens.documents

import android.content.Intent
import android.net.Uri
import android.util.Log
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
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
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
import com.paperless.scanner.ui.screens.upload.CreateTagDialog

enum class DocumentTab {
    DETAILS,
    CONTENT,
    METADATA,
    NOTES,
    HISTORY,
    PERMISSIONS
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DocumentDetailScreen(
    onNavigateBack: () -> Unit,
    onOpenPdf: (Int, String) -> Unit,
    viewModel: DocumentDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val createTagState by viewModel.createTagState.collectAsState()
    val aiSuggestions by viewModel.aiSuggestions.collectAsState()
    val analysisState by viewModel.analysisState.collectAsState()
    val suggestionSource by viewModel.suggestionSource.collectAsState()
    val wifiRequired by viewModel.wifiRequired.collectAsState()
    val isWifiConnected by viewModel.isWifiConnected.collectAsState()
    val aiNewTagsEnabled by viewModel.aiNewTagsEnabled.collectAsState(initial = true)

    // DEBUG: Log aiNewTagsEnabled value
    Log.d("DocumentDetailScreen", "=== DocumentDetailScreen Debug ===")
    Log.d("DocumentDetailScreen", "aiNewTagsEnabled: $aiNewTagsEnabled")

    val context = LocalContext.current
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showEditSheet by remember { mutableStateOf(false) }
    var showCreateTagDialog by remember { mutableStateOf(false) }
    var newlyCreatedTagId by remember { mutableStateOf<Int?>(null) }
    var selectedTab by remember { mutableStateOf(DocumentTab.DETAILS) }

    val sheetState = rememberModalBottomSheetState()
    val scope = rememberCoroutineScope()

    // Navigate back when document is deleted successfully
    LaunchedEffect(uiState.deleteSuccess) {
        if (uiState.deleteSuccess) {
            onNavigateBack()
        }
    }

    // Close edit sheet when update is successful
    LaunchedEffect(uiState.updateSuccess) {
        if (uiState.updateSuccess) {
            scope.launch {
                sheetState.hide()
                showEditSheet = false
            }
            viewModel.resetUpdateSuccess()
        }
    }

    // Handle tag creation state
    LaunchedEffect(createTagState) {
        when (val tagState = createTagState) {
            is CreateTagState.Success -> {
                newlyCreatedTagId = tagState.tag.id
                showCreateTagDialog = false
                viewModel.resetCreateTagState()
            }
            is CreateTagState.Error -> {
                // Error is shown in dialog, just reset
                viewModel.resetCreateTagState()
            }
            else -> {}
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
                onClick = { showEditSheet = true },
                enabled = !uiState.isLoading && !uiState.isDeleting && !uiState.isUpdating
            ) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.document_detail_edit),
                    tint = MaterialTheme.colorScheme.primary
                )
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
                            contentDescription = stringResource(R.string.document_detail_error),
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
                Column(modifier = Modifier.fillMaxSize()) {
                    // Tab Row
                    ScrollableTabRow(
                        selectedTabIndex = selectedTab.ordinal,
                        containerColor = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.primary,
                        edgePadding = 16.dp
                    ) {
                        Tab(
                            selected = selectedTab == DocumentTab.DETAILS,
                            onClick = { selectedTab = DocumentTab.DETAILS },
                            text = { Text(stringResource(R.string.document_tab_details)) }
                        )
                        Tab(
                            selected = selectedTab == DocumentTab.CONTENT,
                            onClick = { selectedTab = DocumentTab.CONTENT },
                            text = { Text(stringResource(R.string.document_tab_content)) }
                        )
                        Tab(
                            selected = selectedTab == DocumentTab.METADATA,
                            onClick = { selectedTab = DocumentTab.METADATA },
                            text = { Text(stringResource(R.string.document_tab_metadata)) }
                        )
                        Tab(
                            selected = selectedTab == DocumentTab.NOTES,
                            onClick = { selectedTab = DocumentTab.NOTES },
                            text = { Text(stringResource(R.string.document_tab_notes)) }
                        )
                        Tab(
                            selected = selectedTab == DocumentTab.HISTORY,
                            onClick = { selectedTab = DocumentTab.HISTORY },
                            text = { Text(stringResource(R.string.document_tab_history)) }
                        )
                        Tab(
                            selected = selectedTab == DocumentTab.PERMISSIONS,
                            onClick = { selectedTab = DocumentTab.PERMISSIONS },
                            text = { Text(stringResource(R.string.document_tab_permissions)) }
                        )
                    }

                    // Tab Content
                    when (selectedTab) {
                        DocumentTab.DETAILS -> DetailsTabContent(
                            uiState = uiState,
                            context = context,
                            onOpenPdf = onOpenPdf
                        )
                        DocumentTab.CONTENT -> ContentTabContent(uiState = uiState)
                        DocumentTab.METADATA -> MetadataTabContent(uiState = uiState)
                        DocumentTab.NOTES -> NotesTabContent(
                            uiState = uiState,
                            onAddNote = { noteText -> viewModel.addNote(noteText) },
                            onDeleteNote = { noteId -> viewModel.deleteNote(noteId) }
                        )
                        DocumentTab.HISTORY -> HistoryTabContent(uiState = uiState)
                        DocumentTab.PERMISSIONS -> PermissionsTabContent(
                            uiState = uiState,
                            onLoadPermissionsData = { viewModel.loadPermissionsData() },
                            onSavePermissions = { owner, viewUsers, viewGroups, changeUsers, changeGroups ->
                                viewModel.updatePermissions(owner, viewUsers, viewGroups, changeUsers, changeGroups)
                            }
                        )
                    }
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

        // Edit Document Sheet
        if (showEditSheet) {
            ModalBottomSheet(
                onDismissRequest = {
                    showEditSheet = false
                    newlyCreatedTagId = null
                },
                sheetState = sheetState,
                containerColor = MaterialTheme.colorScheme.background
            ) {
                EditDocumentSheet(
                    title = uiState.title,
                    selectedTagIds = uiState.tagIds,
                    selectedCorrespondentId = uiState.correspondentId,
                    selectedDocumentTypeId = uiState.documentTypeId,
                    archiveSerialNumber = uiState.archiveSerialNumber,
                    availableTags = uiState.availableTags,
                    availableCorrespondents = uiState.availableCorrespondents,
                    availableDocumentTypes = uiState.availableDocumentTypes,
                    isUpdating = uiState.isUpdating,
                    newlyCreatedTagId = newlyCreatedTagId,
                    // AI Suggestions
                    isAiAvailable = viewModel.isAiAvailable,
                    analysisState = analysisState,
                    aiSuggestions = aiSuggestions,
                    suggestionSource = suggestionSource,
                    wifiRequired = wifiRequired,
                    isWifiConnected = isWifiConnected,
                    aiNewTagsEnabled = aiNewTagsEnabled,
                    onAnalyzeClick = { viewModel.analyzeDocumentThumbnail() },
                    onOverrideWifiOnly = { viewModel.overrideWifiOnlyForSession() },
                    onApplyTagSuggestion = { tagSuggestion ->
                        // Create new tag if it doesn't exist
                        viewModel.createTag(tagSuggestion.tagName)
                    },
                    onAiNewTagsEnabledChange = { enabled ->
                        viewModel.setAiNewTagsEnabled(enabled)
                    },
                    onCreateNewTag = { showCreateTagDialog = true },
                    onSave = { title, tagIds, correspondentId, documentTypeId, asn ->
                        viewModel.updateDocument(
                            title = title,
                            tagIds = tagIds,
                            correspondentId = correspondentId,
                            documentTypeId = documentTypeId,
                            archiveSerialNumber = asn,
                            created = null
                        )
                    }
                )
            }
        }

        // Create Tag Dialog
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
        title = {
            Text(
                text = stringResource(R.string.document_detail_delete_dialog_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.ExtraBold
            )
        },
        text = {
            Column {
                Text(stringResource(R.string.document_detail_delete_dialog_message))
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "\"$documentTitle\"",
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.document_detail_delete_dialog_warning),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )

                if (isDeleting) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    }
                }
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
                Text(
                    if (isDeleting)
                        stringResource(R.string.document_detail_delete_button_deleting)
                    else
                        stringResource(R.string.document_detail_delete_button)
                )
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                enabled = !isDeleting,
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(stringResource(R.string.document_detail_cancel_button))
            }
        },
        containerColor = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp)
    )
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
