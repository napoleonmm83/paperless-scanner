package com.paperless.scanner.ui.screens.documents

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import coil.compose.AsyncImage
import coil.request.ImageRequest
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
                contentDescription = null,
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
                text = "Kein Textinhalt verfügbar",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
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
            text = "Metadaten",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(24.dp))

        MetadataItem("Erstellt", uiState.created)
        Spacer(modifier = Modifier.height(12.dp))
        MetadataItem("Hinzugefügt", uiState.added)
        Spacer(modifier = Modifier.height(12.dp))
        MetadataItem("Geändert", uiState.modified)
        Spacer(modifier = Modifier.height(12.dp))
        MetadataItem("Dokument-ID", uiState.id.toString())

        uiState.originalFileName?.let {
            Spacer(modifier = Modifier.height(12.dp))
            MetadataItem("Original-Dateiname", it)
        }

        uiState.archiveSerialNumber?.let {
            Spacer(modifier = Modifier.height(12.dp))
            MetadataItem("Archivnummer (ASN)", it.toString())
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
                    placeholder = { Text("Neue Notiz hinzufügen...") },
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
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text("Hinzufügen")
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
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Keine Notizen vorhanden",
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
            text = "Verlauf",
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
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Keine Änderungen vorhanden",
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
        )
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
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = entry.actor?.username ?: "System",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = when (entry.action.lowercase()) {
                            "create" -> "Erstellt"
                            "update" -> "Aktualisiert"
                            "delete" -> "Gelöscht"
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
fun PermissionsTabContent(uiState: DocumentDetailUiState) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp)
    ) {
        Text(
            text = stringResource(R.string.document_tab_permissions),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Owner
        Text(
            text = "Besitzer",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = if (uiState.owner != null) "Benutzer #${uiState.owner}" else "Kein Besitzer",
            style = MaterialTheme.typography.bodyLarge
        )

        Spacer(modifier = Modifier.height(24.dp))

        // View Permissions
        if (uiState.permissions != null) {
            Text(
                text = "Ansichtsberechtigungen",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (uiState.permissions.view.users.isEmpty() && uiState.permissions.view.groups.isEmpty()) {
                Text(
                    text = "Keine spezifischen Berechtigungen",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                if (uiState.permissions.view.users.isNotEmpty()) {
                    Text(
                        text = "Benutzer: ${uiState.permissions.view.users.joinToString(", ")}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (uiState.permissions.view.groups.isNotEmpty()) {
                    Text(
                        text = "Gruppen: ${uiState.permissions.view.groups.joinToString(", ")}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Change Permissions
            Text(
                text = "Änderungsberechtigungen",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))

            if (uiState.permissions.change.users.isEmpty() && uiState.permissions.change.groups.isEmpty()) {
                Text(
                    text = "Keine spezifischen Berechtigungen",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                if (uiState.permissions.change.users.isNotEmpty()) {
                    Text(
                        text = "Benutzer: ${uiState.permissions.change.users.joinToString(", ")}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                if (uiState.permissions.change.groups.isNotEmpty()) {
                    Text(
                        text = "Gruppen: ${uiState.permissions.change.groups.joinToString(", ")}",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        } else {
            Text(
                text = "Keine Berechtigungsinformationen verfügbar",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
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
        )
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
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
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = note.user?.username ?: "System",
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
                            contentDescription = "Notiz löschen",
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
