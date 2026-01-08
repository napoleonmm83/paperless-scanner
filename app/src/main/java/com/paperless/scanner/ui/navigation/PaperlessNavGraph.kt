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
import com.paperless.scanner.ui.theme.PaperlessAnimations
import com.paperless.scanner.ui.screens.documents.DocumentDetailScreen
import com.paperless.scanner.ui.screens.main.MainScreen
import com.paperless.scanner.ui.screens.onboarding.OnboardingLoginScreen
import com.paperless.scanner.ui.screens.onboarding.ServerSetupScreen
import com.paperless.scanner.ui.screens.onboarding.SuccessScreen
import com.paperless.scanner.ui.screens.onboarding.WelcomeScreen
import com.paperless.scanner.ui.screens.pdfviewer.PdfViewerScreen
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
        // Onboarding flow
        composable(route = Screen.Welcome.route) {
            WelcomeScreen(
                onContinue = {
                    navController.navigate(Screen.ServerSetup.route)
                }
            )
        }

        composable(route = Screen.ServerSetup.route) {
            ServerSetupScreen(
                onBack = {
                    navController.popBackStack()
                },
                onContinue = { serverUrl ->
                    navController.navigate(Screen.Login.createRoute(serverUrl))
                }
            )
        }

        composable(
            route = Screen.Login.route,
            arguments = listOf(
                navArgument("serverUrl") {
                    type = NavType.StringType
                }
            )
        ) { backStackEntry ->
            val serverUrl = backStackEntry.arguments?.getString("serverUrl")?.let {
                Uri.decode(it)
            } ?: ""

            OnboardingLoginScreen(
                serverUrl = serverUrl,
                onBack = {
                    navController.popBackStack()
                },
                onLoginSuccess = {
                    navController.navigate(Screen.Success.route) {
                        popUpTo(Screen.Welcome.route) { inclusive = true }
                    }
                }
            )
        }

        composable(route = Screen.Success.route) {
            SuccessScreen(
                onComplete = {
                    if (sharedUris.isNotEmpty()) {
                        navController.navigate(Screen.BatchImport.createRoute(sharedUris)) {
                            popUpTo(Screen.Success.route) { inclusive = true }
                        }
                    } else {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Success.route) { inclusive = true }
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
                    onDocumentClick = { documentId ->
                        navController.navigate(Screen.DocumentDetail.createRoute(documentId))
                    },
                    onLogout = {
                        navController.navigate(Screen.Welcome.route) {
                            popUpTo(0) { inclusive = true }
                        }
                    }
                )
            }
        }

        // Document Detail Screen
        composable(
            route = Screen.DocumentDetail.route,
            arguments = listOf(
                navArgument("documentId") {
                    type = NavType.StringType
                }
            ),
            enterTransition = { PaperlessAnimations.screenEnterTransition },
            exitTransition = { PaperlessAnimations.screenExitTransition },
            popEnterTransition = { PaperlessAnimations.screenPopEnterTransition },
            popExitTransition = { PaperlessAnimations.screenPopExitTransition }
        ) {
            DocumentDetailScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onOpenPdf = { documentId, documentTitle ->
                    navController.navigate(Screen.PdfViewer.createRoute(documentId, documentTitle))
                }
            )
        }

        // PDF Viewer Screen
        composable(
            route = Screen.PdfViewer.route,
            arguments = listOf(
                navArgument("documentId") {
                    type = NavType.StringType
                },
                navArgument("documentTitle") {
                    type = NavType.StringType
                }
            ),
            enterTransition = { PaperlessAnimations.screenEnterTransition },
            exitTransition = { PaperlessAnimations.screenExitTransition },
            popEnterTransition = { PaperlessAnimations.screenPopEnterTransition },
            popExitTransition = { PaperlessAnimations.screenPopExitTransition }
        ) {
            PdfViewerScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        // Pending Sync Screen
        composable(route = Screen.PendingSync.route) {
            com.paperless.scanner.ui.screens.pendingsync.PendingSyncScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }

        composable(
            route = Screen.Upload.route,
            arguments = listOf(
                navArgument("documentUri") {
                    type = NavType.StringType
                }
            ),
            enterTransition = { PaperlessAnimations.verticalEnterTransition },
            exitTransition = { PaperlessAnimations.verticalExitTransition },
            popEnterTransition = { PaperlessAnimations.screenPopEnterTransition },
            popExitTransition = { PaperlessAnimations.verticalExitTransition }
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
            ),
            enterTransition = { PaperlessAnimations.verticalEnterTransition },
            exitTransition = { PaperlessAnimations.verticalExitTransition },
            popEnterTransition = { PaperlessAnimations.screenPopEnterTransition },
            popExitTransition = { PaperlessAnimations.verticalExitTransition }
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
            ),
            enterTransition = { PaperlessAnimations.verticalEnterTransition },
            exitTransition = { PaperlessAnimations.verticalExitTransition },
            popEnterTransition = { PaperlessAnimations.screenPopEnterTransition },
            popExitTransition = { PaperlessAnimations.verticalExitTransition }
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
