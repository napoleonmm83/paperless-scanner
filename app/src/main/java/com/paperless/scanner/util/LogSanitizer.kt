package com.paperless.scanner.util

/**
 * Shared sanitizer for server error-response bodies before they reach logs or
 * crash breadcrumbs.
 *
 * Paperless error payloads can echo user-submitted data (filenames, document
 * metadata, custom field values) and backend internals, so we never emit them
 * verbatim. This helper:
 *  - redacts sensitive JSON string fields case-insensitively,
 *  - escapes newlines so one error stays a single grep-able log line,
 *  - caps the length.
 *
 * The full, unredacted body may still be carried for typed error handling
 * (e.g. inside [com.paperless.scanner.domain.error.PaperlessException]); this
 * helper is specifically for the logging path, where bodies linger in logcat
 * captures that get shared during support/debugging.
 */
object LogSanitizer {

    /** Max number of characters of a sanitized error body to emit to logs. */
    const val ERROR_BODY_LOG_LIMIT = 200

    // Matches JSON "field":"value" pairs whose value must never reach a log.
    // IGNORE_CASE so "Token"/"EMAIL" etc. are caught; the captured field name
    // is preserved verbatim in the replacement.
    //
    // The value matcher `(?:\\.|[^"\\])*` is the standard JSON-string-content
    // pattern: it consumes escape sequences (\" \\ \n …) as a unit so an
    // embedded escaped quote like {"token":"ab\"cd"} does NOT terminate the
    // match early and leak the suffix into the log.
    private val SENSITIVE_JSON_FIELD = Regex(
        "\"(token|password|passwd|secret|api[_-]?key|key|authorization|username|user|email)\"\\s*:\\s*\"(?:\\\\.|[^\"\\\\])*\"",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Redacts sensitive fields, escapes newlines and caps length.
     *
     * @param raw the raw error body (already read from the [okhttp3.ResponseBody]);
     *   callers must read the body exactly once since `.string()` consumes the stream.
     * @param limit max characters to keep (applied AFTER redaction so a secret is
     *   never left half-visible by truncation).
     * @return null if [raw] is null; otherwise a copy safe to log.
     */
    fun sanitizeErrorBody(raw: String?, limit: Int = ERROR_BODY_LOG_LIMIT): String? {
        if (raw == null) return null
        return raw
            .replace(SENSITIVE_JSON_FIELD) { match -> "\"${match.groupValues[1]}\":\"[REDACTED]\"" }
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .take(limit)
    }
}
