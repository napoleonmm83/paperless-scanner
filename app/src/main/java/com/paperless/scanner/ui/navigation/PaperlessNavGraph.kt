package com.paperless.scanner.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.paperless.scanner.ui.screens.batchimport.BatchImportScreen
import com.paperless.scanner.ui.screens.login.LoginScreen
import com.paperless.scanner.ui.screens.scan.ScanScreen
import com.paperless.scanner.ui.screens.upload.MultiPageUploadScreen
import com.paperless.scanner.ui.screens.upload.UploadScreen

@Composable
fun PaperlessNavGraph(
    navController: NavHostController,
    startDestination: String
) {
    NavHost(
        navController = navController,
        startDestination = startDestination
    ) {
        composable(route = Screen.Login.route) {
            LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Screen.Scan.route) {
                        popUpTo(Screen.Login.route) { inclusive = true }
                    }
                }
            )
        }

        composable(route = Screen.Scan.route) {
            ScanScreen(
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
                        navController.navigate(Screen.Scan.route) {
                            popUpTo(Screen.Scan.route) { inclusive = true }
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
                        navController.navigate(Screen.Scan.route) {
                            popUpTo(Screen.Scan.route) { inclusive = true }
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
                        navController.navigate(Screen.Scan.route) {
                            popUpTo(Screen.Scan.route) { inclusive = true }
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
