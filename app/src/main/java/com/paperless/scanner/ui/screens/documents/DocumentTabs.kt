package com.paperless.scanner.ui.screens.documents

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.paperless.scanner.R
import com.paperless.scanner.util.DateFormatter
import com.paperless.scanner.domain.model.Tag

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailsTabContent(
    uiState: DocumentDetailUiState,
    context: Context,
    onOpenPdf: (Int, String) -> Unit
) {
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
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(20.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (uiState.thumbnailUrl != null && uiState.authToken != null) {
                    // Auth header is automatically added by ImageLoader's OkHttp interceptor
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(uiState.thumbnailUrl)
                            .crossfade(true)
                            .build(),
                        contentDescription = stringResource(R.string.document_detail_preview),
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.Description,
                        contentDescription = stringResource(R.string.cd_document_thumbnail),
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
            fontWeight = FontWeight.ExtraBold
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
                            .background(
                                color = tagColor.copy(alpha = 0.2f),
                                shape = RoundedCornerShape(50)
                            )
                            .border(
                                width = 1.dp,
                                color = tagColor,
                                shape = RoundedCornerShape(50)
                            )
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
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(24.dp))
        }

        // Info Cards
        uiState.correspondent?.let {
            InfoCard(
                icon = Icons.Filled.Person,
                label = stringResource(R.string.document_detail_correspondent),
                value = it
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        uiState.documentType?.let {
            InfoCard(
                icon = Icons.Filled.Folder,
                label = stringResource(R.string.document_detail_type),
                value = it
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        InfoCard(
            icon = Icons.Filled.CalendarToday,
            label = stringResource(R.string.document_detail_created),
            value = uiState.created
        )

        Spacer(modifier = Modifier.height(12.dp))

        uiState.archiveSerialNumber?.let {
            InfoCard(
                icon = Icons.Filled.Description,
                label = stringResource(R.string.document_detail_asn),
                value = it.toString()
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        uiState.originalFileName?.let {
            InfoCard(
                icon = Icons.Filled.Download,
                label = stringResource(R.string.document_detail_filename),
                value = it
            )
            Spacer(modifier = Modifier.height(12.dp))
        }

        Spacer(modifier = Modifier.height(12.dp))

        // View PDF Button
        Button(
            onClick = {
                onOpenPdf(uiState.id, uiState.title)
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Description,
                contentDescription = stringResource(R.string.cd_pdf),
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.document_detail_view_pdf))
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun ContentTabContent(uiState: DocumentDetailUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(
            text = stringResource(R.string.document_detail_content),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (uiState.content.isNullOrBlank()) {
            Text(
                text = stringResource(R.string.tab_no_text_content),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
            ) {
                Text(
                    text = uiState.content,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
    }
}

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
fun NotesTabContent(
    uiState: DocumentDetailUiState,
    onAddNote: (String) -> Unit,
    onDeleteNote: (Int) -> Unit
) {
    var noteText by remember { mutableStateOf("") }
    var lastNoteCount by remember { mutableStateOf(uiState.notes.size) }
    val scrollState = rememberScrollState()

    // Clear text field and scroll to bottom after successful note addition
    LaunchedEffect(uiState.notes.size) {
        if (uiState.notes.size > lastNoteCount && !uiState.isAddingNote) {
            noteText = ""
            // Scroll to bottom to show new note
            kotlinx.coroutines.delay(100) // Small delay to ensure layout is complete
            scrollState.animateScrollTo(scrollState.maxValue)
        }
        lastNoteCount = uiState.notes.size
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(24.dp)
    ) {
        Text(
            text = stringResource(R.string.document_tab_notes),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Add note input
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(20.dp)
                    )
                    .padding(16.dp)
            ) {
                OutlinedTextField(
                    value = noteText,
                    onValueChange = { noteText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.tab_notes_placeholder)) },
                    enabled = !uiState.isAddingNote,
                    minLines = 3,
                    maxLines = 5
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Button(
                        onClick = {
                            onAddNote(noteText)
                        },
                        enabled = noteText.isNotBlank() && !uiState.isAddingNote
                    ) {
                        if (uiState.isAddingNote) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        } else {
                            Icon(
                                imageVector = Icons.Filled.Send,
                                contentDescription = stringResource(R.string.cd_send),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.tab_notes_add))
                    }
                }

                // Error message
                if (uiState.addNoteError != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = uiState.addNoteError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (uiState.notes.isEmpty()) {
            // Empty state
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Filled.Description,
                    contentDescription = stringResource(R.string.cd_no_notes),
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.tab_notes_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            // Notes list
            uiState.notes.forEachIndexed { index, note ->
                androidx.compose.runtime.key(note.id) {
                    NoteCard(
                        note = note,
                        onDelete = { onDeleteNote(note.id) },
                        isDeleting = uiState.isDeletingNoteId == note.id
                    )
                    if (index < uiState.notes.size - 1) {
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
            }
        }

        // Delete error message
        if (uiState.deleteNoteError != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = uiState.deleteNoteError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@Composable
fun HistoryTabContent(uiState: DocumentDetailUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(
            text = stringResource(R.string.tab_history),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (uiState.history.isEmpty()) {
            // Empty state
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Filled.CalendarToday,
                    contentDescription = stringResource(R.string.cd_calendar),
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.tab_history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            // History entries list
            uiState.history.forEach { entry ->
                HistoryEntryCard(entry = entry)
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
private fun HistoryEntryCard(entry: com.paperless.scanner.domain.model.AuditLogEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Header: Actor, Action, and Timestamp
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Person,
                        contentDescription = stringResource(R.string.cd_person),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = entry.actor?.username ?: stringResource(R.string.tab_history_system),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when (entry.action.lowercase()) {
                            "create" -> stringResource(R.string.tab_history_action_create)
                            "update" -> stringResource(R.string.tab_history_action_update)
                            "delete" -> stringResource(R.string.tab_history_action_delete)
                            else -> entry.action
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(
                    text = DateFormatter.formatDateWithTime(entry.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Changes (if any)
            if (entry.changes.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                entry.changes.forEach { (field, value) ->
                    // Parse the change value (can be List or complex object)
                    val changeText = when (value) {
                        is List<*> -> {
                            // Simple change: [old, new]
                            if (value.size >= 2) {
                                "\"${value[0]}\" → \"${value[1]}\""
                            } else {
                                value.joinToString(", ")
                            }
                        }
                        is Map<*, *> -> {
                            // Complex change (m2m, custom_field, etc.)
                            when (value["type"]) {
                                "m2m" -> {
                                    val operation = value["operation"] ?: "changed"
                                    val objects = (value["objects"] as? List<*>)?.joinToString(", ") ?: ""
                                    "$operation: $objects"
                                }
                                "custom_field" -> {
                                    val fieldName = value["field"] ?: ""
                                    val fieldValue = value["value"] ?: ""
                                    "$fieldName: $fieldValue"
                                }
                                else -> value.toString()
                            }
                        }
                        else -> value.toString()
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                    ) {
                        Text(
                            text = "$field:",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(100.dp)
                        )
                        Text(
                            text = changeText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionsTabContent(
    uiState: DocumentDetailUiState,
    onLoadPermissionsData: () -> Unit,
    onSavePermissions: (Int?, List<Int>, List<Int>, List<Int>, List<Int>) -> Unit
) {
    var isEditing by remember { mutableStateOf(false) }
    var selectedOwner by remember(uiState.owner) { mutableStateOf(uiState.owner) }
    var selectedViewUsers by remember(uiState.permissions) {
        mutableStateOf(uiState.permissions?.view?.users ?: emptyList())
    }
    var selectedViewGroups by remember(uiState.permissions) {
        mutableStateOf(uiState.permissions?.view?.groups ?: emptyList())
    }
    var selectedChangeUsers by remember(uiState.permissions) {
        mutableStateOf(uiState.permissions?.change?.users ?: emptyList())
    }
    var selectedChangeGroups by remember(uiState.permissions) {
        mutableStateOf(uiState.permissions?.change?.groups ?: emptyList())
    }

    // Load users/groups when entering edit mode
    LaunchedEffect(isEditing) {
        if (isEditing && uiState.availableUsers.isEmpty()) {
            onLoadPermissionsData()
        }
    }

    // Reset edit state when permissions update succeeds
    LaunchedEffect(uiState.updatePermissionsSuccess) {
        if (uiState.updatePermissionsSuccess) {
            isEditing = false
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        // Header with Edit Button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.document_tab_permissions),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )

            if (uiState.userCanChange && !isEditing) {
                IconButton(onClick = { isEditing = true }) {
                    Icon(
                        imageVector = Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.tab_permissions_edit),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        if (isEditing) {
            // Edit Mode
            if (uiState.isLoadingPermissionsData) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = stringResource(R.string.tab_permissions_loading),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                // Owner Dropdown
                PermissionDropdown(
                    label = stringResource(R.string.tab_permissions_owner),
                    selectedId = selectedOwner,
                    options = uiState.availableUsers.map { it.id to it.username },
                    onSelect = { selectedOwner = it },
                    allowNull = true
                )

                Spacer(modifier = Modifier.height(24.dp))

                // View Permissions
                Text(
                    text = stringResource(R.string.tab_permissions_view),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))

                PermissionMultiSelect(
                    label = stringResource(R.string.tab_permissions_select_users),
                    selectedIds = selectedViewUsers,
                    options = uiState.availableUsers.map { it.id to it.username },
                    onSelectionChange = { selectedViewUsers = it }
                )

                Spacer(modifier = Modifier.height(12.dp))

                PermissionMultiSelect(
                    label = stringResource(R.string.tab_permissions_select_groups),
                    selectedIds = selectedViewGroups,
                    options = uiState.availableGroups.map { it.id to it.name },
                    onSelectionChange = { selectedViewGroups = it }
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Change Permissions
                Text(
                    text = stringResource(R.string.tab_permissions_change),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(12.dp))

                PermissionMultiSelect(
                    label = stringResource(R.string.tab_permissions_select_users),
                    selectedIds = selectedChangeUsers,
                    options = uiState.availableUsers.map { it.id to it.username },
                    onSelectionChange = { selectedChangeUsers = it }
                )

                Spacer(modifier = Modifier.height(12.dp))

                PermissionMultiSelect(
                    label = stringResource(R.string.tab_permissions_select_groups),
                    selectedIds = selectedChangeGroups,
                    options = uiState.availableGroups.map { it.id to it.name },
                    onSelectionChange = { selectedChangeGroups = it }
                )

                Spacer(modifier = Modifier.height(24.dp))

                // Error message
                if (uiState.updatePermissionsError != null) {
                    Text(
                        text = uiState.updatePermissionsError,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Save/Cancel Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            isEditing = false
                            // Reset to original values
                            selectedOwner = uiState.owner
                            selectedViewUsers = uiState.permissions?.view?.users ?: emptyList()
                            selectedViewGroups = uiState.permissions?.view?.groups ?: emptyList()
                            selectedChangeUsers = uiState.permissions?.change?.users ?: emptyList()
                            selectedChangeGroups = uiState.permissions?.change?.groups ?: emptyList()
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !uiState.isUpdatingPermissions
                    ) {
                        Text(stringResource(R.string.tab_permissions_cancel))
                    }

                    Button(
                        onClick = {
                            onSavePermissions(
                                selectedOwner,
                                selectedViewUsers,
                                selectedViewGroups,
                                selectedChangeUsers,
                                selectedChangeGroups
                            )
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !uiState.isUpdatingPermissions
                    ) {
                        if (uiState.isUpdatingPermissions) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.tab_permissions_save))
                    }
                }
            }
        } else {
            // View Mode (original display)
            // Owner
            Text(
                text = stringResource(R.string.tab_permissions_owner),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            val ownerName = uiState.owner?.let { ownerId ->
                uiState.availableUsers.find { it.id == ownerId }?.username
                    ?: stringResource(R.string.tab_permissions_user_id, ownerId)
            } ?: stringResource(R.string.tab_permissions_no_owner)
            Text(text = ownerName, style = MaterialTheme.typography.bodyLarge)

            Spacer(modifier = Modifier.height(24.dp))

            // View Permissions
            if (uiState.permissions != null) {
                Text(
                    text = stringResource(R.string.tab_permissions_view),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (uiState.permissions.view.users.isEmpty() && uiState.permissions.view.groups.isEmpty()) {
                    Text(
                        text = stringResource(R.string.tab_permissions_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    if (uiState.permissions.view.users.isNotEmpty()) {
                        val userNames = uiState.permissions.view.users.map { userId ->
                            uiState.availableUsers.find { it.id == userId }?.username ?: "#$userId"
                        }.joinToString(", ")
                        Text(
                            text = stringResource(R.string.tab_permissions_users, userNames),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (uiState.permissions.view.groups.isNotEmpty()) {
                        val groupNames = uiState.permissions.view.groups.map { groupId ->
                            uiState.availableGroups.find { it.id == groupId }?.name ?: "#$groupId"
                        }.joinToString(", ")
                        Text(
                            text = stringResource(R.string.tab_permissions_groups, groupNames),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Change Permissions
                Text(
                    text = stringResource(R.string.tab_permissions_change),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (uiState.permissions.change.users.isEmpty() && uiState.permissions.change.groups.isEmpty()) {
                    Text(
                        text = stringResource(R.string.tab_permissions_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    if (uiState.permissions.change.users.isNotEmpty()) {
                        val userNames = uiState.permissions.change.users.map { userId ->
                            uiState.availableUsers.find { it.id == userId }?.username ?: "#$userId"
                        }.joinToString(", ")
                        Text(
                            text = stringResource(R.string.tab_permissions_users, userNames),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (uiState.permissions.change.groups.isNotEmpty()) {
                        val groupNames = uiState.permissions.change.groups.map { groupId ->
                            uiState.availableGroups.find { it.id == groupId }?.name ?: "#$groupId"
                        }.joinToString(", ")
                        Text(
                            text = stringResource(R.string.tab_permissions_groups, groupNames),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            } else {
                Text(
                    text = stringResource(R.string.tab_permissions_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PermissionDropdown(
    label: String,
    selectedId: Int?,
    options: List<Pair<Int, String>>,
    onSelect: (Int?) -> Unit,
    allowNull: Boolean = false
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedName = selectedId?.let { id -> options.find { it.first == id }?.second }
        ?: stringResource(R.string.tab_permissions_no_owner)

    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = it }
        ) {
            OutlinedTextField(
                value = selectedName,
                onValueChange = {},
                readOnly = true,
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                if (allowNull) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.tab_permissions_no_owner)) },
                        onClick = {
                            onSelect(null)
                            expanded = false
                        }
                    )
                }
                options.forEach { (id, name) ->
                    DropdownMenuItem(
                        text = { Text(name) },
                        onClick = {
                            onSelect(id)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PermissionMultiSelect(
    label: String,
    selectedIds: List<Int>,
    options: List<Pair<Int, String>>,
    onSelectionChange: (List<Int>) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }

    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { showDialog = true },
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(16.dp)
            ) {
                if (selectedIds.isEmpty()) {
                    Text(
                        text = stringResource(R.string.tab_permissions_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        selectedIds.forEach { id ->
                            val name = options.find { it.first == id }?.second ?: "#$id"
                            FilterChip(
                                selected = true,
                                onClick = {
                                    onSelectionChange(selectedIds - id)
                                },
                                label = { Text(name) },
                                trailingIcon = {
                                    Icon(
                                        imageVector = Icons.Filled.Close,
                                        contentDescription = stringResource(R.string.cd_delete),
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            )
                        }
                        // Add button
                        FilterChip(
                            selected = false,
                            onClick = { showDialog = true },
                            label = {
                                Icon(
                                    imageVector = Icons.Filled.Add,
                                    contentDescription = stringResource(R.string.cd_add),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        )
                    }
                }
            }
        }
    }

    if (showDialog) {
        PermissionSelectionDialog(
            title = label,
            options = options,
            selectedIds = selectedIds,
            onDismiss = { showDialog = false },
            onConfirm = { newSelection ->
                onSelectionChange(newSelection)
                showDialog = false
            }
        )
    }
}

@Composable
private fun PermissionSelectionDialog(
    title: String,
    options: List<Pair<Int, String>>,
    selectedIds: List<Int>,
    onDismiss: () -> Unit,
    onConfirm: (List<Int>) -> Unit
) {
    var tempSelection by remember { mutableStateOf(selectedIds.toMutableList()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                options.forEach { (id, name) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                tempSelection = if (id in tempSelection) {
                                    (tempSelection - id).toMutableList()
                                } else {
                                    (tempSelection + id).toMutableList()
                                }
                            }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = id in tempSelection,
                            onCheckedChange = { checked ->
                                tempSelection = if (checked) {
                                    (tempSelection + id).toMutableList()
                                } else {
                                    (tempSelection - id).toMutableList()
                                }
                            }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(name)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onConfirm(tempSelection) }) {
                Text(stringResource(R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.tab_permissions_cancel))
            }
        }
    )
}

@Composable
private fun InfoCard(
    icon: ImageVector,
    label: String,
    value: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
            }
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

@Composable
private fun NoteCard(
    note: com.paperless.scanner.domain.model.Note,
    onDelete: () -> Unit,
    isDeleting: Boolean
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline,
                    shape = RoundedCornerShape(20.dp)
                )
                .padding(16.dp)
        ) {
            // Header: User, Date, and Delete button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Person,
                        contentDescription = stringResource(R.string.cd_person),
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = note.user?.username ?: stringResource(R.string.tab_history_system),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = DateFormatter.formatDateWithTime(note.created),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                // Delete button
                IconButton(
                    onClick = onDelete,
                    enabled = !isDeleting
                ) {
                    if (isDeleting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Delete,
                            contentDescription = stringResource(R.string.tab_notes_delete),
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Note content
            Text(
                text = note.note,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
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
