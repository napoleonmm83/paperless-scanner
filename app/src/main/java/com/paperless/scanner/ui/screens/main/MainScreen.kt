package com.paperless.scanner.ui.screens.main

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController
import com.paperless.scanner.ui.components.AdaptiveNavigation
import com.paperless.scanner.ui.navigation.BatchSourceType
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
    scanPageUris: List<Uri> = emptyList(),
    scanBackStackEntry: NavBackStackEntry? = null,
    onDocumentScanned: (Uri) -> Unit,
    onMultipleDocumentsScanned: (List<Uri>) -> Unit,
    onBatchImport: (List<Uri>, BatchSourceType) -> Unit,
    onDocumentClick: (Int) -> Unit,
    onLogout: () -> Unit
) {
    AdaptiveNavigation(
        currentRoute = currentRoute,
        onNavigate = { screen ->
            // For Scan, always navigate to base route (without params)
            val targetRoute = if (screen == Screen.Scan) Screen.Scan.routeBase else screen.route
            if (targetRoute != currentRoute && !currentRoute.startsWith(targetRoute)) {
                navController.navigate(targetRoute) {
                    // Pop up to Home, keeping Home in the back stack
                    popUpTo(Screen.Home.route) {
                        inclusive = false
                    }
                    launchSingleTop = true
                }
            }
        }
    ) {
        when (currentRoute) {
            Screen.Home.route -> HomeScreen(
                onNavigateToScan = {
                    navController.navigate(Screen.Scan.routeBase)
                },
                onNavigateToDocuments = {
                    navController.navigate(Screen.Documents.route)
                },
                onDocumentClick = onDocumentClick,
                onNavigateToPendingSync = {
                    navController.navigate(Screen.PendingSync.route)
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                },
                onNavigateToSmartTagging = {
                    navController.navigate(Screen.SmartTagging.route)
                }
            )
            Screen.Documents.route -> DocumentsScreen(
                onDocumentClick = onDocumentClick
            )
            Screen.Scan.routeBase -> ScanScreen(
                initialPageUris = scanPageUris,
                navBackStackEntry = scanBackStackEntry,
                onDocumentScanned = onDocumentScanned,
                onMultipleDocumentsScanned = onMultipleDocumentsScanned,
                onBatchImport = onBatchImport
            )
            Screen.Labels.route -> LabelsScreen(
                onDocumentClick = onDocumentClick
            )
            Screen.Settings.route -> SettingsScreen(
                onLogout = onLogout,
                onNavigateToSetupAppLock = { isChangingPassword ->
                    navController.navigate(Screen.SetupAppLock.createRoute(isChangingPassword)) {
                        // Ensure Settings is on the back stack for proper back navigation
                        launchSingleTop = true
                    }
                },
                onNavigateToEditServer = {
                    navController.navigate(Screen.EditServerSettings.route)
                }
            )
        }
    }
}
