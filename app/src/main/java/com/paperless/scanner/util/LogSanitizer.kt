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

    // An Authorization header, in the two forms this app and its libraries emit.
    // Not JSON, so [SENSITIVE_JSON_FIELD] never sees it: OkHttp's logging interceptor
    // and hand-written log lines both write `Token abc123` or `Bearer abc123` as plain
    // text, and a diagnostic report a user mails us would carry it verbatim.
    private val AUTH_SCHEME_VALUE = Regex(
        "\\b(Token|Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]{8,}",
        RegexOption.IGNORE_CASE,
    )

    // The authority of a URL — host, optional userinfo, optional port — while the path
    // survives. The path is the diagnostically valuable half: /api/documents/42/download/
    // says what was attempted, the host says where the user's private server lives.
    private val URL_AUTHORITY = Regex("(https?://)([^/\\s]+)", RegexOption.IGNORE_CASE)

    // A bare IPv4 address, with or without a port. [URL_AUTHORITY] only fires behind a
    // scheme, and a great many log lines carry a host WITHOUT one — OkHttp's
    // "Failed to connect to host/192.168.178.20:443", this app's own cleartext-allowlist
    // line, a resolver error. Those survived the sanitizer untouched and reached the
    // report a user mails us, which contradicts what the report's own explanation text
    // promises. Four octets are specific enough not to swallow a version number, which
    // has three parts.
    private val IPV4_ADDRESS = Regex("\\b\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d{1,5})?\\b")

    /**
     * Replaces every occurrence of the user's OWN server host with `<server>`.
     *
     * The regexes above redact by SHAPE, and a hostname has no shape that separates it
     * from a Java package name — `paperless.example.com` and
     * `java.net.UnknownHostException` look alike to a pattern, so a shape rule either
     * misses the host or eats the stack trace. This one redacts by VALUE instead: the
     * host is known from the stored server URL, and a known value can be removed without
     * guessing.
     *
     * Applied over the FINISHED report rather than per line, because the host turns up in
     * places no line sanitizer sees — an exception message carried in the structured
     * part, a protocol-detection result, a response header.
     *
     * @param serverUrl the configured server URL in any form it is stored in (with or
     *   without scheme, port or path). Null, blank or too short: nothing is redacted and
     *   [text] comes back unchanged.
     */
    fun redactKnownHost(text: String, serverUrl: String?): String {
        val host = hostOf(serverUrl) ?: return text
        return text.replace(Regex(Regex.escape(host), RegexOption.IGNORE_CASE), "<server>")
    }

    private fun hostOf(serverUrl: String?): String? = serverUrl
        ?.trim()
        ?.substringAfter("://")
        ?.substringBefore('/')
        ?.substringAfterLast('@')
        ?.substringBeforeLast(':')
        // A single short label would match half the report; "localhost" and any real
        // hostname clear this, a stray fragment does not.
        ?.takeIf { it.length >= 4 }

    /**
     * Sanitizes ONE log line for the diagnostic report a user can send us.
     *
     * Deliberately separate from [sanitizeErrorBody]: that one knows JSON, this one has
     * to cope with arbitrary text — a stack trace, an OkHttp line, a message someone
     * wrote by hand. Applied when a line ENTERS the buffer rather than when the report
     * leaves, so a value that was never stored cannot be handed out by some later path.
     *
     * What survives on purpose: URL paths, HTTP status codes, exception class names,
     * timings. What does not: credentials in any `Token`/`Bearer`/`Basic` header, the
     * sensitive JSON fields the regex above already knows, and the host the user's
     * server runs on.
     */
    fun sanitizeLogLine(raw: String, limit: Int = LOG_LINE_LIMIT): String =
        raw
            .replace(AUTH_SCHEME_VALUE) { match -> "${match.groupValues[1]} [REDACTED]" }
            .replace(SENSITIVE_JSON_FIELD) { match -> "\"${match.groupValues[1]}\":\"[REDACTED]\"" }
            .replace(URL_AUTHORITY) { match -> "${match.groupValues[1]}<server>" }
            .replace(IPV4_ADDRESS, "<ip>")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .take(limit)

    /** Max characters kept per buffered log line. Long stack frames get truncated. */
    const val LOG_LINE_LIMIT = 400
}
