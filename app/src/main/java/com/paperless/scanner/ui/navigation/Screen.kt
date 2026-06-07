package com.paperless.scanner.ui.navigation

import android.net.Uri

/**
 * Type-safe Compose navigation destinations.
 *
 * Every parameterized route MUST be built through its `createRoute(...)` factory — never a raw
 * string literal or hand-built concatenation. Factories give compile-time parameter types and
 * route all dynamic segments through [encodeSegment]/[encodeUriList] for consistent percent-
 * encoding (e.g. [Scan.createRoute], [Upload.createRoute], [PdfViewer.createRoute]). Raw
 * `navigate("...")` literals are rejected by the `RawRouteString` detekt rule. Plan-08 (#45).
 */
sealed class Screen(val route: String) {
    // Onboarding flow
    data object OnboardingWelcome : Screen("onboarding-welcome")
    data object SimplifiedSetup : Screen("simplified-setup")
    data object EditServerSettings : Screen("edit-server-settings")
    data object Welcome : Screen("welcome")
    data object ServerSetup : Screen("server-setup")
    data object Login : Screen("login/{serverUrl}") {
        fun createRoute(serverUrl: String): String {
            return "login/${encodeSegment(serverUrl)}"
        }
    }
    data object Success : Screen("success")

    // Main navigation screens (bottom nav)
    data object Home : Screen("home")
    data object Documents : Screen("documents")
    data object Scan : Screen("scan?pageUris={pageUris}&scanAction={scanAction}") {
        // Route without params for mode selection
        const val routeBase = "scan"

        // Route with page URIs for MultiPageContent (after scanning)
        fun createRoute(pageUris: List<Uri>): String {
            if (pageUris.isEmpty()) return routeBase
            return "scan?pageUris=${encodeUriList(pageUris)}"
        }

        // Route with a specific scan action (for deep links: camera, gallery, file)
        fun createRouteWithAction(scanAction: String): String {
            return "scan?scanAction=$scanAction"
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
            return "pdf-viewer/$documentId/${encodeSegment(documentTitle)}"
        }
    }

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
            return "upload/${encodeSegment(documentUri.toString())}"
        }
    }
    data object MultiPageUpload : Screen("upload-multi/{documentUris}/{uploadAsSingleDocument}") {
        fun createRoute(documentUris: List<Uri>, uploadAsSingleDocument: Boolean = true): String {
            return "upload-multi/${encodeUriList(documentUris)}/$uploadAsSingleDocument"
        }
    }
    data object StepByStepMetadata : Screen("step-by-step-metadata/{pageUris}") {
        fun createRoute(pageUris: List<Uri>): String {
            return "step-by-step-metadata/${encodeUriList(pageUris)}"
        }
    }
}

/** Percent-encodes one dynamic route segment so reserved characters survive nav routing. */
private fun encodeSegment(value: String): String = Uri.encode(value)

/**
 * Joins multiple URIs into one route segment: each part is percent-encoded, while the '|'
 * delimiter is intentionally left raw so the segment can be split apart again on read.
 */
private fun encodeUriList(uris: List<Uri>): String =
    uris.joinToString("|") { encodeSegment(it.toString()) }
