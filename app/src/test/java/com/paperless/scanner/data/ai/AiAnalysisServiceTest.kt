package com.paperless.scanner.data.ai

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Unit tests for AiAnalysisService.
 *
 * Note: These tests focus on the JSON extraction logic.
 * The actual Firebase AI integration is tested in integration tests.
 */
@RunWith(RobolectricTestRunner::class)
class AiAnalysisServiceTest {

    private lateinit var aiAnalysisService: AiAnalysisService
    private lateinit var context: Context

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        aiAnalysisService = AiAnalysisService(context)
    }

    // ==================== JSON Extraction Tests ====================

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

    // ==================== Helper Methods ====================

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
