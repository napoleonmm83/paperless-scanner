package com.paperless.scanner.ui.screens.labels

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DataObject
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Material 3 TabRow for switching between entity types.
 * Displays icon-only tabs for Tags, Correspondents, Document Types, and Custom Fields.
 * Custom Fields tab is only shown if the API supports it.
 *
 * @param selectedType The currently selected entity type
 * @param customFieldsAvailable Whether the Custom Fields API is available on the server
 * @param onTypeSelected Callback when a tab is selected
 */
@Composable
fun EntityTypeTabs(
    selectedType: EntityType,
    customFieldsAvailable: Boolean,
    onTypeSelected: (EntityType) -> Unit
) {
    // Build list of available tabs (EntityType to Icon) based on API availability
    val tabs = buildList {
        add(EntityType.TAG to Icons.Default.Label)
        add(EntityType.CORRESPONDENT to Icons.Default.Person)
        add(EntityType.DOCUMENT_TYPE to Icons.Default.Description)
        if (customFieldsAvailable) {
            add(EntityType.CUSTOM_FIELD to Icons.Default.DataObject)
        }
    }

    TabRow(
        selectedTabIndex = tabs.indexOfFirst { it.first == selectedType }.coerceAtLeast(0)
    ) {
        tabs.forEach { (type, icon) ->
            Tab(
                selected = selectedType == type,
                onClick = { onTypeSelected(type) },
                icon = {
                    Icon(
                        imageVector = icon,
                        contentDescription = type.name
                    )
                }
            )
        }
    }
}

/**
 * Returns the icon for a given entity type.
 */
fun getEntityTypeIcon(entityType: EntityType): ImageVector {
    return when (entityType) {
        EntityType.TAG -> Icons.Default.Label
        EntityType.CORRESPONDENT -> Icons.Default.Person
        EntityType.DOCUMENT_TYPE -> Icons.Default.Description
        EntityType.CUSTOM_FIELD -> Icons.Default.DataObject
    }
}
