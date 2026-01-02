package com.paperless.scanner.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Tag
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.paperless.scanner.ui.navigation.Screen

enum class NavItem(
    val screen: Screen,
    val iconFilled: ImageVector,
    val iconOutlined: ImageVector,
    val label: String
) {
    Home(Screen.Home, Icons.Filled.Home, Icons.Outlined.Home, "Home"),
    Documents(Screen.Documents, Icons.Filled.Description, Icons.Outlined.Description, "Dokumente"),
    Scan(Screen.Scan, Icons.Filled.Add, Icons.Filled.Add, "Scannen"),
    Labels(Screen.Labels, Icons.Filled.Tag, Icons.Outlined.Tag, "Labels"),
    Settings(Screen.Settings, Icons.Filled.Person, Icons.Outlined.Person, "Profil")
}

@Composable
fun BottomNavBar(
    currentRoute: String,
    onNavigate: (Screen) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier.padding(horizontal = 24.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.primary,
            shadowElevation = 8.dp,
            modifier = Modifier.shadow(
                elevation = 16.dp,
                shape = RoundedCornerShape(50),
                ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
                spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
            )
        ) {
            Row(
                modifier = Modifier.padding(6.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                NavItem.entries.forEach { item ->
                    val isSelected = currentRoute == item.screen.route
                    val isScanButton = item == NavItem.Scan

                    if (isScanButton) {
                        // Central FAB-style button
                        ScanNavButton(
                            isSelected = isSelected,
                            onClick = { onNavigate(item.screen) }
                        )
                    } else {
                        // Regular nav buttons
                        NavButton(
                            item = item,
                            isSelected = isSelected,
                            onClick = { onNavigate(item.screen) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NavButton(
    item: NavItem,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(
                if (isSelected) MaterialTheme.colorScheme.surface
                else MaterialTheme.colorScheme.primary
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isSelected) item.iconFilled else item.iconOutlined,
            contentDescription = item.label,
            modifier = Modifier.size(22.dp),
            tint = if (isSelected)
                MaterialTheme.colorScheme.onSurface
            else
                MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.9f)
        )
    }
}

@Composable
private fun ScanNavButton(
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .size(56.dp)
            .clip(CircleShape)
            .background(
                if (isSelected) MaterialTheme.colorScheme.surface
                else MaterialTheme.colorScheme.onPrimary
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Filled.Add,
            contentDescription = "Scannen",
            modifier = Modifier.size(28.dp),
            tint = if (isSelected)
                MaterialTheme.colorScheme.onSurface
            else
                MaterialTheme.colorScheme.primary
        )
    }
}
