package com.paperless.scanner.ui.screens.documents.sharelinks

import java.time.ZoneOffset
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Paperless sends share-link dates with the server's offset, not only in UTC. */
class ReadableDateTest {
    @Test fun `offset and UTC timestamps of the same instant format identically`() {
        val utc = readableDate("2026-10-15T08:00:00Z", Locale.US, ZoneOffset.UTC)
        assertNotEquals("2026-10-15T08:00:00Z", utc)
        assertEquals(utc, readableDate("2026-10-15T10:00:00+02:00", Locale.US, ZoneOffset.UTC))
    }

    @Test fun `unparseable value falls back to the raw text`() {
        assertEquals("not a date", readableDate("not a date", Locale.US, ZoneOffset.UTC))
    }
}
