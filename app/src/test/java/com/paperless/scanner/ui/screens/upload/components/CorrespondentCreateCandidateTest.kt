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

    // --- query re-seeding (CodeRabbit #438: restored typing must survive a late list load) ---

    @Test
    fun `list loading after recreation does not re-seed the same selection`() {
        // Seeded for id 5 before rotation; after restore the name arrives late.
        assertNull(correspondentQueryReseedKey(seededKey = "5", selectedId = 5, selectedName = null))
        assertNull(correspondentQueryReseedKey(seededKey = "5", selectedId = 5, selectedName = "ACME AG"))
    }

    @Test
    fun `a different selection re-seeds once its name is known`() {
        assertNull(correspondentQueryReseedKey(seededKey = "5", selectedId = 9, selectedName = null))
        assertEquals("9", correspondentQueryReseedKey(seededKey = "5", selectedId = 9, selectedName = "Migros"))
    }

    @Test
    fun `clearing the selection re-seeds to empty, first composition seeds`() {
        assertEquals("none", correspondentQueryReseedKey(seededKey = "5", selectedId = null, selectedName = null))
        assertEquals("none", correspondentQueryReseedKey(seededKey = "unseeded", selectedId = null, selectedName = null))
        assertNull(correspondentQueryReseedKey(seededKey = "none", selectedId = null, selectedName = null))
    }
}
