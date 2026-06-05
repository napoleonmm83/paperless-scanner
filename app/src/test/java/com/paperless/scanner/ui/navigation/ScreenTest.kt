package com.paperless.scanner.ui.navigation

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Characterizes the Screen route factories (plan-08 #45). Runs under Robolectric because
 * android.net.Uri.encode is a no-op stub under plain JVM unit tests. Doubles as the safety
 * net for the Uri.encode consolidation refactor: behavior must stay byte-identical.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ScreenTest {

    @Test
    fun `static route has no params`() {
        assertEquals("home", Screen.Home.route)
    }

    @Test
    fun `Login createRoute percent-encodes the server url`() {
        val url = "https://my.server:8000/path"
        assertEquals("login/${Uri.encode(url)}", Screen.Login.createRoute(url))
    }

    @Test
    fun `Scan createRoute with no pages returns the base route`() {
        assertEquals("scan", Screen.Scan.createRoute(emptyList()))
    }

    @Test
    fun `Scan createRoute joins encoded uris with a raw pipe delimiter`() {
        val u1 = Uri.parse("content://docs/1")
        val u2 = Uri.parse("content://docs/2")
        val route = Screen.Scan.createRoute(listOf(u1, u2))
        assertEquals(
            "scan?pageUris=${Uri.encode(u1.toString())}|${Uri.encode(u2.toString())}",
            route,
        )
        assertTrue("pipe delimiter must stay raw", route.contains("|"))
    }

    @Test
    fun `PdfViewer createRoute encodes the title but not the id`() {
        val route = Screen.PdfViewer.createRoute(42, "Q3 Report / final")
        assertEquals("pdf-viewer/42/${Uri.encode("Q3 Report / final")}", route)
        assertTrue("numeric id stays raw", route.startsWith("pdf-viewer/42/"))
    }

    @Test
    fun `Upload createRoute percent-encodes the document uri`() {
        val uri = Uri.parse("content://media/external/file/99")
        assertEquals("upload/${Uri.encode(uri.toString())}", Screen.Upload.createRoute(uri))
    }

    @Test
    fun `MultiPageUpload createRoute joins encoded uris and appends the single-doc flag`() {
        val u1 = Uri.parse("content://docs/1")
        val u2 = Uri.parse("content://docs/2")
        val route = Screen.MultiPageUpload.createRoute(listOf(u1, u2), uploadAsSingleDocument = true)
        assertEquals(
            "upload-multi/${Uri.encode(u1.toString())}|${Uri.encode(u2.toString())}/true",
            route,
        )
    }

    @Test
    fun `DocumentDetail createRoute embeds the raw int id`() {
        assertEquals("document/7", Screen.DocumentDetail.createRoute(7))
    }
}
