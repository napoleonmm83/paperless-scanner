package com.paperless.scanner.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.PermanentDrawerSheet
import androidx.compose.material3.PermanentNavigationDrawer
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.paperless.scanner.R
import com.paperless.scanner.ui.navigation.Screen
import com.paperless.scanner.ui.theme.LocalWindowSizeClass

/**
 * Adaptive navigation component that adjusts layout based on screen size:
 * - Compact (phones): Bottom navigation bar
 * - Medium (tablets portrait): Navigation rail on the left
 * - Expanded (tablets landscape): Permanent navigation drawer
 */
@Composable
fun AdaptiveNavigation(
    currentRoute: String,
    onNavigate: (Screen) -> Unit,
    content: @Composable () -> Unit
) {
    val windowSizeClass = LocalWindowSizeClass.current

    when (windowSizeClass.widthSizeClass) {
        WindowWidthSizeClass.Compact -> {
            // Phone: Bottom Navigation Bar
            Scaffold(
                contentWindowInsets = WindowInsets(0, 0, 0, 0),
                bottomBar = {
                    BottomNavBar(
                        currentRoute = currentRoute,
                        onNavigate = onNavigate
                    )
                }
            ) { paddingValues ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .padding(paddingValues)
                ) {
                    content()
                }
            }
        }
        WindowWidthSizeClass.Medium -> {
            // Tablet Portrait: Navigation Rail
            Row(modifier = Modifier.fillMaxSize()) {
                NavigationRailContent(
                    currentRoute = currentRoute,
                    onNavigate = onNavigate
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.statusBars)
                ) {
                    content()
                }
            }
        }
        WindowWidthSizeClass.Expanded -> {
            // Tablet Landscape: Permanent Navigation Drawer
            PermanentNavigationDrawer(
                drawerContent = {
                    NavigationDrawerContent(
                        currentRoute = currentRoute,
                        onNavigate = onNavigate
                    )
                }
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.statusBars)
                ) {
                    content()
                }
            }
        }
    }
}

@Composable
private fun NavigationRailContent(
    currentRoute: String,
    onNavigate: (Screen) -> Unit
) {
    NavigationRail(
        modifier = Modifier
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars),
        containerColor = MaterialTheme.colorScheme.surface,
        header = {
            Spacer(modifier = Modifier.height(16.dp))
            // Scan FAB at the top
            FloatingActionButton(
                onClick = { onNavigate(Screen.Scan) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(56.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = stringResource(R.string.cd_scan),
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }
    ) {
        NavItem.entries.filter { it != NavItem.Scan }.forEach { item ->
            val isSelected = when (item) {
                NavItem.Scan -> currentRoute.startsWith(Screen.Scan.routeBase)
                else -> currentRoute == item.screen.route
            }
            NavigationRailItem(
                icon = {
                    Icon(
                        imageVector = if (isSelected) item.iconFilled else item.iconOutlined,
                        contentDescription = item.label
                    )
                },
                label = { Text(item.label) },
                selected = isSelected,
                onClick = { onNavigate(item.screen) }
            )
        }
    }
}

@Composable
private fun NavigationDrawerContent(
    currentRoute: String,
    onNavigate: (Screen) -> Unit
) {
    PermanentDrawerSheet(
        modifier = Modifier.width(280.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(16.dp)
        ) {
            // App header
            Text(
                text = "Paperless Scanner",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp)
            )

            // Scan button
            Box(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 8.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary)
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clip(CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = stringResource(R.string.cd_scan),
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Neuer Scan",
                        color = MaterialTheme.colorScheme.onPrimary,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Navigation items
            NavItem.entries.filter { it != NavItem.Scan }.forEach { item ->
                val isSelected = when (item) {
                    NavItem.Scan -> currentRoute.startsWith(Screen.Scan.routeBase)
                    else -> currentRoute == item.screen.route
                }
                NavigationDrawerItem(
                    icon = {
                        Icon(
                            imageVector = if (isSelected) item.iconFilled else item.iconOutlined,
                            contentDescription = item.label
                        )
                    },
                    label = { Text(item.label) },
                    selected = isSelected,
                    onClick = { onNavigate(item.screen) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
            }
        }
    }
}
