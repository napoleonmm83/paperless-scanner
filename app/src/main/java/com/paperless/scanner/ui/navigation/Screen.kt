package com.paperless.scanner.ui.navigation

import android.net.Uri

sealed class Screen(val route: String) {
    data object Login : Screen("login")

    // Main navigation screens (bottom nav)
    data object Home : Screen("home")
    data object Documents : Screen("documents")
    data object Scan : Screen("scan")
    data object Labels : Screen("labels")
    data object Settings : Screen("settings")

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
