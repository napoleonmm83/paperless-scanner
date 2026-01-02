package com.paperless.scanner.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.paperless.scanner.ui.screens.batchimport.BatchImportScreen
import com.paperless.scanner.ui.screens.login.LoginScreen
import com.paperless.scanner.ui.screens.main.MainScreen
import com.paperless.scanner.ui.screens.upload.MultiPageUploadScreen
import com.paperless.scanner.ui.screens.upload.UploadScreen

// Main screens that use the bottom navigation
private val mainScreenRoutes = listOf(
    Screen.Home.route,
    Screen.Documents.route,
    Screen.Scan.route,
    Screen.Labels.route,
    Screen.Settings.route
)

@Composable
fun PaperlessNavGraph(
    navController: NavHostController,
    startDestination: String,
    sharedUris: List<Uri> = emptyList()
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Handle navigation to BatchImport when shared URIs are present
    LaunchedEffect(sharedUris, startDestination) {
        if (sharedUris.isNotEmpty() && startDestination == Screen.Home.route) {
            // User is logged in and has shared content - navigate to BatchImport
            navController.navigate(Screen.BatchImport.createRoute(sharedUris)) {
                popUpTo(Screen.Home.route) { inclusive = false }
            }
        }
    }

    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(route = Screen.Login.route) {
            LoginScreen(
                onLoginSuccess = {
                    if (sharedUris.isNotEmpty()) {
                        // After login, go directly to BatchImport with shared content
                        navController.navigate(Screen.BatchImport.createRoute(sharedUris)) {
                            popUpTo(Screen.Login.route) { inclusive = true }
                        }
                    } else {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Login.route) { inclusive = true }
                        }
                    }
                }
            )
        }

        // Main screens with bottom navigation
        mainScreenRoutes.forEach { route ->
            composable(route = route) {
                MainScreen(
                    navController = navController,
                    currentRoute = route,
                    onDocumentScanned = { uri ->
                        navController.navigate(Screen.Upload.createRoute(uri))
                    },
                    onMultipleDocumentsScanned = { uris ->
                        navController.navigate(Screen.MultiPageUpload.createRoute(uris))
                    },
                    onBatchImport = { uris ->
                        navController.navigate(Screen.BatchImport.createRoute(uris))
                    },
                    onLogout = {
                        navController.navigate(Screen.Login.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                )
            }
        }

        composable(
            route = Screen.Upload.route,
            arguments = listOf(
                navArgument("documentUri") {
                    type = NavType.StringType
                }
            )
        ) { backStackEntry ->
            val documentUriString = backStackEntry.arguments?.getString("documentUri")
            val documentUri = documentUriString?.let { Uri.parse(it) }

            if (documentUri != null) {
                UploadScreen(
                    documentUri = documentUri,
                    onUploadSuccess = {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Home.route) { inclusive = true }
                        }
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
        }

        composable(
            route = Screen.MultiPageUpload.route,
            arguments = listOf(
                navArgument("documentUris") {
                    type = NavType.StringType
                }
            )
        ) { backStackEntry ->
            val documentUrisString = backStackEntry.arguments?.getString("documentUris")
            val documentUris = documentUrisString?.split("|")?.mapNotNull { encodedUri ->
                try {
                    Uri.parse(Uri.decode(encodedUri))
                } catch (e: Exception) {
                    null
                }
            } ?: emptyList()

            if (documentUris.isNotEmpty()) {
                MultiPageUploadScreen(
                    documentUris = documentUris,
                    onUploadSuccess = {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Home.route) { inclusive = true }
                        }
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
        }

        composable(
            route = Screen.BatchImport.route,
            arguments = listOf(
                navArgument("imageUris") {
                    type = NavType.StringType
                }
            )
        ) { backStackEntry ->
            val imageUrisString = backStackEntry.arguments?.getString("imageUris")
            val imageUris = imageUrisString?.split("|")?.mapNotNull { encodedUri ->
                try {
                    Uri.parse(Uri.decode(encodedUri))
                } catch (e: Exception) {
                    null
                }
            } ?: emptyList()

            if (imageUris.isNotEmpty()) {
                BatchImportScreen(
                    imageUris = imageUris,
                    onImportSuccess = {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Home.route) { inclusive = true }
                        }
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}
