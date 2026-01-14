package com.paperless.scanner.data.ai.paperlessgpt.models

import com.google.gson.annotations.SerializedName

/**
 * Request body for generating AI suggestions for documents.
 *
 * Example:
 * ```
 * GenerateSuggestionsRequest(
 *     document_ids = listOf(123),
 *     generate_title = true,
 *     generate_tags = true,
 *     generate_correspondent = true
 * )
 * ```
 *
 * API: POST /api/generate-suggestions
 * Response Time: 2-10 seconds (depends on LLM provider)
 */
data class GenerateSuggestionsRequest(
    @SerializedName("document_ids")
    val documentIds: List<Int>,

    @SerializedName("generate_title")
    val generateTitle: Boolean = true,

    @SerializedName("generate_tags")
    val generateTags: Boolean = true,

    @SerializedName("generate_correspondent")
    val generateCorrespondent: Boolean = true,

    @SerializedName("generate_created_date")
    val generateCreatedDate: Boolean = false,

    @SerializedName("generate_custom_fields")
    val generateCustomFields: Boolean = false
)

/**
 * AI-generated suggestions for a single document.
 *
 * Example:
 * ```
 * DocumentSuggestion(
 *     id = 123,
 *     title = "Telekom Mobilfunk Rechnung Januar 2024",
 *     tags = listOf("rechnung", "telekom", "mobilfunk"),
 *     correspondent = "Deutsche Telekom AG",
 *     customFields = listOf(
 *         CustomFieldSuggestion(field = 1, value = "€ 49,99")
 *     )
 * )
 * ```
 *
 * Note: All fields except `id` are nullable as AI may not always detect all metadata.
 */
data class DocumentSuggestion(
    @SerializedName("id")
    val id: Int,

    @SerializedName("title")
    val title: String? = null,

    @SerializedName("tags")
    val tags: List<String>? = null,

    @SerializedName("correspondent")
    val correspondent: String? = null,

    @SerializedName("document_type")
    val documentType: String? = null,

    @SerializedName("created_date")
    val createdDate: String? = null,

    @SerializedName("custom_fields")
    val customFields: List<CustomFieldSuggestion>? = null
)

/**
 * Custom field value suggestion extracted by AI.
 *
 * Example:
 * ```
 * CustomFieldSuggestion(
 *     field = 1,  // Custom field ID from Paperless
 *     value = "€ 49,99"  // Extracted value (e.g., invoice amount)
 * )
 * ```
 */
data class CustomFieldSuggestion(
    @SerializedName("field")
    val field: Int,

    @SerializedName("value")
    val value: String
)

/**
 * Request to start an OCR job for a document with poor OCR quality.
 *
 * Example:
 * ```
 * OcrJobRequest(mode = "vision")  // Use LLM-Vision-OCR instead of Tesseract
 * ```
 *
 * API: POST /api/documents/{id}/ocr
 * Use Case: When MLKit detects low confidence (<0.8) → trigger LLM-based OCR
 */
data class OcrJobRequest(
    @SerializedName("mode")
    val mode: String = "vision"  // "vision" for LLM-Vision-OCR, "tesseract" for standard OCR
)

/**
 * Response when starting an OCR job.
 *
 * Example:
 * ```
 * OcrJobResponse(jobId = "ocr-job-uuid-1234")
 * ```
 *
 * Next step: Poll GET /api/jobs/ocr/{jobId} until status is "completed"
 */
data class OcrJobResponse(
    @SerializedName("job_id")
    val jobId: String
)

/**
 * Status of an OCR job (used for polling).
 *
 * Example:
 * ```
 * OcrJobStatus(
 *     id = "ocr-job-uuid-1234",
 *     documentId = 123,
 *     status = "processing",
 *     progress = OcrProgress(pagesDone = 2, totalPages = 5),
 *     error = null
 * )
 * ```
 *
 * API: GET /api/jobs/ocr/{job_id}
 * Status values: "pending", "processing", "completed", "failed"
 */
data class OcrJobStatus(
    @SerializedName("id")
    val id: String,

    @SerializedName("document_id")
    val documentId: Int,

    @SerializedName("status")
    val status: String,

    @SerializedName("progress")
    val progress: OcrProgress? = null,

    @SerializedName("error")
    val error: String? = null
) {
    companion object {
        const val STATUS_PENDING = "pending"
        const val STATUS_PROCESSING = "processing"
        const val STATUS_COMPLETED = "completed"
        const val STATUS_FAILED = "failed"
    }

    /**
     * Returns true if the OCR job is still in progress.
     */
    val isPending: Boolean
        get() = status == STATUS_PENDING || status == STATUS_PROCESSING

    /**
     * Returns true if the OCR job has finished (success or failure).
     */
    val isCompleted: Boolean
        get() = status == STATUS_COMPLETED || status == STATUS_FAILED

    /**
     * Returns true if the OCR job completed successfully.
     */
    val isSuccess: Boolean
        get() = status == STATUS_COMPLETED
}

/**
 * Progress information for a running OCR job.
 *
 * Example:
 * ```
 * OcrProgress(pagesDone = 3, totalPages = 5)  // 60% complete
 * ```
 */
data class OcrProgress(
    @SerializedName("pages_done")
    val pagesDone: Int,

    @SerializedName("total_pages")
    val totalPages: Int
) {
    /**
     * Progress percentage (0.0 to 1.0).
     */
    val progressPercentage: Float
        get() = if (totalPages > 0) pagesDone.toFloat() / totalPages else 0f
}

/**
 * Custom field definition from Paperless-GPT.
 *
 * Example:
 * ```
 * CustomField(
 *     id = 1,
 *     name = "Invoice Amount",
 *     dataType = "monetary"
 * )
 * ```
 *
 * API: GET /api/custom_fields
 */
data class CustomField(
    @SerializedName("id")
    val id: Int,

    @SerializedName("name")
    val name: String,

    @SerializedName("data_type")
    val dataType: String? = null
)
