package com.paperless.scanner.data.service

import com.google.gson.Gson
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DocumentSerializerTest {

    private lateinit var gson: Gson
    private lateinit var serializer: DocumentSerializer

    @Before
    fun setup() {
        gson = Gson()
        serializer = DocumentSerializer(gson)
    }

    // ---- serializeCustomFieldsForUpload ----

    @Test
    fun `serializeCustomFieldsForUpload returns null for null input`() {
        assertNull(serializer.serializeCustomFieldsForUpload(null))
    }

    @Test
    fun `serializeCustomFieldsForUpload returns null for empty map`() {
        assertNull(serializer.serializeCustomFieldsForUpload(emptyMap()))
    }

    @Test
    fun `serializeCustomFieldsForUpload returns RequestBody with single field as JSON list`() {
        val body = serializer.serializeCustomFieldsForUpload(mapOf(42 to "hello"))
        assertNotNull(body)
        val buffer = Buffer()
        body!!.writeTo(buffer)
        val json = buffer.readUtf8()
        assertEquals("""[{"field":42,"value":"hello"}]""", json)
        assertEquals("application/json", body.contentType()?.toString()?.substringBefore(";"))
    }

    @Test
    fun `serializeCustomFieldsForUpload preserves multiple entries`() {
        val body = serializer.serializeCustomFieldsForUpload(linkedMapOf(1 to "a", 2 to "b", 3 to 99))
        val buffer = Buffer()
        body!!.writeTo(buffer)
        val json = buffer.readUtf8()
        assertTrue(json.contains(""""field":1"""))
        assertTrue(json.contains(""""field":2"""))
        assertTrue(json.contains(""""field":3"""))
        assertTrue(json.contains(""""value":"a""""))
        assertTrue(json.contains(""""value":99"""))
    }

    // ---- deserializeCachedTagIds ----

    @Test
    fun `deserializeCachedTagIds returns empty list for null`() {
        assertEquals(emptyList<Int>(), serializer.deserializeCachedTagIds(null))
    }

    @Test
    fun `deserializeCachedTagIds returns empty list for blank input`() {
        assertEquals(emptyList<Int>(), serializer.deserializeCachedTagIds(""))
        assertEquals(emptyList<Int>(), serializer.deserializeCachedTagIds("   "))
    }

    @Test
    fun `deserializeCachedTagIds parses valid JSON int list`() {
        assertEquals(listOf(1, 2, 3), serializer.deserializeCachedTagIds("[1,2,3]"))
    }

    @Test
    fun `deserializeCachedTagIds returns empty list for malformed JSON`() {
        assertEquals(emptyList<Int>(), serializer.deserializeCachedTagIds("not json"))
        assertEquals(emptyList<Int>(), serializer.deserializeCachedTagIds("[1,2,"))
    }
}
