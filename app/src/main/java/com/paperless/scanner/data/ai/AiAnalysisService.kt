package com.paperless.scanner.data.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.paperless.scanner.data.ai.models.DocumentAnalysis
import com.paperless.scanner.data.ai.models.TagSuggestion
import com.paperless.scanner.domain.model.Tag
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for analyzing documents using Firebase AI (Gemini).
 *
 * This service provides AI-powered document analysis including:
 * - Title extraction
 * - Tag suggestions based on content
 * - Correspondent detection
 * - Document type classification
 * - Date extraction
 */
@Singleton
class AiAnalysisService @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AiAnalysisService"
        private const val MODEL_NAME = "gemini-2.5-flash-lite"
        private const val TEMPERATURE = 0.3f
        private const val MAX_OUTPUT_TOKENS = 1024
        private const val NEW_TAG_CONFIDENCE_FACTOR = 0.8f
        private val DATE_PATTERN = Regex("""\d{4}-\d{2}-\d{2}""")
    }

    private val generativeModel by lazy {
        // Firebase AI Backend (uses Firebase Vertex AI automatically via google-services.json)
        // No explicit backend parameter needed - defaults to Firebase when configured
        Firebase.ai.generativeModel(
            modelName = MODEL_NAME,
            generationConfig = generationConfig {
                temperature = TEMPERATURE
                maxOutputTokens = MAX_OUTPUT_TOKENS
            }
        )
    }

    /**
     * Analyzes an image and returns document metadata suggestions.
     *
     * @param bitmap The image to analyze
     * @param availableTags List of available tags from Paperless for matching
     * @param availableCorrespondents List of available correspondent names
     * @param availableDocumentTypes List of available document type names
     * @param allowNewTags Whether AI can suggest new tags that don't exist yet (default: true)
     * @return DocumentAnalysis with suggestions
     */
    suspend fun analyzeImage(
        bitmap: Bitmap,
        availableTags: List<Tag> = emptyList(),
        availableCorrespondents: List<String> = emptyList(),
        availableDocumentTypes: List<String> = emptyList(),
        allowNewTags: Boolean = true
    ): Result<DocumentAnalysis> = withContext(Dispatchers.IO) {
        runCatching {
            withTimeout(com.paperless.scanner.util.NetworkConfig.AI_ANALYSIS_TIMEOUT_MS) {
                android.util.Log.d(TAG, "Starting AI analysis with ${availableTags.size} available tags, allowNewTags=$allowNewTags")
                val prompt = buildPrompt(availableTags, availableCorrespondents, availableDocumentTypes)
                android.util.Log.d(TAG, "Prompt built, sending to Gemini...")

                val content = content {
                    image(bitmap)
                    text(prompt)
                }

                val response = generativeModel.generateContent(content)
                val responseText = response.text ?: throw IllegalStateException("Empty AI response")

                android.util.Log.d(TAG, "AI Response received: $responseText")

                val analysis = parseResponse(responseText, availableTags, allowNewTags)
                android.util.Log.d(TAG, "Parsed analysis: ${analysis.suggestedTags.size} tags, title=${analysis.suggestedTitle}")
                analysis
            }
        }
    }

    /**
     * Analyzes an image from URI.
     */
    suspend fun analyzeImageUri(
        uri: Uri,
        availableTags: List<Tag> = emptyList(),
        availableCorrespondents: List<String> = emptyList(),
        availableDocumentTypes: List<String> = emptyList(),
        allowNewTags: Boolean = true
    ): Result<DocumentAnalysis> = withContext(Dispatchers.IO) {
        runCatching {
            val bitmap = loadBitmapFromUri(uri)
                ?: throw IllegalArgumentException("Could not load image from URI")

            analyzeImage(bitmap, availableTags, availableCorrespondents, availableDocumentTypes, allowNewTags)
                .getOrThrow()
        }
    }

    private fun loadBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BitmapFactory.decodeStream(inputStream)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun buildPrompt(
        availableTags: List<Tag>,
        availableCorrespondents: List<String>,
        availableDocumentTypes: List<String>
    ): String {
        val tagList = if (availableTags.isNotEmpty()) {
            availableTags.joinToString(", ") { tag ->
                val desc = tag.match?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""
                "${tag.name}$desc"
            }
        } else {
            "keine Tags verfügbar"
        }

        val correspondentList = if (availableCorrespondents.isNotEmpty()) {
            availableCorrespondents.joinToString(", ")
        } else {
            "keine Korrespondenten verfügbar"
        }

        val docTypeList = if (availableDocumentTypes.isNotEmpty()) {
            availableDocumentTypes.joinToString(", ")
        } else {
            "keine Dokumenttypen verfügbar"
        }

        return """
            |Analysiere dieses Dokument und extrahiere Metadaten.
            |
            |VERFÜGBARE TAGS: $tagList
            |VERFÜGBARE KORRESPONDENTEN: $correspondentList
            |VERFÜGBARE DOKUMENTTYPEN: $docTypeList
            |
            |Antworte NUR mit einem JSON-Objekt im folgenden Format:
            |{
            |  "title": "Kurzer, beschreibender Titel des Dokuments",
            |  "tags": ["tag1", "tag2"],
            |  "correspondent": "Name des Absenders/Korrespondenten oder null",
            |  "document_type": "Dokumenttyp oder null",
            |  "date": "YYYY-MM-DD oder null",
            |  "confidence": 0.0-1.0,
            |  "new_tags": ["vorgeschlagene neue Tags die noch nicht existieren"]
            |}
            |
            |REGELN:
            |1. Wähle passende Tags aus der Liste der verfügbaren Tags (falls vorhanden)
            |2. WICHTIG: Schlage IMMER mindestens 2-3 neue Tags in "new_tags" vor, die das Dokument kategorisieren!
            |   Beispiele für sinnvolle Tags:
            |   - Dokumentart: Rechnung, Vertrag, Brief, Anleitung, Etikett, Garantie
            |   - Kategorie: Finanzen, Technik, Hardware, Versicherung, Gesundheit
            |   - Firma/Marke: Samsung, Amazon, Telekom (wenn erkennbar)
            |   - Status: Wichtig, Archiv, Ablage
            |3. Der Titel sollte kurz und beschreibend sein (max 50 Zeichen)
            |4. Erkenne Datum, Absender und Dokumenttyp aus dem Inhalt
            |5. Bei Rechnungen: Suche nach Rechnungsnummer, Betrag, Firma
            |6. Confidence gibt an, wie sicher die Analyse ist (0.0 = unsicher, 1.0 = sicher)
            |
            |WICHTIG: Das Feld "new_tags" sollte NIEMALS leer sein! Schlage immer relevante Tags vor.
            |
            |Antworte NUR mit dem JSON, keine weitere Erklärung.
        """.trimMargin()
    }

    private fun parseResponse(responseText: String, availableTags: List<Tag>, allowNewTags: Boolean): DocumentAnalysis {
        val jsonString = extractJson(responseText)
        val json = JSONObject(jsonString)

        val suggestedTags = mutableListOf<TagSuggestion>()

        // Parse existing tags
        val tagsArray = json.optJSONArray("tags")
        if (tagsArray != null) {
            for (i in 0 until tagsArray.length()) {
                val tagName = tagsArray.getString(i)
                val matchingTag = availableTags.find {
                    it.name.equals(tagName, ignoreCase = true)
                }
                suggestedTags.add(
                    TagSuggestion(
                        tagId = matchingTag?.id,
                        tagName = matchingTag?.name ?: tagName,
                        confidence = json.optDouble("confidence", 0.8).toFloat(),
                        reason = TagSuggestion.REASON_AI_DETECTED
                    )
                )
            }
        }

        // Parse new tag suggestions (only if allowed by user setting)
        if (allowNewTags) {
            val newTagsArray = json.optJSONArray("new_tags")
            if (newTagsArray != null) {
                for (i in 0 until newTagsArray.length()) {
                    val tagName = newTagsArray.getString(i)
                    suggestedTags.add(
                        TagSuggestion(
                            tagId = null,
                            tagName = tagName,
                            confidence = json.optDouble("confidence", 0.7).toFloat() * NEW_TAG_CONFIDENCE_FACTOR,
                            reason = TagSuggestion.REASON_AI_DETECTED
                        )
                    )
                }
                android.util.Log.d(TAG, "New tags included: ${newTagsArray.length()} suggestions")
            }
        } else {
            android.util.Log.d(TAG, "New tags filtered out (allowNewTags=false)")
        }

        return DocumentAnalysis(
            suggestedTitle = json.optString("title").takeIf { it.isNotBlank() },
            suggestedTags = suggestedTags.sortedByDescending { it.confidence },
            suggestedCorrespondent = json.optString("correspondent").takeIf {
                it.isNotBlank() && it != "null"
            },
            suggestedDocumentType = json.optString("document_type").takeIf {
                it.isNotBlank() && it != "null"
            },
            suggestedDate = json.optString("date").takeIf {
                it.isNotBlank() && it != "null" && it.matches(DATE_PATTERN)
            },
            confidence = json.optDouble("confidence", 0.5).toFloat()
        )
    }

    private fun extractJson(text: String): String {
        // Try to find JSON in the response
        val jsonStart = text.indexOf('{')
        val jsonEnd = text.lastIndexOf('}')

        return if (jsonStart != -1 && jsonEnd != -1 && jsonEnd > jsonStart) {
            text.substring(jsonStart, jsonEnd + 1)
        } else {
            throw IllegalStateException("No valid JSON found in response")
        }
    }
}
