package com.paperless.scanner.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LogSanitizerTest {

    @Test
    fun `null body returns null`() {
        assertNull(LogSanitizer.sanitizeErrorBody(null))
    }

    @Test
    fun `empty body returns empty`() {
        assertEquals("", LogSanitizer.sanitizeErrorBody(""))
    }

    @Test
    fun `redacts token field`() {
        val result = LogSanitizer.sanitizeErrorBody("""{"token":"abc123secret"}""")
        assertEquals("""{"token":"[REDACTED]"}""", result)
    }

    @Test
    fun `redacts password secret key authorization fields`() {
        val raw = """{"password":"p","secret":"s","key":"k","authorization":"Bearer x"}"""
        val result = LogSanitizer.sanitizeErrorBody(raw)!!
        assertFalse(result.contains("\"p\""))
        assertFalse(result.contains("\"s\""))
        assertFalse(result.contains("Bearer x"))
        assertEquals(4, Regex("\\[REDACTED]").findAll(result).count())
    }

    @Test
    fun `redacts api_key and api-key variants`() {
        assertEquals("""{"api_key":"[REDACTED]"}""", LogSanitizer.sanitizeErrorBody("""{"api_key":"v"}"""))
        assertEquals("""{"api-key":"[REDACTED]"}""", LogSanitizer.sanitizeErrorBody("""{"api-key":"v"}"""))
    }

    @Test
    fun `redacts username user email fields`() {
        val raw = """{"username":"alice","user":"bob","email":"a@b.com"}"""
        val result = LogSanitizer.sanitizeErrorBody(raw)!!
        assertFalse(result.contains("alice"))
        assertFalse(result.contains("bob"))
        assertFalse(result.contains("a@b.com"))
    }

    @Test
    fun `redaction is case-insensitive and preserves field-name case`() {
        val result = LogSanitizer.sanitizeErrorBody("""{"Token":"x","EMAIL":"y"}""")!!
        assertEquals("""{"Token":"[REDACTED]","EMAIL":"[REDACTED]"}""", result)
    }

    @Test
    fun `redacts value containing an escaped quote without leaking the suffix`() {
        // Regression for codex P2: a naive "[^"]*" matcher stops at the escaped
        // quote and leaves `cd"}` in the log. The JSON-escape-aware matcher must
        // redact the whole value.
        val result = LogSanitizer.sanitizeErrorBody("""{"token":"ab\"cd"}""")!!
        assertFalse(result.contains("cd"))
        assertEquals("""{"token":"[REDACTED]"}""", result)
    }

    @Test
    fun `redacts value containing an escaped backslash`() {
        val result = LogSanitizer.sanitizeErrorBody("""{"secret":"a\\b"}""")!!
        assertEquals("""{"secret":"[REDACTED]"}""", result)
    }

    @Test
    fun `non-sensitive fields are left untouched`() {
        val raw = """{"detail":"document not found","count":3}"""
        assertEquals(raw, LogSanitizer.sanitizeErrorBody(raw))
    }

    @Test
    fun `escapes newlines and carriage returns`() {
        assertEquals("line1\\nline2\\rline3", LogSanitizer.sanitizeErrorBody("line1\nline2\rline3"))
    }

    @Test
    fun `truncates to default limit`() {
        val raw = "x".repeat(500)
        val result = LogSanitizer.sanitizeErrorBody(raw)!!
        assertEquals(LogSanitizer.ERROR_BODY_LOG_LIMIT, result.length)
    }

    @Test
    fun `truncates to custom limit`() {
        assertEquals("abcde", LogSanitizer.sanitizeErrorBody("abcdefghij", limit = 5))
    }

    @Test
    fun `truncation happens after redaction so a secret is never half-exposed`() {
        // The sensitive value sits past the limit; redaction must run first so the
        // raw secret can never survive into the (then truncated) output.
        val raw = """{"padding":"${"P".repeat(50)}","token":"SUPERSECRETVALUE"}"""
        val result = LogSanitizer.sanitizeErrorBody(raw, limit = 80)!!
        assertFalse(result.contains("SUPERSECRETVALUE"))
        assertTrue(result.length <= 80)
    }
}
