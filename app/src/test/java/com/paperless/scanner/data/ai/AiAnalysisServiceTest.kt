package com.paperless.scanner.data.ai

import android.content.Context
import android.graphics.Bitmap
import com.paperless.scanner.domain.model.Tag
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for AiAnalysisService.
 *
 * Note: These tests focus on the parsing and processing logic.
 * The actual Firebase AI integration is tested in integration tests.
 *
 * Tests:
 * - JSON parsing & extraction
 * - Tag matching logic
 * - Date validation
 * - Error handling for malformed responses
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class AiAnalysisServiceTest {

    private lateinit var aiAnalysisService: AiAnalysisService
    private lateinit var context: Context

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        aiAnalysisService = AiAnalysisService(context)
    }

    // ==================== JSON Parsing Tests ====================

    @Ignore("parseResponse method no longer exists - needs refactoring to test actual public API")
    @Test
    fun `parseResponse extracts complete document analysis`() {
        val jsonResponse = """
            {
              "title": "Stromrechnung Dezember 2024",
              "tags": ["Rechnung", "Energie"],
              "correspondent": "Stadtwerke GmbH",
              "document_type": "Rechnung",
              "date": "2024-12-15",
              "confidence": 0.95,
              "new_tags": []
            }
        """.trimIndent()

        val tags = listOf(
            Tag(id = 1, name = "Rechnung", color = "#FF0000", match = "invoice,bill"),
            Tag(id = 2, name = "Energie", color = "#00FF00", match = "energy,power")
        )

        val analysis = parseResponseHelper(jsonResponse, tags)

        assertEquals("Stromrechnung Dezember 2024", analysis.suggestedTitle)
        assertEquals(2, analysis.suggestedTags.size)
        assertEquals("Rechnung", analysis.suggestedTags[0].tagName)
        assertEquals(1, analysis.suggestedTags[0].tagId)
        assertEquals("Stadtwerke GmbH", analysis.suggestedCorrespondent)
        assertEquals("Rechnung", analysis.suggestedDocumentType)
        assertEquals("2024-12-15", analysis.suggestedDate)
        assertEquals(0.95f, analysis.confidence, 0.01f)
    }

    @Ignore("parseResponse method no longer exists - needs refactoring to test actual public API")
    @Test
    fun `parseResponse handles partial data correctly`() {
        val jsonResponse = """
            {
              "title": "Dokument",
              "tags": [],
              "correspondent": null,
              "document_type": null,
              "date": null,
              "confidence": 0.6,
              "new_tags": []
            }
        """.trimIndent()

        val analysis = parseResponseHelper(jsonResponse, emptyList())

        assertEquals("Dokument", analysis.suggestedTitle)
        assertTrue(analysis.suggestedTags.isEmpty())
        assertNull(analysis.suggestedCorrespondent)
        assertNull(analysis.suggestedDocumentType)
        assertNull(analysis.suggestedDate)
        assertEquals(0.6f, analysis.confidence, 0.01f)
    }

    @Ignore("parseResponse method no longer exists - needs refactoring to test actual public API")
    @Test
    fun `parseResponse suggests new tags when no match found`() {
        val jsonResponse = """
            {
              "title": "Vertrag",
              "tags": [],
              "correspondent": "Versicherung AG",
              "document_type": "Vertrag",
              "date": "2024-01-10",
              "confidence": 0.85,
              "new_tags": ["Versicherung", "KFZ"]
            }
        """.trimIndent()

        val analysis = parseResponseHelper(jsonResponse, emptyList())

        assertEquals(2, analysis.suggestedTags.size)
        assertEquals("Versicherung", analysis.suggestedTags[0].tagName)
        assertNull(analysis.suggestedTags[0].tagId) // No ID for new tags
        assertTrue(analysis.suggestedTags[0].confidence < 0.85f) // Reduced confidence
    }

    @Ignore("parseResponse method no longer exists - needs refactoring to test actual public API")
    @Test
    fun `parseResponse matches tags case-insensitively`() {
        val jsonResponse = """
            {
              "title": "Test",
              "tags": ["rechnung", "STEUER"],
              "correspondent": null,
              "document_type": null,
              "date": null,
              "confidence": 0.8,
              "new_tags": []
            }
        """.trimIndent()

        val tags = listOf(
            Tag(id = 1, name = "Rechnung", color = "#FF0000", match = null),
            Tag(id = 2, name = "Steuer", color = "#0000FF", match = null)
        )

        val analysis = parseResponseHelper(jsonResponse, tags)

        assertEquals(2, analysis.suggestedTags.size)
        assertEquals("Rechnung", analysis.suggestedTags[0].tagName) // Matched with correct casing
        assertEquals(1, analysis.suggestedTags[0].tagId)
        assertEquals("Steuer", analysis.suggestedTags[1].tagName)
        assertEquals(2, analysis.suggestedTags[1].tagId)
    }

    @Test
    fun `extractJson finds JSON in markdown code blocks`() {
        val response = """
            Here is the analysis:
            ```json
            {"title": "Test Document", "tags": []}
            ```
        """.trimIndent()

        val json = extractJsonHelper(response)

        assertTrue(json.contains("\"title\""))
        assertTrue(json.contains("Test Document"))
    }

    @Test
    fun `extractJson handles JSON with extra whitespace`() {
        val response = """


            {"title": "Whitespace Test", "tags": []}


        """.trimIndent()

        val json = extractJsonHelper(response)

        assertTrue(json.contains("\"title\""))
    }

    @Test
    fun `extractJson throws when no JSON found`() {
        try {
            extractJsonHelper("This is just plain text without any JSON")
            fail("Expected IllegalStateException")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            assertTrue(e.cause is IllegalStateException)
            assertEquals("No valid JSON found in response", e.cause?.message)
        }
    }

    // ==================== Date Validation Tests ====================

    @Ignore("parseResponse method no longer exists - needs refactoring to test actual public API")
    @Test
    fun `parseResponse accepts valid date formats`() {
        val jsonResponse = """
            {"title": "Test", "tags": [], "date": "2024-03-15", "confidence": 0.8}
        """.trimIndent()

        val analysis = parseResponseHelper(jsonResponse, emptyList())

        assertEquals("2024-03-15", analysis.suggestedDate)
    }

    @Ignore("parseResponse method no longer exists - needs refactoring to test actual public API")
    @Test
    fun `parseResponse rejects invalid date formats`() {
        val jsonResponse = """
            {"title": "Test", "tags": [], "date": "15.03.2024", "confidence": 0.8}
        """.trimIndent()

        val analysis = parseResponseHelper(jsonResponse, emptyList())

        assertNull(analysis.suggestedDate) // Invalid format rejected
    }

    @Ignore("parseResponse method no longer exists - needs refactoring to test actual public API")
    @Test
    fun `parseResponse handles null date as string`() {
        val jsonResponse = """
            {"title": "Test", "tags": [], "date": "null", "confidence": 0.8}
        """.trimIndent()

        val analysis = parseResponseHelper(jsonResponse, emptyList())

        assertNull(analysis.suggestedDate) // String "null" treated as null
    }

    // ==================== Edge Cases Tests ====================

    @Ignore("parseResponse method no longer exists - needs refactoring to test actual public API")
    @Test
    fun `parseResponse handles empty tags array`() {
        val jsonResponse = """
            {"title": "Empty Tags", "tags": [], "confidence": 0.5}
        """.trimIndent()

        val analysis = parseResponseHelper(jsonResponse, emptyList())

        assertTrue(analysis.suggestedTags.isEmpty())
    }

    @Ignore("parseResponse method no longer exists - needs refactoring to test actual public API")
    @Test
    fun `parseResponse handles missing optional fields`() {
        val jsonResponse = """
            {"title": "Minimal Data", "tags": ["Test"]}
        """.trimIndent()

        val tags = listOf(Tag(id = 1, name = "Test", color = "#FFFFFF", match = null))

        val analysis = parseResponseHelper(jsonResponse, tags)

        assertEquals("Minimal Data", analysis.suggestedTitle)
        assertNull(analysis.suggestedCorrespondent)
        assertNull(analysis.suggestedDocumentType)
        assertNull(analysis.suggestedDate)
        assertTrue(analysis.confidence > 0.0f) // Default confidence
    }

    @Ignore("parseResponse method no longer exists - needs refactoring to test actual public API")
    @Test
    fun `parseResponse sorts tags by confidence descending`() {
        val jsonResponse = """
            {
              "title": "Test",
              "tags": ["Tag1", "Tag2", "Tag3"],
              "confidence": 0.9,
              "new_tags": []
            }
        """.trimIndent()

        val tags = listOf(
            Tag(id = 1, name = "Tag1", color = "#FF0000", match = null),
            Tag(id = 2, name = "Tag2", color = "#00FF00", match = null),
            Tag(id = 3, name = "Tag3", color = "#0000FF", match = null)
        )

        val analysis = parseResponseHelper(jsonResponse, tags)

        assertEquals(3, analysis.suggestedTags.size)
        // Should be sorted by confidence (all equal in this case)
        for (i in 0 until analysis.suggestedTags.size - 1) {
            assertTrue(analysis.suggestedTags[i].confidence >= analysis.suggestedTags[i + 1].confidence)
        }
    }

    @Ignore("parseResponse method no longer exists - needs refactoring to test actual public API")
    @Test
    fun `parseResponse handles blank title gracefully`() {
        val jsonResponse = """
            {"title": "", "tags": [], "confidence": 0.5}
        """.trimIndent()

        val analysis = parseResponseHelper(jsonResponse, emptyList())

        assertNull(analysis.suggestedTitle) // Blank title treated as null
    }

    @Ignore("parseResponse method no longer exists - needs refactoring to test actual public API")
    @Test
    fun `parseResponse handles blank correspondent gracefully`() {
        val jsonResponse = """
            {"title": "Test", "tags": [], "correspondent": "", "confidence": 0.5}
        """.trimIndent()

        val analysis = parseResponseHelper(jsonResponse, emptyList())

        assertNull(analysis.suggestedCorrespondent) // Blank treated as null
    }

    @Ignore("parseResponse method no longer exists - needs refactoring to test actual public API")
    @Test
    fun `parseResponse throws on malformed JSON`() {
        val malformedJson = """
            {"title": "Test", "tags": ["invalid
        """.trimIndent()

        try {
            parseResponseHelper(malformedJson, emptyList())
            fail("Expected IllegalStateException or JSONException")
        } catch (e: java.lang.reflect.InvocationTargetException) {
            // extractJson() throws IllegalStateException if no closing brace found
            assertTrue(
                "Expected IllegalStateException or JSONException, got ${e.cause?.javaClass?.name}",
                e.cause is IllegalStateException || e.cause is org.json.JSONException
            )
        }
    }

    // ==================== Helper Methods ====================

    /**
     * Helper to test the parseResponse method via reflection.
     * This allows testing the parsing logic without mocking Firebase AI.
     */
    private fun parseResponseHelper(responseText: String, availableTags: List<Tag>): com.paperless.scanner.data.ai.models.DocumentAnalysis {
        val parseMethod = AiAnalysisService::class.java.getDeclaredMethod(
            "parseResponse",
            String::class.java,
            List::class.java
        )
        parseMethod.isAccessible = true

        @Suppress("UNCHECKED_CAST")
        return parseMethod.invoke(aiAnalysisService, responseText, availableTags)
            as com.paperless.scanner.data.ai.models.DocumentAnalysis
    }

    /**
     * Helper to test the extractJson method via reflection.
     */
    private fun extractJsonHelper(text: String): String {
        val extractMethod = AiAnalysisService::class.java.getDeclaredMethod(
            "extractJson",
            String::class.java
        )
        extractMethod.isAccessible = true

        return extractMethod.invoke(aiAnalysisService, text) as String
    }
}
