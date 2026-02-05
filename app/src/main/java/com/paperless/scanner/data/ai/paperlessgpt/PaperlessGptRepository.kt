package com.paperless.scanner.data.ai.paperlessgpt

import android.content.Context
import com.paperless.scanner.R
import com.paperless.scanner.data.ai.paperlessgpt.models.GenerateSuggestionsRequest
import com.paperless.scanner.data.ai.paperlessgpt.models.DocumentSuggestion
import com.paperless.scanner.data.ai.paperlessgpt.models.OcrJobRequest
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.api.safeApiCall
import com.paperless.scanner.data.datastore.TokenManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import javax.inject.Inject

/**
 * Repository for Paperless-GPT AI features.
 *
 * **Features:**
 * - AI-powered metadata suggestions (tags, title, correspondent)
 * - OCR quality improvement for scanned documents
 *
 * **Configuration:**
 * - Reads settings from TokenManager (enabled state, base URL)
 * - Supports standalone service or integrated plugin modes
 */
class PaperlessGptRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: PaperlessGptApi,
    private val tokenManager: TokenManager
) {

    /**
     * Check if Paperless-GPT features are enabled.
     */
    suspend fun isEnabled(): Boolean {
        return tokenManager.paperlessGptEnabled.first()
    }

    /**
     * Generate AI-powered metadata suggestions for a document.
     *
     * @param documentIds List of document IDs to analyze
     * @param generateTitle Whether to suggest a title
     * @param generateTags Whether to suggest tags
     * @param generateCorrespondent Whether to suggest correspondent
     * @param generateCustomFields Whether to suggest custom field values
     * @return List of suggestions (one per document)
     */
    suspend fun generateSuggestions(
        documentIds: List<Int>,
        generateTitle: Boolean = true,
        generateTags: Boolean = true,
        generateCorrespondent: Boolean = false,
        generateCustomFields: Boolean = false
    ): Result<List<DocumentSuggestion>> = safeApiCall {
        api.generateSuggestions(
            GenerateSuggestionsRequest(
                documentIds = documentIds,
                generateTitle = generateTitle,
                generateTags = generateTags,
                generateCorrespondent = generateCorrespondent,
                generateCustomFields = generateCustomFields
            )
        )
    }

    /**
     * Automatically trigger OCR job if document has low confidence.
     *
     * **Workflow:**
     * 1. Check if Paperless-GPT OCR Auto is enabled
     * 2. Check if OCR confidence < threshold (default: 0.8)
     * 3. Start OCR job with "auto" mode
     * 4. Poll job status every 2 seconds (max 2 minutes)
     * 5. Return success when job completes
     *
     * **Use Case:** Called automatically after document upload in UploadViewModel
     *
     * @param documentId Paperless document ID
     * @param ocrConfidence OCR confidence from Paperless-ngx Document API (0.0-1.0)
     * @param threshold Minimum confidence threshold (default: 0.8)
     * @return Result<Unit> - Success if job completed, Failure if error or timeout
     */
    suspend fun autoTriggerOcrIfNeeded(
        documentId: Int,
        ocrConfidence: Double,
        threshold: Double = 0.8
    ): Result<Unit> {
        return try {
            // Check if OCR Auto is enabled
            val ocrAutoEnabled = tokenManager.paperlessGptOcrAutoEnabled.first()
            if (!ocrAutoEnabled) {
                return Result.success(Unit) // Disabled, skip silently
            }

            // Check confidence threshold
            if (ocrConfidence >= threshold) {
                return Result.success(Unit) // Quality is good, no OCR needed
            }

            // Start OCR job
            val jobResponse = api.startOcrJob(
                documentId = documentId,
                request = OcrJobRequest(mode = "auto")
            )

            // Poll job status (every 2 seconds, max 2 minutes = 60 iterations)
            val maxPolls = 60
            repeat(maxPolls) { iteration ->
                delay(2000) // 2 seconds

                val status = api.getOcrJobStatus(jobResponse.jobId)

                when (status.status) {
                    "completed" -> return Result.success(Unit) // Success!
                    "failed" -> {
                        val errorMsg = status.error ?: context.getString(R.string.ai_ocr_failed)
                        return Result.failure(
                            PaperlessException.ParseError(errorMsg)
                        )
                    }
                    "pending", "processing" -> {
                        // Continue polling
                    }
                    else -> {
                        // Unknown status, continue polling but log warning
                    }
                }

                // Last iteration reached
                if (iteration == maxPolls - 1) {
                    return Result.failure(
                        PaperlessException.ParseError(context.getString(R.string.error_ocr_timeout))
                    )
                }
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    /**
     * Manually trigger OCR job for a document.
     *
     * **Use Case:** User manually requests OCR improvement via UI
     *
     * @param documentId Paperless document ID
     * @param mode OCR mode ("auto", "image", "pdf")
     * @return Job ID for status polling
     */
    suspend fun startOcrJob(
        documentId: Int,
        mode: String = "auto"
    ): Result<String> = safeApiCall {
        val response = api.startOcrJob(
            documentId = documentId,
            request = OcrJobRequest(mode = mode)
        )
        response.jobId
    }

    /**
     * Poll OCR job status.
     *
     * **Use Case:** Check if manually triggered OCR job completed
     *
     * @param jobId Job ID from startOcrJob
     * @return Job status ("pending", "processing", "completed", "failed")
     */
    suspend fun getOcrJobStatus(jobId: String): Result<String> = safeApiCall {
        val status = api.getOcrJobStatus(jobId)
        status.status
    }
}
