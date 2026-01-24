package com.paperless.scanner.ui.navigation

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.ui.screens.batchimport.BatchImportScreen
import com.paperless.scanner.ui.screens.batchimport.BatchImportViewModel
import com.paperless.scanner.ui.screens.batchimport.BatchMetadataScreen
import com.paperless.scanner.ui.theme.PaperlessAnimations
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.paperless.scanner.ui.screens.documents.DocumentDetailScreen
import com.paperless.scanner.ui.screens.main.MainScreen
import com.paperless.scanner.ui.screens.onboarding.SimplifiedSetupScreen
import com.paperless.scanner.ui.screens.pdfviewer.PdfViewerScreen
import com.paperless.scanner.ui.screens.upload.MultiPageUploadScreen
import com.paperless.scanner.ui.screens.upload.UploadScreen
import com.paperless.scanner.ui.screens.home.SmartTaggingScreen

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
    sharedUris: List<Uri> = emptyList(),
    tokenManager: TokenManager,
    appLockManager: com.paperless.scanner.util.AppLockManager
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // App-Lock Navigation Interceptor
    AppLockNavigationInterceptor(
        navController = navController,
        appLockManager = appLockManager
    )

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
        // Unified Onboarding flow - SimplifiedSetup on Welcome route
        composable(route = Screen.Welcome.route) {
            SimplifiedSetupScreen(
                onSuccess = {
                    // Mark onboarding as completed
                    CoroutineScope(Dispatchers.IO).launch {
                        tokenManager.setOnboardingCompleted(true)
                    }

                    if (sharedUris.isNotEmpty()) {
                        navController.navigate(Screen.BatchImport.createRoute(sharedUris)) {
                            popUpTo(Screen.Welcome.route) { inclusive = true }
                        }
                    } else {
                        navController.navigate(Screen.Home.route) {
                            popUpTo(Screen.Welcome.route) { inclusive = true }
                        }
                    }
                }
            )
        }

        // Main screens with bottom navigation
        mainScreenRoutes.forEach { route ->
            // Scan screen needs special handling for optional pageUris parameter
            if (route == Screen.Scan.route) {
                composable(
                    route = route,
                    arguments = listOf(
                        navArgument("pageUris") {
                            type = NavType.StringType
                            nullable = true
                            defaultValue = null
                        }
                    )
                ) { backStackEntry ->
                    val pageUrisString = backStackEntry.arguments?.getString("pageUris")
                    val pageUris = pageUrisString?.split("|")?.mapNotNull { encodedUri ->
                        try {
                            Uri.parse(Uri.decode(encodedUri))
                        } catch (e: Exception) {
                            null
                        }
                    } ?: emptyList()

                    MainScreen(
                        navController = navController,
                        currentRoute = Screen.Scan.routeBase,
                        scanPageUris = pageUris,
                        scanBackStackEntry = backStackEntry,
                        onDocumentScanned = { uri ->
                            navController.navigate(Screen.Upload.createRoute(uri))
                        },
                        onMultipleDocumentsScanned = { uris ->
                            navController.navigate(Screen.MultiPageUpload.createRoute(uris))
                        },
                        onBatchImport = { uris, sourceType ->
                            navController.navigate(Screen.BatchImport.createRoute(uris, sourceType))
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
            } else {
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
                        onBatchImport = { uris, sourceType ->
                            navController.navigate(Screen.BatchImport.createRoute(uris, sourceType))
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
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
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

        // Smart Tagging Screen (Tinder-style swipe to tag)
        composable(
            route = Screen.SmartTagging.route,
            enterTransition = { PaperlessAnimations.screenEnterTransition },
            exitTransition = { PaperlessAnimations.screenExitTransition },
            popEnterTransition = { PaperlessAnimations.screenPopEnterTransition },
            popExitTransition = { PaperlessAnimations.screenPopExitTransition }
        ) {
            SmartTaggingScreen(
                onNavigateBack = {
                    navController.popBackStack()
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
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
                    },
                    onNavigateToSettings = {
                        navController.navigate(Screen.Settings.route)
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
                },
                navArgument("sourceType") {
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
            val sourceTypeString = backStackEntry.arguments?.getString("sourceType")
            val sourceType = try {
                BatchSourceType.valueOf(sourceTypeString ?: "GALLERY")
            } catch (e: Exception) {
                BatchSourceType.GALLERY
            }

            if (imageUris.isNotEmpty()) {
                val viewModel: BatchImportViewModel = hiltViewModel()

                BatchImportScreen(
                    imageUris = imageUris,
                    sourceType = sourceType,
                    viewModel = viewModel,
                    navBackStackEntry = backStackEntry,
                    onContinueToMetadata = { selectedUris, uploadAsSingleDocument ->
                        navController.navigate(Screen.BatchMetadata.createRoute(selectedUris, uploadAsSingleDocument))
                    },
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
        }

        composable(
            route = Screen.BatchMetadata.route,
            arguments = listOf(
                navArgument("documentUris") {
                    type = NavType.StringType
                },
                navArgument("uploadAsSingleDocument") {
                    type = NavType.BoolType
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
            val uploadAsSingleDocument = backStackEntry.arguments?.getBoolean("uploadAsSingleDocument") ?: false

            if (documentUris.isNotEmpty()) {
                BatchMetadataScreen(
                    imageUris = documentUris,
                    uploadAsSingleDocument = uploadAsSingleDocument,
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

        // App Lock Screen
        composable(route = Screen.AppLock.route) {
            com.paperless.scanner.ui.screens.applock.AppLockScreen(
                onUnlocked = {
                    // CRITICAL: Do NOT navigate here!
                    // Navigation is handled by AppLockNavigationInterceptor to avoid race conditions
                    // The interceptor listens to lockState and navigates back to the saved route
                },
                onLockedOut = {
                    // User was locked out after too many attempts
                    // Navigate back to login
                    navController.navigate(Screen.Welcome.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }

        // Setup App Lock Screen (Password Setup/Change)
        composable(
            route = Screen.SetupAppLock.route,
            arguments = listOf(
                navArgument("isChangingPassword") {
                    type = NavType.BoolType
                }
            )
        ) { backStackEntry ->
            val isChangingPassword = backStackEntry.arguments?.getBoolean("isChangingPassword") ?: false

            com.paperless.scanner.ui.screens.applock.SetupAppLockScreen(
                onBackClick = {
                    navController.popBackStack()
                },
                onSetupComplete = {
                    // IMPORTANT: Just pop back to Settings (previous screen)
                    // Don't use navigate() with popUpTo because route templates don't match instantiated routes
                    navController.popBackStack()
                },
                isChangingPassword = isChangingPassword
            )
        }

    }
}
