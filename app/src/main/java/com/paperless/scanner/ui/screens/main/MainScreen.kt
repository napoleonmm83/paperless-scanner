package com.paperless.scanner.ui.screens.main

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
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
    onDocumentClick: (Int) -> Unit,
    onLogout: () -> Unit
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        bottomBar = {
            BottomNavBar(
                currentRoute = currentRoute,
                onNavigate = { screen ->
                    if (screen.route != currentRoute) {
                        navController.navigate(screen.route) {
                            // Pop up to Home, keeping Home in the back stack
                            popUpTo(Screen.Home.route) {
                                inclusive = false
                            }
                            launchSingleTop = true
                        }
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(paddingValues)
        ) {
            when (currentRoute) {
                Screen.Home.route -> HomeScreen(
                    onNavigateToScan = {
                        navController.navigate(Screen.Scan.route)
                    },
                    onNavigateToDocuments = {
                        navController.navigate(Screen.Documents.route)
                    },
                    onDocumentClick = onDocumentClick,
                    onNavigateToPendingSync = {
                        navController.navigate(Screen.PendingSync.route)
                    }
                )
                Screen.Documents.route -> DocumentsScreen(
                    onDocumentClick = onDocumentClick
                )
                Screen.Scan.route -> ScanScreen(
                    onDocumentScanned = onDocumentScanned,
                    onMultipleDocumentsScanned = onMultipleDocumentsScanned,
                    onBatchImport = onBatchImport
                )
                Screen.Labels.route -> LabelsScreen(
                    onDocumentClick = onDocumentClick
                )
                Screen.Settings.route -> SettingsScreen(
                    onLogout = onLogout
                )
            }
        }
    }
}
