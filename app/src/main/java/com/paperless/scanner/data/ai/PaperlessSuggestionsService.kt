package com.paperless.scanner.data.ai

import com.paperless.scanner.data.ai.models.DocumentAnalysis
import com.paperless.scanner.data.ai.models.TagSuggestion
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.repository.CorrespondentRepository
import com.paperless.scanner.data.repository.DocumentTypeRepository
import com.paperless.scanner.data.repository.TagRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for fetching document suggestions from Paperless-ngx server.
 *
 * Uses the built-in ML-based suggestions API which is:
 * - FREE (no API costs)
 * - Requires the document to be already uploaded
 * - Based on Paperless' internal neural network
 */
@Singleton
class PaperlessSuggestionsService @Inject constructor(
    private val api: PaperlessApi,
    private val tagRepository: TagRepository,
    private val correspondentRepository: CorrespondentRepository,
    private val documentTypeRepository: DocumentTypeRepository
) {

    /**
     * Get suggestions for an uploaded document.
     *
     * @param documentId The ID of the document in Paperless
     * @return DocumentAnalysis with suggestions, or error if failed
     */
    suspend fun getSuggestions(documentId: Int): Result<DocumentAnalysis> = withContext(Dispatchers.IO) {
        runCatching {
            val response = api.getDocumentSuggestions(documentId)

            // Get current metadata to resolve IDs to names
            val allTags = tagRepository.observeTags().first()
            val allCorrespondents = correspondentRepository.observeCorrespondents().first()
            val allDocumentTypes = documentTypeRepository.observeDocumentTypes().first()

            // Convert tag IDs to TagSuggestions
            val tagSuggestions = response.tags.mapNotNull { tagId ->
                allTags.find { it.id == tagId }?.let { tag ->
                    TagSuggestion(
                        tagId = tag.id,
                        tagName = tag.name,
                        confidence = PAPERLESS_SUGGESTION_CONFIDENCE,
                        reason = TagSuggestion.REASON_PAPERLESS_API
                    )
                }
            }

            // Get first suggested correspondent name
            val suggestedCorrespondent = response.correspondents.firstOrNull()?.let { corrId ->
                allCorrespondents.find { it.id == corrId }?.name
            }

            // Get first suggested document type name
            val suggestedDocumentType = response.documentTypes.firstOrNull()?.let { typeId ->
                allDocumentTypes.find { it.id == typeId }?.name
            }

            // Get first suggested date
            val suggestedDate = response.dates.firstOrNull()

            DocumentAnalysis(
                suggestedTitle = null, // Paperless API doesn't suggest titles
                suggestedTags = tagSuggestions,
                suggestedCorrespondent = suggestedCorrespondent,
                suggestedDocumentType = suggestedDocumentType,
                suggestedDate = suggestedDate,
                extractedText = null,
                confidence = if (tagSuggestions.isNotEmpty()) PAPERLESS_SUGGESTION_CONFIDENCE else 0f
            )
        }
    }

    /**
     * Check if suggestions are available for a document.
     * Some documents may not have any suggestions if Paperless hasn't analyzed them yet.
     */
    suspend fun hasSuggestions(documentId: Int): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val response = api.getDocumentSuggestions(documentId)
            response.tags.isNotEmpty() ||
                response.correspondents.isNotEmpty() ||
                response.documentTypes.isNotEmpty()
        }.getOrDefault(false)
    }

    companion object {
        /**
         * Default confidence for Paperless suggestions.
         * Lower than AI suggestions since they're based on simpler matching.
         */
        private const val PAPERLESS_SUGGESTION_CONFIDENCE = 0.65f
    }
}
