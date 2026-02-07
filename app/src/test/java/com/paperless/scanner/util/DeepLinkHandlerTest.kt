package com.paperless.scanner.util

import android.content.Intent
import android.net.Uri
import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for DeepLinkHandler.
 *
 * Tests verify:
 * - Correct parsing of all supported paperless:// URIs
 * - Null returned for unknown hosts, paths, and schemes
 * - Intent parsing with null intent, missing data, and wrong action
 */
@RunWith(RobolectricTestRunner::class)
class DeepLinkHandlerTest {

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    // ==================== parseUri tests ====================

    @Test
    fun `parseUri with paperless scan returns SCAN`() {
        val uri = Uri.parse("paperless://scan")
        val result = DeepLinkHandler.parseUri(uri)
        assertEquals(DeepLinkAction.SCAN, result)
    }

    @Test
    fun `parseUri with paperless scan camera returns SCAN_CAMERA`() {
        val uri = Uri.parse("paperless://scan/camera")
        val result = DeepLinkHandler.parseUri(uri)
        assertEquals(DeepLinkAction.SCAN_CAMERA, result)
    }

    @Test
    fun `parseUri with paperless scan gallery returns SCAN_GALLERY`() {
        val uri = Uri.parse("paperless://scan/gallery")
        val result = DeepLinkHandler.parseUri(uri)
        assertEquals(DeepLinkAction.SCAN_GALLERY, result)
    }

    @Test
    fun `parseUri with paperless scan file returns SCAN_FILE`() {
        val uri = Uri.parse("paperless://scan/file")
        val result = DeepLinkHandler.parseUri(uri)
        assertEquals(DeepLinkAction.SCAN_FILE, result)
    }

    @Test
    fun `parseUri with paperless status returns STATUS`() {
        val uri = Uri.parse("paperless://status")
        val result = DeepLinkHandler.parseUri(uri)
        assertEquals(DeepLinkAction.STATUS, result)
    }

    @Test
    fun `parseUri with unknown host returns null`() {
        val uri = Uri.parse("paperless://unknown")
        val result = DeepLinkHandler.parseUri(uri)
        assertNull(result)
    }

    @Test
    fun `parseUri with unknown scan path returns null`() {
        val uri = Uri.parse("paperless://scan/unknown")
        val result = DeepLinkHandler.parseUri(uri)
        assertNull(result)
    }

    @Test
    fun `parseUri with wrong scheme returns null`() {
        val uri = Uri.parse("https://scan")
        val result = DeepLinkHandler.parseUri(uri)
        assertNull(result)
    }

    @Test
    fun `parseUri with http scheme returns null`() {
        val uri = Uri.parse("http://scan/camera")
        val result = DeepLinkHandler.parseUri(uri)
        assertNull(result)
    }

    @Test
    fun `parseUri with empty scheme returns null`() {
        val uri = Uri.parse("://scan")
        val result = DeepLinkHandler.parseUri(uri)
        assertNull(result)
    }

    // ==================== parseIntent tests ====================

    @Test
    fun `parseIntent with null intent returns null`() {
        val result = DeepLinkHandler.parseIntent(null)
        assertNull(result)
    }

    @Test
    fun `parseIntent with non-VIEW action returns null`() {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            data = Uri.parse("paperless://scan")
        }
        val result = DeepLinkHandler.parseIntent(intent)
        assertNull(result)
    }

    @Test
    fun `parseIntent with ACTION_SEND returns null`() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            data = Uri.parse("paperless://scan/camera")
        }
        val result = DeepLinkHandler.parseIntent(intent)
        assertNull(result)
    }

    @Test
    fun `parseIntent with VIEW action and valid deep link returns correct action`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("paperless://scan/camera")
        }
        val result = DeepLinkHandler.parseIntent(intent)
        assertEquals(DeepLinkAction.SCAN_CAMERA, result)
    }

    @Test
    fun `parseIntent with VIEW action and null data returns null`() {
        val intent = Intent(Intent.ACTION_VIEW)
        // data is null by default
        val result = DeepLinkHandler.parseIntent(intent)
        assertNull(result)
    }

    @Test
    fun `parseIntent with VIEW action and status deep link returns STATUS`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("paperless://status")
        }
        val result = DeepLinkHandler.parseIntent(intent)
        assertEquals(DeepLinkAction.STATUS, result)
    }

    @Test
    fun `parseIntent with VIEW action and wrong scheme returns null`() {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = Uri.parse("https://scan")
        }
        val result = DeepLinkHandler.parseIntent(intent)
        assertNull(result)
    }
}
