package com.paperless.scanner.data.ai

import com.paperless.scanner.data.ai.models.TagSuggestion
import com.paperless.scanner.domain.model.Tag
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

/**
 * Engine for matching document text against available tags.
 * Provides offline tag suggestions without API costs.
 *
 * Matching strategies (in priority order):
 * 1. Keyword Match - Uses Tag.match field patterns
 * 2. Fuzzy Name Match - Levenshtein distance on tag names
 * 3. Synonym Detection - Common document term mappings
 * 4. Frequency Boost - Prioritizes frequently used tags
 */
@Singleton
class TagMatchingEngine @Inject constructor() {

    /**
     * Find matching tags for the given text.
     *
     * @param text The document text to analyze
     * @param availableTags List of available tags to match against
     * @return List of tag suggestions sorted by confidence
     */
    fun findMatchingTags(
        text: String,
        availableTags: List<Tag>
    ): List<TagSuggestion> {
        if (text.isBlank() || availableTags.isEmpty()) {
            return emptyList()
        }

        val normalizedText = text.lowercase()
        val suggestions = mutableMapOf<Int, TagSuggestion>()

        // Strategy 1: Keyword Match (highest priority)
        availableTags.forEach { tag ->
            val keywordMatch = matchByKeyword(normalizedText, tag)
            if (keywordMatch != null && keywordMatch.confidence > (suggestions[tag.id]?.confidence ?: 0f)) {
                suggestions[tag.id] = keywordMatch
            }
        }

        // Strategy 2: Fuzzy Name Match
        availableTags.forEach { tag ->
            if (!suggestions.containsKey(tag.id)) {
                val fuzzyMatch = matchByFuzzyName(normalizedText, tag)
                if (fuzzyMatch != null) {
                    suggestions[tag.id] = fuzzyMatch
                }
            }
        }

        // Strategy 3: Synonym Detection
        availableTags.forEach { tag ->
            if (!suggestions.containsKey(tag.id)) {
                val synonymMatch = matchBySynonym(normalizedText, tag)
                if (synonymMatch != null) {
                    suggestions[tag.id] = synonymMatch
                }
            }
        }

        // Apply frequency boost
        val boostedSuggestions = suggestions.values.map { suggestion ->
            val tag = availableTags.find { it.id == suggestion.tagId }
            val boost = calculateFrequencyBoost(tag?.documentCount ?: 0)
            suggestion.copy(confidence = (suggestion.confidence * boost).coerceAtMost(MAX_CONFIDENCE))
        }

        return boostedSuggestions
            .filter { it.confidence >= MIN_CONFIDENCE }
            .sortedByDescending { it.confidence }
            .take(MAX_SUGGESTIONS)
    }

    /**
     * Match using the tag's match field (keyword/regex patterns).
     */
    private fun matchByKeyword(text: String, tag: Tag): TagSuggestion? {
        val matchPattern = tag.match?.takeIf { it.isNotBlank() } ?: return null

        // Split match field by common delimiters (comma, space, pipe)
        val keywords = matchPattern.lowercase()
            .split(Regex("[,|\\s]+"))
            .filter { it.isNotBlank() }

        val matchedKeywords = keywords.filter { keyword ->
            text.contains(keyword)
        }

        if (matchedKeywords.isEmpty()) return null

        // Confidence based on how many keywords matched
        val matchRatio = matchedKeywords.size.toFloat() / keywords.size
        val confidence = KEYWORD_BASE_CONFIDENCE + (matchRatio * KEYWORD_BONUS)

        return TagSuggestion(
            tagId = tag.id,
            tagName = tag.name,
            confidence = confidence.coerceAtMost(MAX_CONFIDENCE),
            reason = TagSuggestion.REASON_KEYWORD_MATCH
        )
    }

    /**
     * Match using fuzzy string matching on tag name.
     */
    private fun matchByFuzzyName(text: String, tag: Tag): TagSuggestion? {
        val tagName = tag.name.lowercase()
        val words = text.split(Regex("\\s+"))

        // Check if any word in the text is similar to the tag name
        val bestMatch = words
            .filter { it.length >= MIN_WORD_LENGTH }
            .mapNotNull { word ->
                val similarity = calculateSimilarity(word, tagName)
                if (similarity >= FUZZY_THRESHOLD) similarity else null
            }
            .maxOrNull()

        if (bestMatch == null) return null

        return TagSuggestion(
            tagId = tag.id,
            tagName = tag.name,
            confidence = (FUZZY_BASE_CONFIDENCE * bestMatch).toFloat(),
            reason = TagSuggestion.REASON_FUZZY_MATCH
        )
    }

    /**
     * Match using synonym mappings for common document terms.
     */
    private fun matchBySynonym(text: String, tag: Tag): TagSuggestion? {
        val tagName = tag.name.lowercase()
        val synonyms = SYNONYM_MAP[tagName] ?: return null

        val matchedSynonym = synonyms.any { synonym ->
            text.contains(synonym.lowercase())
        }

        if (!matchedSynonym) return null

        return TagSuggestion(
            tagId = tag.id,
            tagName = tag.name,
            confidence = SYNONYM_CONFIDENCE,
            reason = TagSuggestion.REASON_FUZZY_MATCH
        )
    }

    /**
     * Calculate Levenshtein similarity between two strings.
     * Returns a value between 0.0 (no match) and 1.0 (exact match).
     */
    private fun calculateSimilarity(s1: String, s2: String): Double {
        val distance = levenshteinDistance(s1, s2)
        val maxLength = maxOf(s1.length, s2.length)
        return if (maxLength == 0) 1.0 else 1.0 - (distance.toDouble() / maxLength)
    }

    /**
     * Calculate Levenshtein distance between two strings.
     */
    private fun levenshteinDistance(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length

        if (m == 0) return n
        if (n == 0) return m

        val dp = Array(m + 1) { IntArray(n + 1) }

        for (i in 0..m) dp[i][0] = i
        for (j in 0..n) dp[0][j] = j

        for (i in 1..m) {
            for (j in 1..n) {
                val cost = if (s1[i - 1] == s2[j - 1]) 0 else 1
                dp[i][j] = min(
                    min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                    dp[i - 1][j - 1] + cost
                )
            }
        }

        return dp[m][n]
    }

    /**
     * Calculate frequency boost based on document count.
     * More frequently used tags get a slight boost.
     */
    private fun calculateFrequencyBoost(documentCount: Int): Float {
        return when {
            documentCount >= 100 -> 1.15f
            documentCount >= 50 -> 1.10f
            documentCount >= 20 -> 1.05f
            documentCount >= 5 -> 1.02f
            else -> 1.0f
        }
    }

    companion object {
        private const val MAX_SUGGESTIONS = 5
        private const val MIN_CONFIDENCE = 0.3f
        private const val MAX_CONFIDENCE = 0.95f

        private const val KEYWORD_BASE_CONFIDENCE = 0.7f
        private const val KEYWORD_BONUS = 0.2f

        private const val FUZZY_THRESHOLD = 0.8
        private const val FUZZY_BASE_CONFIDENCE = 0.6

        private const val SYNONYM_CONFIDENCE = 0.55f

        private const val MIN_WORD_LENGTH = 3

        /**
         * Synonym mappings for common document terms.
         * Key: tag name (lowercase), Value: list of synonyms
         */
        private val SYNONYM_MAP = mapOf(
            // German-English document types
            "rechnung" to listOf("invoice", "bill", "faktura", "abrechnung"),
            "vertrag" to listOf("contract", "agreement", "vereinbarung"),
            "brief" to listOf("letter", "schreiben", "anschreiben"),
            "quittung" to listOf("receipt", "beleg", "kassenbon"),
            "mahnung" to listOf("reminder", "zahlungserinnerung", "payment reminder"),
            "angebot" to listOf("quote", "quotation", "offer", "kostenvoranschlag"),
            "bestellung" to listOf("order", "auftrag", "purchase order"),
            "lieferschein" to listOf("delivery note", "packing slip"),
            "kontoauszug" to listOf("bank statement", "account statement"),
            "steuerbescheid" to listOf("tax notice", "tax assessment"),
            "versicherung" to listOf("insurance", "policy", "police"),
            "mietvertrag" to listOf("lease", "rental agreement", "tenancy"),
            "arbeitsvertrag" to listOf("employment contract", "job contract"),
            "kündigung" to listOf("cancellation", "termination", "notice"),
            "garantie" to listOf("warranty", "guarantee"),
            "handbuch" to listOf("manual", "guide", "anleitung"),
            "rezept" to listOf("recipe", "prescription"),
            "zeugnis" to listOf("certificate", "diploma", "report card"),

            // English equivalents
            "invoice" to listOf("rechnung", "bill", "faktura"),
            "contract" to listOf("vertrag", "agreement"),
            "letter" to listOf("brief", "schreiben"),
            "receipt" to listOf("quittung", "beleg"),
            "insurance" to listOf("versicherung", "policy"),
            "bank" to listOf("kontoauszug", "statement"),
            "tax" to listOf("steuer", "steuerbescheid")
        )
    }
}
