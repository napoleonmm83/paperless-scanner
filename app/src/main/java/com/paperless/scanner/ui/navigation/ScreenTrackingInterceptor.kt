package com.paperless.scanner.ui.navigation

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.paperless.scanner.data.analytics.AnalyticsService
import com.paperless.scanner.data.analytics.CrashlyticsHelper

/**
 * Screen Tracking Interceptor for Firebase Analytics and Crashlytics.
 *
 * Automatically tracks screen views when navigation changes:
 * - Firebase Analytics: trackScreen() for screen view events
 * - Crashlytics: logNavigationBreadcrumb() for crash context
 *
 * Respects GDPR consent via AnalyticsService.isAnalyticsEnabled().
 */
@Composable
fun ScreenTrackingInterceptor(
    navController: NavHostController,
    analyticsService: AnalyticsService,
    crashlyticsHelper: CrashlyticsHelper
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Track previous route to avoid duplicate tracking on recomposition
    var previousRoute by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(currentRoute) {
        if (currentRoute != null && currentRoute != previousRoute) {
            val screenName = mapRouteToScreenName(currentRoute)

            Log.d("ScreenTrackingInterceptor", "Screen changed: $currentRoute -> $screenName")

            // Track in Firebase Analytics
            analyticsService.trackScreen(
                screenName = screenName,
                screenClass = currentRoute
            )

            // Log Crashlytics breadcrumb
            crashlyticsHelper.logNavigationBreadcrumb(screenName)

            previousRoute = currentRoute
        }
    }
}

/**
 * Maps navigation routes to human-readable screen names.
 *
 * Examples:
 * - "document/{documentId}" -> "DocumentDetail"
 * - "home" -> "Home"
 * - "scan?pageUris={pageUris}" -> "Scan"
 */
private fun mapRouteToScreenName(route: String): String {
    return when {
        // Onboarding
        route == Screen.Welcome.route -> "Welcome"
        route == Screen.OnboardingWelcome.route -> "OnboardingWelcome"
        route == Screen.SimplifiedSetup.route -> "SimplifiedSetup"
        route == Screen.EditServerSettings.route -> "EditServerSettings"
        route == Screen.ServerSetup.route -> "ServerSetup"
        route.startsWith("login/") -> "Login"
        route == Screen.Success.route -> "Success"

        // Main navigation
        route == Screen.Home.route -> "Home"
        route == Screen.Documents.route -> "Documents"
        route.startsWith("scan") -> "Scan"
        route == Screen.Labels.route -> "Labels"
        route == Screen.Settings.route -> "Settings"
        route == Screen.Diagnostics.route -> "Diagnostics"

        // Document screens
        route.startsWith("document/") -> "DocumentDetail"
        route.startsWith("pdf-viewer/") -> "PdfViewer"

        // Sync & Features
        route == Screen.SyncCenter.route -> "SyncCenter"
        route == Screen.SmartTagging.route -> "SmartTagging"
        route == Screen.Trash.route -> "Trash"

        // App Lock
        route == Screen.AppLock.route -> "AppLock"
        route.startsWith("setup-app-lock/") -> "SetupAppLock"

        // Upload flow
        route.startsWith("upload/") && !route.startsWith("upload-multi/") -> "Upload"
        route.startsWith("upload-multi/") -> "MultiPageUpload"
        route.startsWith("step-by-step-metadata/") -> "StepByStepMetadata"

        // Fallback: use route as-is (cleaned up)
        else -> route
            .substringBefore("/")
            .substringBefore("?")
            .replaceFirstChar { it.uppercase() }
    }
}
