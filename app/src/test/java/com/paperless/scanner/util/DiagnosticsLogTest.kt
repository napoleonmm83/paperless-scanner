package com.paperless.scanner.util

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pin for the buffer behind the user-sendable diagnostic report.
 *
 * Two properties matter here and they pull in opposite directions: the buffer has to
 * carry enough to explain a failure, and it must never carry a credential or the address
 * of the user's private server. Sanitizing happens on the way IN, so these tests assert
 * against what was STORED, not against what a report builder later prints.
 */
class DiagnosticsLogTest {

    @Before
    fun setup() = DiagnosticsLog.clear()

    @After
    fun tearDown() = DiagnosticsLog.clear()

    @Test
    fun `an authorization header never reaches the buffer`() {
        // The single most likely secret in a log line: the app sends `Authorization:
        // Token <value>` on every request, and OkHttp's logging interceptor prints
        // headers. This is not JSON, so the existing sanitizeErrorBody regex never
        // matched it.
        DiagnosticsLog.append("Http", "--> GET /api/documents/ Authorization: Token abc123def456ghi789")

        val stored = DiagnosticsLog.snapshot().single()

        assertFalse("the token was stored verbatim", stored.contains("abc123def456ghi789"))
        assertTrue("the redaction is not visible", stored.contains("[REDACTED]"))
        // The request still has to be identifiable, or the buffer is useless.
        assertTrue(stored.contains("/api/documents/"))
    }

    @Test
    fun `the server host is replaced but the path survives`() {
        // The path says WHAT was attempted and is the diagnostically valuable half; the
        // host says where someone's private server lives.
        DiagnosticsLog.append("Repo", "GET https://paperless.private.example:8443/api/documents/42/download/ -> 502")

        val stored = DiagnosticsLog.snapshot().single()

        assertFalse("the host was stored", stored.contains("paperless.private.example"))
        assertFalse("the port was stored", stored.contains("8443"))
        assertTrue(stored.contains("<server>"))
        assertTrue("the path was lost", stored.contains("/api/documents/42/download/"))
        assertTrue("the status was lost", stored.contains("502"))
    }

    @Test
    fun `a bare IP address is redacted even without a scheme in front of it`() {
        // The URL rule only fires behind http:// or https://, and the most common way an
        // address appears in a log has no scheme at all — OkHttp's connect failure, this
        // app's own cleartext-allowlist line. Those walked straight through into the
        // report a user mails us. Found by a cold reader; the suite was green.
        DiagnosticsLog.append("Http", "Failed to connect to server/192.168.178.20:443")

        val stored = DiagnosticsLog.snapshot().single()

        assertFalse("the address was stored", stored.contains("192.168.178.20"))
        assertTrue(stored.contains("<ip>"))
        assertTrue("the failure itself was lost", stored.contains("Failed to connect"))
    }

    @Test
    fun `a version number is not mistaken for an address`() {
        // The negative control for the rule above. Three parts, not four — without this
        // the IP rule could quietly redact every version string in the report and the
        // test above would still pass.
        DiagnosticsLog.append("App", "starting Paperless Scanner 1.5.242")

        assertTrue(DiagnosticsLog.snapshot().single().contains("1.5.242"))
    }

    @Test
    fun `a known host is redacted by value where no pattern could find it`() {
        // A hostname has no shape that separates it from a package name, so the line
        // sanitizer cannot catch it. Redacting the KNOWN value can — and this is the one
        // that matters, because the host is the user's own server address.
        val line = """java.net.UnknownHostException: Unable to resolve host "paperless.private.example""""

        val cleaned = LogSanitizer.redactKnownHost(line, "https://paperless.private.example:8443/")

        assertFalse(cleaned.contains("paperless.private.example"))
        assertTrue(cleaned.contains("<server>"))
        assertTrue("the diagnosis was thrown away with the host", cleaned.contains("UnknownHostException"))
    }

    @Test
    fun `nothing is redacted when no server is configured`() {
        // Negative control: with a null URL the redaction must be a no-op rather than
        // matching the empty string and shredding the report.
        val line = "java.net.UnknownHostException: Unable to resolve host"

        assertEquals(line, LogSanitizer.redactKnownHost(line, null))
        assertEquals(line, LogSanitizer.redactKnownHost(line, ""))
    }

    @Test
    fun `a bearer token in any casing is caught`() {
        DiagnosticsLog.append("Auth", "retry with bearer eyJhbGciOiJIUzI1NiJ9payload")

        assertFalse(DiagnosticsLog.snapshot().single().contains("eyJhbGciOiJIUzI1NiJ9payload"))
    }

    @Test
    fun `the line cap evicts the oldest first`() {
        repeat(DiagnosticsLog.MAX_LINES + 50) { DiagnosticsLog.append("T", "line $it") }

        val stored = DiagnosticsLog.snapshot()

        assertEquals(DiagnosticsLog.MAX_LINES, stored.size)
        assertFalse("the oldest line survived eviction", stored.first().contains("line 0"))
        assertTrue("the newest line was evicted", stored.last().contains("line ${DiagnosticsLog.MAX_LINES + 49}"))
    }

    @Test
    fun `the character cap evicts before the line cap when lines are long`() {
        // The counterpart to the test above, and it corrected an assumption while being
        // written. The first version pushed twenty 4000-character lines and expected
        // eviction; it failed, because sanitizeLogLine truncates every line to
        // LOG_LINE_LIMIT on the way in, so twenty fat lines are only ~8k in the buffer.
        //
        // The caps therefore divide the work: with lines at the per-line limit the
        // character budget is reached at roughly 32000/400 ≈ 80 entries, long before 300
        // — so for verbose logging the character cap governs, and for terse logging the
        // line cap does. Both are load-bearing; neither alone bounds the buffer.
        val long = "x".repeat(LogSanitizer.LOG_LINE_LIMIT * 2)
        repeat(200) { DiagnosticsLog.append("T", long) }

        val stored = DiagnosticsLog.snapshot()

        assertTrue(
            "the character cap never fired: ${stored.size} entries held",
            stored.size < DiagnosticsLog.MAX_LINES
        )
        assertTrue(
            "the buffer grew past its character budget",
            stored.sumOf { it.length } <= DiagnosticsLog.MAX_CHARS + LogSanitizer.LOG_LINE_LIMIT + 64
        )
    }

    @Test
    fun `an ordinary line is kept readable`() {
        // The positive control. Every test above proves something was REMOVED; without
        // this one they would all pass just as well if append stored nothing at all.
        DiagnosticsLog.append("PdfViewer", "download failed: ContentError HTTP 406")

        val stored = DiagnosticsLog.snapshot().single()

        assertTrue(stored.contains("PdfViewer"))
        assertTrue(stored.contains("ContentError HTTP 406"))
    }

    @Test
    fun `a newline cannot split one entry into two`() {
        // A stack trace arrives as one message; if it stayed multi-line it would break
        // the one-entry-per-line shape the report and any grep over it rely on.
        DiagnosticsLog.append("T", "first\nsecond\r\nthird")

        assertEquals(1, DiagnosticsLog.snapshot().size)
        assertFalse(DiagnosticsLog.snapshot().single().contains("\n"))
    }
}
