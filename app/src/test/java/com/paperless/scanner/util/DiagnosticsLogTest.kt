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
        DiagnosticsLog.append("Http", "--> GET /api/documents/ Authorization: Token PLACEHOLDER-NOT-A-REAL-TOKEN-00000")

        val stored = DiagnosticsLog.snapshot().single()

        assertFalse("the token was stored verbatim", stored.contains("PLACEHOLDER-NOT-A-REAL-TOKEN-00000"))
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
    fun `an ordinary sentence after Token is not mistaken for a credential`() {
        // Negative control, and it caught a real over-redaction: the rule used to treat any
        // long word after Token/Bearer/Basic as a secret, so "Token validation network
        // error" — a line AuthRepository actually logs — became "Token [REDACTED] network
        // error". That shreds exactly the auth diagnosis the report exists to carry. Real
        // credentials carry digits; English words do not.
        DiagnosticsLog.append("Auth", "Token validation network error")
        DiagnosticsLog.append("Http", "Basic authentication required by proxy")

        val stored = DiagnosticsLog.snapshot()

        assertTrue(stored[0].contains("Token validation network error"))
        assertTrue(stored[1].contains("Basic authentication required"))
    }

    @Test
    fun `a letter-only credential is caught too`() {
        // The hole a reviewer found in the fix for the test above: requiring a digit made
        // "Token validation network error" survive, and let `Bearer abcdefghijkl` through
        // with it. Two structural branches now — a short value with something no English
        // word carries, or a long letter-only one.
        DiagnosticsLog.append("Http", "--> GET /api/ Authorization: Bearer abcdefgh")
        DiagnosticsLog.append("Auth", "sending bearer abcdefghijklmnopqrst")

        val stored = DiagnosticsLog.snapshot()

        assertFalse("a short header credential survived", stored[0].contains("abcdefgh"))
        assertFalse("a long letter-only credential survived", stored[1].contains("abcdefghijklmnopqrst"))
        assertTrue(stored[0].contains("[REDACTED]"))
        assertTrue(stored[1].contains("[REDACTED]"))
    }

    @Test
    fun `a single-label host does not shred the app's own package name`() {
        // http://paperless:8000 is ordinary on a home network — Tailscale MagicDNS, a
        // Docker service name. Redacting it without word boundaries rewrote every stack
        // frame `com.paperless.scanner` to `com.<server>.scanner`, destroying the report
        // in the name of protecting it.
        val line = "at com.paperless.scanner.ui.PdfViewerViewModel — connecting to paperless:8000"

        val cleaned = LogSanitizer.redactKnownHost(line, "http://paperless:8000")

        assertTrue("the package name was shredded", cleaned.contains("com.paperless.scanner"))
        assertTrue("the actual host survived", cleaned.contains("<server>:8000"))
    }

    @Test
    fun `an IPv6 host in brackets is cut at the bracket, not at a colon inside it`() {
        // substringBeforeLast(':') lands INSIDE the address: [2001:db8::1] became
        // "[2001:db8:" and the report then showed "<server>:1]".
        val line = "connecting to [2001:db8::1] failed"

        val cleaned = LogSanitizer.redactKnownHost(line, "http://[2001:db8::1]/")

        assertTrue("the address was cut mid-way: $cleaned", cleaned.contains("<server> failed"))
    }

    @Test
    fun `several known hosts are all redacted, longest first`() {
        // A user can have more than one: the Paperless server and a Paperless-GPT
        // instance. Longest-first matters — with both "paperless.example.com" and
        // "example.com" known, the short one first would leave "paperless.<server>".
        val line = "server paperless.example.com, gpt at example.com"

        val cleaned = LogSanitizer.redactKnownHosts(
            line,
            listOf("https://example.com", "https://paperless.example.com"),
        )

        assertEquals("server <server>, gpt at <server>", cleaned)
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
        DiagnosticsLog.append("Auth", "retry with bearer PLACEHOLDER-NOT-A-REAL-BEARER-00000")

        assertFalse(DiagnosticsLog.snapshot().single().contains("PLACEHOLDER-NOT-A-REAL-BEARER-00000"))
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
        // character budget is reached at MAX_CHARS/LOG_LINE_LIMIT entries, well before
        // MAX_LINES — so for verbose logging the character cap governs, and for terse
        // logging the line cap does. Both are load-bearing; neither alone bounds it.
        //
        // The count is DERIVED, not a literal. It was 200 while MAX_CHARS was 32000,
        // and raising the budget to 128000 would have left this test pushing far too
        // few lines to reach the cap — passing without exercising anything, which is
        // the failure mode a cap test cannot afford.
        val long = "x".repeat(LogSanitizer.LOG_LINE_LIMIT * 2)
        val genugFuerDieZeichengrenze =
            (DiagnosticsLog.MAX_CHARS / LogSanitizer.LOG_LINE_LIMIT) + 50
        repeat(genugFuerDieZeichengrenze) { DiagnosticsLog.append("T", long) }

        val stored = DiagnosticsLog.snapshot()

        // The line cap must NOT be what fired here, or this test proves nothing about
        // the character cap.
        assertTrue(
            "the line cap fired first, so the character cap was never tested",
            genugFuerDieZeichengrenze < DiagnosticsLog.MAX_LINES
        )
        assertTrue(
            "nothing was evicted at all",
            stored.size < genugFuerDieZeichengrenze
        )

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
