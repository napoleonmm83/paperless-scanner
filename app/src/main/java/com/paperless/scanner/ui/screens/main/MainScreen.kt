package com.paperless.scanner.ui.screens.main

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.paperless.scanner.ui.components.BottomNavBar
import com.paperless.scanner.ui.navigation.Screen
import com.paperless.scanner.ui.screens.documents.DocumentsScreen
import com.paperless.scanner.ui.screens.home.HomeScreen
import com.paperless.scanner.ui.screens.labels.LabelsScreen
import com.paperless.scanner.ui.screens.scan.ScanScreen
import com.paperless.scanner.ui.screens.settings.SettingsScreen

@Composable
fun MainScreen(
    navController: NavHostController,
    currentRoute: String,
    onDocumentScanned: (Uri) -> Unit,
    onMultipleDocumentsScanned: (List<Uri>) -> Unit,
    onBatchImport: (List<Uri>) -> Unit,
    onLogout: () -> Unit
) {
    Scaffold(
        bottomBar = {
            BottomNavBar(
                currentRoute = currentRoute,
                onNavigate = { screen ->
                    if (screen.route != currentRoute) {
                        navController.navigate(screen.route) {
                            // Pop up to home to avoid building up a large back stack
                            popUpTo(Screen.Home.route) {
                                saveState = true
                            }
                            // Avoid multiple copies of the same destination
                            launchSingleTop = true
                            // Restore state when reselecting a previously selected item
                            restoreState = true
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when (currentRoute) {
                Screen.Home.route -> HomeScreen(
                    onNavigateToScan = {
                        navController.navigate(Screen.Scan.route)
                    },
                    onNavigateToDocuments = {
                        navController.navigate(Screen.Documents.route)
                    }
                )
                Screen.Documents.route -> DocumentsScreen()
                Screen.Scan.route -> ScanScreen(
                    onDocumentScanned = onDocumentScanned,
                    onMultipleDocumentsScanned = onMultipleDocumentsScanned,
                    onBatchImport = onBatchImport
                )
                Screen.Labels.route -> LabelsScreen()
                Screen.Settings.route -> SettingsScreen(
                    onLogout = onLogout
                )
            }
        }
    }
}
