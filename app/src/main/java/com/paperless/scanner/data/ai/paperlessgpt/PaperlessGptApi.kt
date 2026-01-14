package com.paperless.scanner.data.ai.paperlessgpt

import com.paperless.scanner.data.ai.paperlessgpt.models.DocumentSuggestion
import com.paperless.scanner.data.ai.paperlessgpt.models.GenerateSuggestionsRequest
import com.paperless.scanner.data.ai.paperlessgpt.models.OcrJobRequest
import com.paperless.scanner.data.ai.paperlessgpt.models.OcrJobResponse
import com.paperless.scanner.data.ai.paperlessgpt.models.OcrJobStatus
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path

/**
 * Retrofit interface for Paperless-GPT API.
 *
 * Paperless-GPT is an AI-powered companion service for Paperless-ngx
 * that provides document analysis and OCR enhancement capabilities.
 *
 * ## Service Modes
 * 1. **Standalone Service**: Paperless-GPT runs on separate host (configured via settings)
 * 2. **Integrated Plugin**: Paperless-GPT integrated into Paperless-ngx (uses same base URL)
 *
 * ## Authentication
 * Uses same token as Paperless-ngx (passed via AuthInterceptor)
 *
 * @see com.paperless.scanner.data.ai.paperlessgpt.models.PaperlessGptModels
 */
interface PaperlessGptApi {

    /**
     * Generate AI-powered metadata suggestions for uploaded documents.
     *
     * **Endpoint:** `POST /api/generate-suggestions`
     *
     * **Use Case:** After uploading a document to Paperless-ngx, call this to get
     * AI-suggested tags, title, correspondent, and custom fields.
     *
     * **Response Time:** 2-10 seconds (depends on LLM provider and document complexity)
     *
     * **Example:**
     * ```kotlin
     * val request = GenerateSuggestionsRequest(
     *     documentIds = listOf(123),
     *     generateTitle = true,
     *     generateTags = true
     * )
     * val suggestions = api.generateSuggestions(request)
     * ```
     *
     * @param request Configuration for what metadata to generate
     * @return List of document suggestions (one per document ID)
     */
    @POST("api/generate-suggestions")
    suspend fun generateSuggestions(
        @Body request: GenerateSuggestionsRequest
    ): List<DocumentSuggestion>

    /**
     * Start OCR quality improvement job for a document.
     *
     * **Endpoint:** `POST /api/documents/{id}/ocr`
     *
     * **Use Case:** When uploaded document has low OCR confidence (<0.8),
     * trigger this job to re-process the document with better OCR settings.
     *
     * **Modes:**
     * - `"image"` - Use image-based OCR (for scanned documents)
     * - `"pdf"` - Use PDF text extraction + OCR fallback
     * - `"auto"` - Automatically detect best method
     *
     * **Example:**
     * ```kotlin
     * val request = OcrJobRequest(mode = "image")
     * val response = api.startOcrJob(documentId = 123, request)
     * // Poll getOcrJobStatus(response.jobId) until complete
     * ```
     *
     * @param documentId Paperless document ID
     * @param request OCR job configuration
     * @return Job ID and initial status
     */
    @POST("api/documents/{id}/ocr")
    suspend fun startOcrJob(
        @Path("id") documentId: Int,
        @Body request: OcrJobRequest
    ): OcrJobResponse

    /**
     * Get status of a running OCR job.
     *
     * **Endpoint:** `GET /api/jobs/ocr/{jobId}`
     *
     * **Use Case:** Poll this endpoint every 2 seconds to check if OCR job completed.
     *
     * **Status Values:**
     * - `"pending"` - Job queued, not started yet
     * - `"processing"` - OCR in progress
     * - `"completed"` - OCR finished successfully
     * - `"failed"` - OCR failed (check `error` field)
     *
     * **Example:**
     * ```kotlin
     * while (true) {
     *     val status = api.getOcrJobStatus(jobId)
     *     when (status.status) {
     *         "completed" -> break  // Success!
     *         "failed" -> throw Exception(status.error)
     *         else -> delay(2000)   // Poll again in 2 seconds
     *     }
     * }
     * ```
     *
     * @param jobId Job ID from startOcrJob response
     * @return Current job status with progress info
     */
    @GET("api/jobs/ocr/{jobId}")
    suspend fun getOcrJobStatus(
        @Path("jobId") jobId: String
    ): OcrJobStatus
}
