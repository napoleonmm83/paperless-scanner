package com.paperless.scanner.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbnailUrlBuilderTest {

    @Test
    fun `buildThumbnailUrl with trailing slash`() {
        val result = ThumbnailUrlBuilder.buildThumbnailUrl(
            serverUrl = "https://example.com/",
            documentId = 123
        )
        assertEquals("https://example.com/api/documents/123/thumb/", result)
    }

    @Test
    fun `buildThumbnailUrl without trailing slash`() {
        val result = ThumbnailUrlBuilder.buildThumbnailUrl(
            serverUrl = "https://example.com",
            documentId = 123
        )
        assertEquals("https://example.com/api/documents/123/thumb/", result)
    }

    @Test
    fun `buildThumbnailUrl with subdirectory`() {
        val result = ThumbnailUrlBuilder.buildThumbnailUrl(
            serverUrl = "https://example.com/paperless/",
            documentId = 456
        )
        assertEquals("https://example.com/paperless/api/documents/456/thumb/", result)
    }

    @Test
    fun `buildThumbnailUrl with subdirectory without trailing slash`() {
        val result = ThumbnailUrlBuilder.buildThumbnailUrl(
            serverUrl = "https://example.com/paperless",
            documentId = 456
        )
        assertEquals("https://example.com/paperless/api/documents/456/thumb/", result)
    }

    @Test
    fun `buildThumbnailUrl with multiple trailing slashes`() {
        val result = ThumbnailUrlBuilder.buildThumbnailUrl(
            serverUrl = "https://example.com///",
            documentId = 789
        )
        assertEquals("https://example.com/api/documents/789/thumb/", result)
    }

    @Test
    fun `buildThumbnailUrl with HTTP protocol`() {
        val result = ThumbnailUrlBuilder.buildThumbnailUrl(
            serverUrl = "http://localhost:8000",
            documentId = 1
        )
        assertEquals("http://localhost:8000/api/documents/1/thumb/", result)
    }

    @Test
    fun `buildThumbnailUrl with real server example`() {
        val result = ThumbnailUrlBuilder.buildThumbnailUrl(
            serverUrl = "https://docs.martini.digital",
            documentId = 398
        )
        assertEquals("https://docs.martini.digital/api/documents/398/thumb/", result)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildThumbnailUrl throws on blank serverUrl`() {
        ThumbnailUrlBuilder.buildThumbnailUrl(
            serverUrl = "",
            documentId = 123
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `buildThumbnailUrl throws on whitespace-only serverUrl`() {
        ThumbnailUrlBuilder.buildThumbnailUrl(
            serverUrl = "   ",
            documentId = 123
        )
    }
}
