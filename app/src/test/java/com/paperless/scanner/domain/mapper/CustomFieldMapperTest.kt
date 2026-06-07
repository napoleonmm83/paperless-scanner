package com.paperless.scanner.domain.mapper

import com.paperless.scanner.data.api.models.CustomField as ApiCustomField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Confirms the [ApiCustomField.toDomain] mapper preserves every field of the
 * `/api/custom_fields/` DTO on the way to the domain
 * [com.paperless.scanner.domain.model.CustomField] model (Issue #343).
 * Pure JVM — no Android/Robolectric needed.
 */
class CustomFieldMapperTest {

    @Test
    fun `toDomain maps all fields`() {
        val domain = ApiCustomField(id = 7, name = "Betrag", dataType = "monetary").toDomain()

        assertEquals(7, domain.id)
        assertEquals("Betrag", domain.name)
        assertEquals("monetary", domain.dataType)
    }

    @Test
    fun `toDomain maps absent dataType to null`() {
        val domain = ApiCustomField(id = 1, name = "Notes").toDomain()

        assertEquals(1, domain.id)
        assertEquals("Notes", domain.name)
        assertNull(domain.dataType)
    }

    @Test
    fun `list toDomain maps every element preserving order`() {
        val domain = listOf(
            ApiCustomField(id = 1, name = "First", dataType = "string"),
            ApiCustomField(id = 2, name = "Second", dataType = "integer"),
        ).toDomain()

        assertEquals(2, domain.size)
        assertEquals(1, domain[0].id)
        assertEquals("First", domain[0].name)
        assertEquals("string", domain[0].dataType)
        assertEquals(2, domain[1].id)
        assertEquals("Second", domain[1].name)
        assertEquals("integer", domain[1].dataType)
    }
}
