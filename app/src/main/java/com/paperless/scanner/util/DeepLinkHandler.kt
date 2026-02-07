package com.paperless.scanner.util

import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Defines the possible deep link actions that can be triggered from widgets or external sources.
 */
enum class DeepLinkAction {
    /** Navigate to scan screen (mode selection) */
    SCAN,
    /** Start MLKit Document Scanner directly */
    SCAN_CAMERA,
    /** Open Photo Picker directly */
    SCAN_GALLERY,
    /** Open File Picker directly */
    SCAN_FILE,
    /** Navigate to SyncCenter screen */
    STATUS
}

/**
 * Parses deep link intents with the "paperless://" scheme into DeepLinkAction values.
 *
 * Supported URIs:
 * - paperless://scan → SCAN (mode selection)
 * - paperless://scan/camera → SCAN_CAMERA
 * - paperless://scan/gallery → SCAN_GALLERY
 * - paperless://scan/file → SCAN_FILE
 * - paperless://status → STATUS
 */
object DeepLinkHandler {

    private const val TAG = "DeepLinkHandler"
    private const val SCHEME = "paperless"

    /**
     * Parses an Intent to extract a DeepLinkAction, if present.
     * Returns null if the intent does not contain a valid deep link.
     */
    fun parseIntent(intent: Intent?): DeepLinkAction? {
        if (intent == null) return null

        // Only handle VIEW actions with our scheme
        if (intent.action != Intent.ACTION_VIEW) return null

        val uri = intent.data ?: return null
        return parseUri(uri)
    }

    /**
     * Parses a URI into a DeepLinkAction.
     * Returns null if the URI is not a valid paperless:// deep link.
     */
    fun parseUri(uri: Uri): DeepLinkAction? {
        if (uri.scheme != SCHEME) return null

        val host = uri.host ?: return null
        val path = uri.path?.trimStart('/') ?: ""

        Log.d(TAG, "Parsing deep link: scheme=$SCHEME, host=$host, path=$path")

        return when (host) {
            "scan" -> when (path) {
                "camera" -> DeepLinkAction.SCAN_CAMERA
                "gallery" -> DeepLinkAction.SCAN_GALLERY
                "file" -> DeepLinkAction.SCAN_FILE
                "" -> DeepLinkAction.SCAN
                else -> {
                    Log.w(TAG, "Unknown scan path: $path")
                    null
                }
            }
            "status" -> DeepLinkAction.STATUS
            else -> {
                Log.w(TAG, "Unknown deep link host: $host")
                null
            }
        }
    }
}
