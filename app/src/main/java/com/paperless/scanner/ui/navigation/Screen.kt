package com.paperless.scanner.ui.navigation

import android.net.Uri

sealed class Screen(val route: String) {
    // Onboarding flow
    data object OnboardingWelcome : Screen("onboarding-welcome")
    data object SimplifiedSetup : Screen("simplified-setup")
    data object EditServerSettings : Screen("edit-server-settings")
    data object Welcome : Screen("welcome")
    data object ServerSetup : Screen("server-setup")
    data object Login : Screen("login/{serverUrl}") {
        fun createRoute(serverUrl: String): String {
            return "login/${Uri.encode(serverUrl)}"
        }
    }
    data object Success : Screen("success")

    // Main navigation screens (bottom nav)
    data object Home : Screen("home")
    data object Documents : Screen("documents")
    data object Scan : Screen("scan?pageUris={pageUris}") {
        // Route without params for mode selection
        const val routeBase = "scan"

        // Route with page URIs for MultiPageContent (after scanning)
        fun createRoute(pageUris: List<Uri>): String {
            if (pageUris.isEmpty()) return routeBase
            val encodedUris = pageUris.joinToString("|") { Uri.encode(it.toString()) }
            return "scan?pageUris=$encodedUris"
        }
    }
    data object Labels : Screen("labels")
    data object Settings : Screen("settings")
    data object Diagnostics : Screen("diagnostics")

    // Document detail
    data object DocumentDetail : Screen("document/{documentId}") {
        fun createRoute(documentId: Int): String {
            return "document/$documentId"
        }
    }

    // PDF Viewer
    data object PdfViewer : Screen("pdf-viewer/{documentId}/{documentTitle}") {
        fun createRoute(documentId: Int, documentTitle: String): String {
            val encodedTitle = Uri.encode(documentTitle)
            return "pdf-viewer/$documentId/$encodedTitle"
        }
    }

    // Pending Sync Debug (deprecated - use SyncCenter)
    data object PendingSync : Screen("pending-sync")

    // Sync Center (new unified sync screen)
    data object SyncCenter : Screen("sync-center")

    // Smart Tagging (Tinder-style swipe to tag)
    data object SmartTagging : Screen("smart-tagging")

    // Trash (Soft-deleted documents)
    data object Trash : Screen("trash")

    // App Lock
    data object AppLock : Screen("app-lock")

    data object SetupAppLock : Screen("setup-app-lock/{isChangingPassword}") {
        fun createRoute(isChangingPassword: Boolean): String {
            return "setup-app-lock/$isChangingPassword"
        }
    }

    // Upload flow screens
    data object Upload : Screen("upload/{documentUri}") {
        fun createRoute(documentUri: Uri): String {
            return "upload/${Uri.encode(documentUri.toString())}"
        }
    }
    data object MultiPageUpload : Screen("upload-multi/{documentUris}/{uploadAsSingleDocument}") {
        fun createRoute(documentUris: List<Uri>, uploadAsSingleDocument: Boolean = true): String {
            val encodedUris = documentUris.joinToString("|") { Uri.encode(it.toString()) }
            return "upload-multi/$encodedUris/$uploadAsSingleDocument"
        }
    }
    data object StepByStepMetadata : Screen("step-by-step-metadata/{pageUris}") {
        fun createRoute(pageUris: List<Uri>): String {
            val encodedUris = pageUris.joinToString("|") { Uri.encode(it.toString()) }
            return "step-by-step-metadata/$encodedUris"
        }
    }
}
