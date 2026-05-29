package com.paperless.scanner.ui.screens.documents

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.paperless.scanner.R

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
                .clip(RoundedCornerShape(20.dp))
                .clickable { showDialog = true },
            shape = RoundedCornerShape(20.dp),
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
                        shape = RoundedCornerShape(20.dp)
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
