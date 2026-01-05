package com.paperless.scanner.ui.navigation

import android.net.Uri

sealed class Screen(val route: String) {
    // Onboarding flow
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
    data object Scan : Screen("scan")
    data object Labels : Screen("labels")
    data object Settings : Screen("settings")

    // Demo screen
    data object Demo : Screen("demo")

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

    // Pending Sync Debug
    data object PendingSync : Screen("pending-sync")

    // Upload flow screens
    data object Upload : Screen("upload/{documentUri}") {
        fun createRoute(documentUri: Uri): String {
            return "upload/${Uri.encode(documentUri.toString())}"
        }
    }
    data object MultiPageUpload : Screen("upload-multi/{documentUris}") {
        fun createRoute(documentUris: List<Uri>): String {
            val encodedUris = documentUris.joinToString("|") { Uri.encode(it.toString()) }
            return "upload-multi/$encodedUris"
        }
    }
    data object BatchImport : Screen("batch-import/{imageUris}") {
        fun createRoute(imageUris: List<Uri>): String {
            val encodedUris = imageUris.joinToString("|") { Uri.encode(it.toString()) }
            return "batch-import/$encodedUris"
        }
    }
}
