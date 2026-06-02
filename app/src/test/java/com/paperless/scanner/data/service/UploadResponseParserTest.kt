package com.paperless.scanner.data.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Regression tests pinning the `post_document` plain-string parsing contract (#135).
 * Paperless-ngx returns the task UUID as a raw string, optionally quoted/whitespaced.
 */
class UploadResponseParserTest {

    @Test
    fun `parses quoted task id`() {
        assertEquals("task-uuid-123", UploadResponseParser.parseTaskId("\"task-uuid-123\""))
    }

    @Test
    fun `parses unquoted task id`() {
        assertEquals("task-uuid-123", UploadResponseParser.parseTaskId("task-uuid-123"))
    }

    @Test
    fun `trims surrounding whitespace and trailing newline`() {
        assertEquals("task-uuid-123", UploadResponseParser.parseTaskId("  \"task-uuid-123\"\n"))
    }

    @Test
    fun `trims whitespace inside quotes`() {
        assertEquals("task-uuid-123", UploadResponseParser.parseTaskId("\" task-uuid-123 \""))
    }

    @Test
    fun `throws on blank body`() {
        assertThrows(UploadResponseParseException::class.java) {
            UploadResponseParser.parseTaskId("   ")
        }
    }

    @Test
    fun `throws on empty quotes`() {
        assertThrows(UploadResponseParseException::class.java) {
            UploadResponseParser.parseTaskId("\"\"")
        }
    }
}
