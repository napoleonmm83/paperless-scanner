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
    //
    // Two rules, because one cannot separate a credential from a sentence.
    //
    // Behind an `Authorization:` header the scheme word is never prose, so ANY value goes
    // — that is where OkHttp's logging interceptor puts the real thing.
    private val AUTH_HEADER_VALUE = Regex(
        "\\b(Authorization\\s*:\\s*)(Token|Bearer|Basic)\\s+\\S{6,}",
        RegexOption.IGNORE_CASE,
    )

    // Standing bare in a sentence, the same word usually IS prose: an earlier version
    // redacted any long word after it, so "Token validation network error" became
    // "Token [REDACTED] network error" and "Basic authentication required" became
    // "Basic [REDACTED] required" — shredding exactly the auth diagnosis this report
    // exists to carry.
    //
    // The version after THAT required a digit, and a reviewer caught the hole it opened:
    // a letter-only `Bearer abcdefghijkl` walked straight through. So the branches are
    // structural instead. Either the value carries something no English word does — a
    // digit or one of `._~+/=` — at 8 characters, or it is letter-only and then needs 16,
    // which no word that follows these three in practice reaches ("authentication" is 14,
    // "validation" 10) and every real credential exceeds (a Paperless token is 40 hex).
    private val AUTH_SCHEME_VALUE = Regex(
        "\\b(Token|Bearer|Basic)\\s+(?:(?=[A-Za-z0-9._~+/=-]*[0-9._~+/=])[A-Za-z0-9._~+/=-]{8,}|[A-Za-z]{16,})",
        RegexOption.IGNORE_CASE,
    )

    // A cookie value behind a `Cookie:` or `Set-Cookie:` header.
    //
    // Found on a REAL report pulled off a device (2026-09-21, 171 KB, 680 logged header
    // lines): `Authorization` was redacted as designed, and one line below it sat
    // `set-cookie: csrftoken=<40 chars>` completely in the clear. The Authorization rule
    // above cannot see it — a cookie carries no scheme word — so the header needed its
    // own rule.
    //
    // Only the VALUE goes; the cookie's NAME stays, because knowing that a CSRF or
    // session cookie was set is diagnostically useful and names no secret. Everything up
    // to the first attribute separator is redacted, so `expires` and `Max-Age` survive
    // for the same reason.
    //
    // In release the interceptor logs at NONE, so today this fires only in a debug
    // build — which is exactly the kind of reasoning that ages badly: the sanitizer is
    // the layer that must not depend on who is careful upstream.
    // Anchored on the HEADER, then every pair inside it — a request header carries
    // several (`Cookie: csrftoken=…; sessionid=…`), and a rule that redacts only the
    // first one leaves the session cookie, which is the worse of the two. The first
    // version of this rule did exactly that.
    private val COOKIE_HEADER_LINE = Regex(
        "\\b((?:Set-)?Cookie\\s*:\\s*)(.+)",
        RegexOption.IGNORE_CASE,
    )
    private val COOKIE_PAIR = Regex("([A-Za-z0-9_.-]+=)([^;\\s]+)")

    // Cookie attributes are not secrets and stay readable; everything else in the
    // header is treated as a value. Kept as a set rather than a regex alternation so
    // the list reads as data.
    private val COOKIE_ATTRIBUTES = setOf(
        "expires", "max-age", "domain", "path", "samesite", "version", "secure", "httponly",
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
    // promises.
    //
    // Known and accepted false positive: a FOUR-part build number is indistinguishable
    // from an address by shape ("Google Play services 24.31.35.100" becomes "<ip>"). An
    // earlier version of this comment claimed the four octets were specific enough
    // because a version has three parts — that is true of this app's own version and not
    // of a library's. The trade is deliberate: a redacted build number costs a line of
    // context, a leaked address costs the promise.
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
    fun redactKnownHost(text: String, serverUrl: String?): String =
        redactKnownHosts(text, listOfNotNull(serverUrl))

    /**
     * The same, for every host the app knows about.
     *
     * A single URL is not enough, and the gap was not theoretical: the STORED server URL
     * is written only after a successful login, so during setup — new install, new
     * server, failed login — there is nothing to redact against, and that is exactly the
     * path this report was built for. A user also has a second host whenever
     * Paperless-GPT is configured, plus whatever sits in the cleartext and certificate
     * allowlists.
     */
    fun redactKnownHosts(text: String, serverUrls: Collection<String?>): String {
        val hosts = serverUrls.mapNotNull { hostOf(it) }.distinct()
        if (hosts.isEmpty()) return text
        // Longest first: with "paperless.example.com" and "example.com" both known,
        // replacing the short one first would leave "paperless.<server>".
        return hosts.sortedByDescending { it.length }.fold(text) { acc, host ->
            acc.replace(boundedHost(host), "<server>")
        }
    }

    // Bounded on both sides, and that is not tidiness. A single-label host is ordinary on
    // a home network — Tailscale MagicDNS, a Docker service name, plain `http://paperless:8000`
    // — and an unbounded replacement then rewrites the app's OWN package name: every stack
    // frame `com.paperless.scanner` became `com.<server>.scanner`, which destroys the
    // report while claiming to protect it. A real host is preceded by a space, a quote or
    // a slash; inside a package name it is preceded by a dot.
    private fun boundedHost(host: String) = Regex(
        "(?<![A-Za-z0-9.\\-])" + Regex.escape(host) + "(?![A-Za-z0-9\\-])",
        RegexOption.IGNORE_CASE,
    )

    private fun hostOf(serverUrl: String?): String? {
        val authority = serverUrl
            ?.trim()
            ?.substringAfter("://")
            ?.substringBefore('/')
            ?.substringAfterLast('@')
            ?: return null

        val host = if (authority.startsWith("[")) {
            // IPv6 in brackets. Cutting at the last colon — which is what the port strip
            // below does — lands INSIDE the address: `[2001:db8::1]` became `[2001:db8:`,
            // and the report then showed `<server>:1]`.
            authority.substringBefore(']', missingDelimiterValue = authority) + "]"
        } else {
            authority.substringBeforeLast(':')
        }

        // Two characters is enough BECAUSE of the boundaries above: `nas`, `pi` and `srv`
        // are ordinary LAN names, and the previous threshold of four left them standing.
        return host.takeIf { it.length >= 2 }
    }

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
        sanitizeText(raw)
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .take(limit)

    /**
     * The shape-based rules alone, without the one-line formatting or the cap.
     *
     * Split out because the STRUCTURED half of the report never ran through any of them:
     * `errorMessage` and the protocol-detection results are printed verbatim, and they are
     * filled with raw `e.message`. The same connect failure was redacted to `<ip>` in the
     * log tail and left the server's public address standing two sections above it.
     */
    fun sanitizeText(raw: String): String =
        raw
            // Header rule first: it is the unconditional one, and running it before the
            // bare rule means a short letter-only header value is caught rather than
            // falling through to the rule that would let it pass.
            .replace(AUTH_HEADER_VALUE) { match ->
                "${match.groupValues[1]}${match.groupValues[2]} [REDACTED]"
            }
            .replace(AUTH_SCHEME_VALUE) { match -> "${match.groupValues[1]} [REDACTED]" }
            // Before the URL rule: a cookie value may contain characters the authority
            // rule would rather not meet, and the name-plus-equals prefix is the most
            // specific anchor in this chain.
            .replace(COOKIE_HEADER_LINE) { header ->
                header.groupValues[1] + COOKIE_PAIR.replace(header.groupValues[2]) { pair ->
                    val name = pair.groupValues[1].dropLast(1).lowercase()
                    if (name in COOKIE_ATTRIBUTES) pair.value else "${pair.groupValues[1]}[REDACTED]"
                }
            }
            .replace(SENSITIVE_JSON_FIELD) { match -> "\"${match.groupValues[1]}\":\"[REDACTED]\"" }
            .replace(URL_AUTHORITY) { match -> "${match.groupValues[1]}<server>" }
            .replace(IPV4_ADDRESS, "<ip>")

    /** Max characters kept per buffered log line. Long stack frames get truncated. */
    const val LOG_LINE_LIMIT = 400
}
