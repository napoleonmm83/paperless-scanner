package com.paperless.scanner.ui.screens.upload.components

import com.paperless.scanner.domain.model.Correspondent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CorrespondentCreateCandidateTest {

    private val existing = listOf(Correspondent(id = 1, name = "ACME AG"), Correspondent(id = 2, name = "Swisscom"))

    @Test
    fun `new name is offered trimmed`() {
        assertEquals("Migros", correspondentCreateCandidate("  Migros ", existing))
    }

    @Test
    fun `partial match is still offered`() {
        assertEquals("ACME", correspondentCreateCandidate("ACME", existing))
    }

    @Test
    fun `exact match ignoring case is not offered`() {
        assertNull(correspondentCreateCandidate("acme ag", existing))
    }

    @Test
    fun `blank input is not offered`() {
        assertNull(correspondentCreateCandidate("   ", existing))
    }
}
