package com.paperless.scanner.data.service

/**
 * Parses the response of `POST /api/documents/post_document/`.
 *
 * Paperless-ngx returns the task UUID as a PLAIN STRING (not JSON), sometimes
 * wrapped in double quotes and/or surrounded by whitespace/newlines, e.g.
 * `"abc-123"` or `abc-123\n`. This centralizes that quirk — previously duplicated
 * inline at every upload call site (issues #46/#127) — and fails fast on an empty
 * or quotes-only body so a malformed response surfaces as an upload error instead
 * of a silently-empty task id that would later break task polling (#135).
 */
/**
 * Thrown when the upload response body contains no usable task id (blank or only quotes).
 *
 * A distinct exception type (not [IllegalStateException]/[IllegalArgumentException]) so the
 * upload call sites can map it to a proper upload/parse error instead of having it swallowed
 * by their PDF-creation / image-processing catch blocks.
 */
class UploadResponseParseException(message: String) : Exception(message)

object UploadResponseParser {
    /**
     * @param rawBody the raw response body string (e.g. from `ResponseBody.string()`).
     * @return the bare task UUID, with surrounding quotes and whitespace stripped.
     * @throws UploadResponseParseException if the body is blank or contains only quotes.
     */
    fun parseTaskId(rawBody: String): String {
        val taskId = rawBody.trim().removeSurrounding("\"").trim()
        if (taskId.isBlank()) {
            throw UploadResponseParseException(
                "Upload response contained no task id (body was blank or only quotes)"
            )
        }
        return taskId
    }
}
