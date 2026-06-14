package com.paperless.scanner.util

import com.paperless.scanner.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM unit tests for ServerUrlParser.
 *
 * Issue #233 added userScheme capture and partial-IP rejection. Issue #137 / #99
 * conventions: one @Test per case, plain JUnit assertions, no Robolectric.
 */
class ServerUrlParserTest {

    // region userScheme capture (Issue #233 AC-A)

    @Test
    fun `explicit http scheme is captured`() {
        val result = ServerUrlParser.parse("http://192.168.1.1") as ServerUrlParser.ParseResult.Success
        assertEquals("http", result.userScheme)
        assertEquals("192.168.1.1", result.host)
    }

    @Test
    fun `explicit https scheme is captured`() {
        val result = ServerUrlParser.parse("https://paperless.example.com") as ServerUrlParser.ParseResult.Success
        assertEquals("https", result.userScheme)
        assertEquals("paperless.example.com", result.host)
    }

    @Test
    fun `uppercase scheme is captured case-insensitive`() {
        val result = ServerUrlParser.parse("HTTPS://X.Y") as ServerUrlParser.ParseResult.Success
        assertEquals("https", result.userScheme)
        // Domain itself is normalized lowercase
        assertEquals("x.y", result.host)
    }

    @Test
    fun `no scheme yields null userScheme`() {
        val result = ServerUrlParser.parse("192.168.1.1") as ServerUrlParser.ParseResult.Success
        assertNull(result.userScheme)
        assertEquals("192.168.1.1", result.host)
    }

    @Test
    fun `IPv6 with no scheme yields null userScheme`() {
        val result = ServerUrlParser.parse("[::1]:8000") as ServerUrlParser.ParseResult.Success
        assertNull(result.userScheme)
        assertEquals("::1", result.host)
        assertEquals(8000, result.port)
        assertTrue(result.isIpv6)
    }

    @Test
    fun `IPv6 with explicit https scheme captures scheme`() {
        val result = ServerUrlParser.parse("https://[2001:db8::1]:8000") as ServerUrlParser.ParseResult.Success
        assertEquals("https", result.userScheme)
        assertEquals("2001:db8::1", result.host)
        assertEquals(8000, result.port)
    }

    @Test
    fun `port-only domain captures scheme and port`() {
        val result = ServerUrlParser.parse("http://server.local:8000") as ServerUrlParser.ParseResult.Success
        assertEquals("http", result.userScheme)
        assertEquals("server.local", result.host)
        assertEquals(8000, result.port)
    }

    // endregion

    // region Trailing-dot rejection (Issue #233 AC-B, memory 24099)

    @Test
    fun `trailing dot in partial IPv4 is rejected`() {
        val result = ServerUrlParser.parse("192.")
        assertTrue("Expected Error for '192.', got $result", result is ServerUrlParser.ParseResult.Error)
        assertEquals(R.string.error_invalid_server_address, (result as ServerUrlParser.ParseResult.Error).messageResId)
    }

    @Test
    fun `trailing dot after two octets is rejected`() {
        val result = ServerUrlParser.parse("192.168.")
        assertTrue("Expected Error for '192.168.'", result is ServerUrlParser.ParseResult.Error)
    }

    @Test
    fun `trailing dot after three octets is rejected`() {
        val result = ServerUrlParser.parse("192.168.1.")
        assertTrue("Expected Error for '192.168.1.'", result is ServerUrlParser.ParseResult.Error)
    }

    @Test
    fun `trailing dot after explicit scheme is rejected`() {
        val result = ServerUrlParser.parse("https://192.168.1.")
        assertTrue("Expected Error for 'https://192.168.1.'", result is ServerUrlParser.ParseResult.Error)
    }

    @Test
    fun `leading dot is rejected`() {
        val result = ServerUrlParser.parse(".com")
        assertTrue("Expected Error for '.com'", result is ServerUrlParser.ParseResult.Error)
    }

    // endregion

    // region Partial IPv4 / single-label all-digit rejection (Issue #233 AC-B)

    @Test
    fun `single-label all-digit host is rejected`() {
        val result = ServerUrlParser.parse("192")
        assertTrue("Expected Error for '192'", result is ServerUrlParser.ParseResult.Error)
    }

    @Test
    fun `single-label all-digit host with explicit http scheme is rejected`() {
        val result = ServerUrlParser.parse("http://192")
        assertTrue("Expected Error for 'http://192'", result is ServerUrlParser.ParseResult.Error)
    }

    // endregion

    // region Embedded-scheme / colon-bearing host rejection (Crashlytics f7caf99c)

    @Test
    fun `embedded http scheme without slashes is rejected`() {
        // "http:192.168.178.158:8010" — user typed "http:" without "//". Previously
        // parsed as Success(host="http:192.168.178.158"), which made ProtocolDetector
        // build "https://http:192.168.178.158:8010/api/" and crash OkHttp with
        // "Invalid URL port". Must now be Error so the detect-gate holds.
        val result = ServerUrlParser.parse("http:192.168.178.158:8010")
        assertTrue(
            "Expected Error for 'http:192.168.178.158:8010', got $result",
            result is ServerUrlParser.ParseResult.Error
        )
        assertEquals(
            R.string.error_invalid_server_address,
            (result as ServerUrlParser.ParseResult.Error).messageResId
        )
    }

    @Test
    fun `embedded https scheme without slashes is rejected`() {
        val result = ServerUrlParser.parse("https:host:8000")
        assertTrue(
            "Expected Error for 'https:host:8000', got $result",
            result is ServerUrlParser.ParseResult.Error
        )
    }

    // endregion

    // region Regression: existing valid inputs still parse (no behavior change)

    @Test
    fun `complete IPv4 still parses`() {
        val result = ServerUrlParser.parse("192.168.1.100")
        assertTrue(result is ServerUrlParser.ParseResult.Success)
        result as ServerUrlParser.ParseResult.Success
        assertEquals("192.168.1.100", result.host)
        assertNull(result.port)
        assertFalse(result.isIpv6)
    }

    @Test
    fun `complete IPv4 with port still parses`() {
        val result = ServerUrlParser.parse("192.168.1.100:8000")
        assertTrue(result is ServerUrlParser.ParseResult.Success)
        result as ServerUrlParser.ParseResult.Success
        assertEquals("192.168.1.100", result.host)
        assertEquals(8000, result.port)
    }

    @Test
    fun `domain with dot still parses`() {
        val result = ServerUrlParser.parse("paperless.example.com")
        assertTrue(result is ServerUrlParser.ParseResult.Success)
        result as ServerUrlParser.ParseResult.Success
        assertEquals("paperless.example.com", result.host)
        assertNull(result.userScheme)
    }

    @Test
    fun `localhost still parses`() {
        val result = ServerUrlParser.parse("localhost")
        assertTrue(result is ServerUrlParser.ParseResult.Success)
        result as ServerUrlParser.ParseResult.Success
        assertEquals("localhost", result.host)
    }

    @Test
    fun `two-label host with digits parses as domain`() {
        // "192.168" is syntactically a two-label hostname. We don't reject these;
        // network detection will return server-not-found. AC #233 only rejects
        // single-label all-digit hosts ("192" alone).
        val result = ServerUrlParser.parse("192.168")
        assertTrue("Expected Success for '192.168' (parser accepts, network rejects)", result is ServerUrlParser.ParseResult.Success)
    }

    @Test
    fun `whitespace is stripped`() {
        val result = ServerUrlParser.parse("  192.168.1.1  ")
        assertTrue(result is ServerUrlParser.ParseResult.Success)
        result as ServerUrlParser.ParseResult.Success
        assertEquals("192.168.1.1", result.host)
    }

    @Test
    fun `empty input is rejected`() {
        val result = ServerUrlParser.parse("")
        assertTrue(result is ServerUrlParser.ParseResult.Error)
    }

    @Test
    fun `whitespace-only input is rejected`() {
        val result = ServerUrlParser.parse("   ")
        assertTrue(result is ServerUrlParser.ParseResult.Error)
    }

    // endregion

    // region toHostString helper

    @Test
    fun `toHostString includes port when present`() {
        val success = ServerUrlParser.ParseResult.Success(
            host = "192.168.1.1",
            port = 8000,
            isIpv6 = false,
            userScheme = "http"
        )
        assertEquals("192.168.1.1:8000", success.toHostString())
    }

    @Test
    fun `toHostString wraps IPv6 in brackets`() {
        val success = ServerUrlParser.ParseResult.Success(
            host = "::1",
            port = 8000,
            isIpv6 = true
        )
        assertEquals("[::1]:8000", success.toHostString())
    }

    // endregion

    // region URL with path is stripped

    @Test
    fun `URL with path strips path keeps scheme and host`() {
        val result = ServerUrlParser.parse("http://192.168.1.1:8000/api/")
        assertTrue(result is ServerUrlParser.ParseResult.Success)
        result as ServerUrlParser.ParseResult.Success
        assertEquals("http", result.userScheme)
        assertEquals("192.168.1.1", result.host)
        assertEquals(8000, result.port)
    }

    // endregion
}
