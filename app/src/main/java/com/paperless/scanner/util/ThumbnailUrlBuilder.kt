package com.paperless.scanner.util

/**
 * Utility object for constructing Paperless-ngx thumbnail URLs.
 *
 * Thumbnails are accessed via: `https://server.com/api/documents/{id}/thumb/`
 *
 * Security Note:
 * - Token is included via Authorization header (handled by CoilAuthInterceptor)
 * - NEVER include token in URL query parameters (insecure!)
 *
 * URL Handling:
 * - Handles trailing slashes in serverUrl (automatically normalized)
 * - Example: "https://example.com/" -> "https://example.com/api/documents/123/thumb/"
 * - Example: "https://example.com" -> "https://example.com/api/documents/123/thumb/"
 */
object ThumbnailUrlBuilder {

    /**
     * Builds a thumbnail URL for a specific document.
     *
     * @param serverUrl Base server URL (e.g., "https://docs.martini.digital" or "https://example.com/")
     * @param documentId Document ID
     * @return Full thumbnail URL (e.g., "https://docs.martini.digital/api/documents/398/thumb/")
     *
     * @throws IllegalArgumentException if serverUrl is blank
     *
     * Examples:
     * ```kotlin
     * // With trailing slash
     * buildThumbnailUrl("https://example.com/", 123)
     * // -> "https://example.com/api/documents/123/thumb/"
     *
     * // Without trailing slash
     * buildThumbnailUrl("https://example.com", 123)
     * // -> "https://example.com/api/documents/123/thumb/"
     *
     * // With subdirectory
     * buildThumbnailUrl("https://example.com/paperless/", 123)
     * // -> "https://example.com/paperless/api/documents/123/thumb/"
     * ```
     */
    fun buildThumbnailUrl(serverUrl: String, documentId: Int): String {
        require(serverUrl.isNotBlank()) { "serverUrl must not be blank" }

        // Normalize server URL (remove trailing slash)
        val normalizedUrl = serverUrl.trimEnd('/')

        // Build thumbnail URL
        return "$normalizedUrl/api/documents/$documentId/thumb/"
    }

}
