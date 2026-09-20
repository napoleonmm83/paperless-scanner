package com.paperless.scanner.domain.error

import android.content.Context
import androidx.annotation.StringRes
import com.paperless.scanner.R
import com.paperless.scanner.data.api.CleartextNotAllowlistedException
import com.paperless.scanner.data.network.CertificatePinMismatchException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

/**
 * Reasons why the Paperless server is unreachable.
 */
enum class ServerOfflineReason(@StringRes val messageResId: Int) {
    NO_INTERNET(R.string.error_no_internet),
    DNS_FAILURE(R.string.error_dns_failure),
    CONNECTION_REFUSED(R.string.error_connection_refused),
    TIMEOUT(R.string.error_timeout),
    SSL_ERROR(R.string.error_ssl_certificate_full),
    VPN_REQUIRED(R.string.error_vpn_required),
    UNKNOWN(R.string.error_server_unreachable_check)
}

/**
 * Custom exception hierarchy for Paperless-ngx API errors.
 *
 * Provides specific error types for better error handling and user-facing messages.
 * Uses @StringRes IDs for localized messages - resolve with [getLocalizedMessage] in UI layer.
 */
sealed class PaperlessException(
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause) {

    /**
     * Returns the @StringRes ID for the localized error message.
     * Override in subclasses to provide specific resource IDs.
     */
    @get:StringRes
    abstract val messageResId: Int

    /**
     * Optional format arguments for the message resource.
     * Override in subclasses that have parameterized messages.
     */
    open val messageFormatArgs: Array<Any>
        get() = emptyArray()

    /**
     * A short, low-cardinality discriminator: an exception class name, or `HTTP <code>`.
     *
     * Exists because the *technical* [message] is not a safe carrier. It can hold a URL,
     * a host name or a server payload, and this value is both rendered on screen (for
     * [UnknownError]) and shipped as an analytics dimension, where high cardinality
     * would make the event useless and a leaked host would make it a privacy problem.
     *
     * The default — the subclass name — is right for every type whose message already
     * names its own cause. Two override it: [UnknownError], which has nothing else to
     * say, and [ContentError], whose own [message] is the fixed string "Content error"
     * and would otherwise drop the HTTP status on the floor. That last case is the whole
     * reason this property exists rather than a cast at the call site: reporting
     * "ContentError" without the code reproduces, one level up, exactly the defect this
     * change was written to remove.
     */
    open val diagnosticTag: String
        get() = this::class.java.simpleName.ifEmpty { "Unknown" }

    /**
     * Server is unreachable (DNS failure, connection refused, timeout, etc.)
     * Distinguishes server-offline from general network errors.
     */
    data class ServerUnreachable(
        val reason: ServerOfflineReason,
        // Optional so every existing single-argument call site is untouched. It exists
        // because the TLS branch in from() used to drop the SSLHandshakeException on the
        // spot: recordException then filed a report whose message was the literal string
        // "SSL_ERROR", whose cause was null, and whose stack trace belonged to the mapper
        // rather than to the handshake. A crash report that cannot name the handshake is
        // no more useful than the "Unknown error" this change exists to remove.
        val originalException: Throwable? = null
    ) : PaperlessException(reason.name, originalException) {
        @StringRes
        override val messageResId: Int = reason.messageResId

        // Without this override all five reasons — DNS failure, refused connection,
        // timeout, TLS trust, no internet — report the single value "ServerUnreachable",
        // and these are the MOST common download failures there are. Counting a set
        // without splitting it is the exact defect this property was introduced to fix;
        // reproducing it on the busiest type would have been the worst place to do so.
        override val diagnosticTag: String = reason.name
    }

    /**
     * Network-related errors (no connection, DNS failure, timeout, etc.)
     */
    data class NetworkError(
        val originalException: IOException
    ) : PaperlessException(
        originalException.message ?: "Network error",
        originalException
    ) {
        @StringRes
        override val messageResId: Int = R.string.error_network_check_internet
    }

    /**
     * Authentication errors (401, 403, invalid token)
     * @param customMessage Optional custom error message (overrides default based on code)
     */
    data class AuthError(
        val code: Int,
        val customMessage: String? = null
    ) : PaperlessException(customMessage ?: "Auth error: $code") {
        @StringRes
        override val messageResId: Int = when (code) {
            401 -> R.string.error_session_expired
            403 -> R.string.error_access_denied
            else -> R.string.error_authentication
        }
    }

    /**
     * Issue #27: the request was blocked by an edge proxy / WAF (e.g. Cloudflare)
     * BEFORE it reached Paperless-ngx. Crucially this is NOT a credential or
     * permission error — the user's login or token is valid — so it must never
     * be counted as a failed auth attempt by the login rate limiter, or a user
     * sitting behind their own proxy gets locked out despite typing the correct
     * password. It is intentionally a distinct type (not an [AuthError]) so the
     * rate-limiter gate can exclude it by type rather than by fragile message
     * matching.
     *
     * Per the project's localization rule the repository returns this typed
     * error with only [code]; the user-facing text is resolved in the UI layer
     * via [messageResId] / [getLocalizedMessage]. The fixed [message] string is
     * for logs only and deliberately carries no auth keywords.
     *
     * @param code the HTTP status the proxy returned (typically 403)
     */
    data class ProxyBlocked(
        val code: Int
    ) : PaperlessException("Edge proxy/WAF blocked the request") {
        @StringRes
        override val messageResId: Int = R.string.error_blocked_by_proxy
    }

    /**
     * Server errors (5xx status codes)
     */
    data class ServerError(
        val code: Int,
        val serverMessage: String? = null
    ) : PaperlessException("Server error: $code") {
        @StringRes
        override val messageResId: Int = when (code) {
            500 -> R.string.error_internal_server
            502 -> R.string.error_bad_gateway
            503 -> R.string.error_service_unavailable
            504 -> R.string.error_gateway_timeout
            else -> R.string.error_server_code
        }

        override val messageFormatArgs: Array<Any>
            get() = if (code !in listOf(500, 502, 503, 504)) arrayOf(code) else emptyArray()
    }

    /**
     * Client errors (4xx status codes, excluding auth errors)
     */
    data class ClientError(
        val code: Int,
        val serverMessage: String? = null
    ) : PaperlessException(serverMessage ?: "Client error: $code") {
        @StringRes
        override val messageResId: Int = when (code) {
            400 -> R.string.error_invalid_request
            404 -> R.string.error_resource_not_found
            409 -> R.string.error_conflict
            413 -> R.string.error_file_too_large
            422 -> R.string.error_validation
            else -> R.string.error_request_code
        }

        override val messageFormatArgs: Array<Any>
            get() = if (code !in listOf(400, 404, 409, 413, 422)) arrayOf(code) else emptyArray()
    }

    /**
     * Rate limiting error (429)
     */
    data class RateLimitError(
        val retryAfterSeconds: Int? = null
    ) : PaperlessException("Rate limit exceeded") {
        @StringRes
        override val messageResId: Int = if (retryAfterSeconds != null) {
            R.string.error_rate_limit_seconds
        } else {
            R.string.error_rate_limit
        }

        override val messageFormatArgs: Array<Any>
            get() = retryAfterSeconds?.let { arrayOf(it) } ?: emptyArray()
    }

    /**
     * Validation/parsing errors (malformed response, unexpected format)
     */
    data class ParseError(
        val details: String? = null
    ) : PaperlessException(details ?: "Parse error") {
        @StringRes
        override val messageResId: Int = R.string.error_unexpected_response
    }

    /**
     * File/content related errors
     */
    data class ContentError(
        @StringRes override val messageResId: Int,
        override val messageFormatArgs: Array<Any> = emptyArray(),
        // Defaults to the class name, so every existing two-argument call site keeps
        // its old behaviour. The HTTP mapper passes the status, because this class's
        // own `message` is the constant "Content error" below and would lose it.
        override val diagnosticTag: String = "ContentError"
    ) : PaperlessException("Content error") {
        // Hand-written because messageFormatArgs is an Array: the generated equals would
        // compare it by identity. diagnosticTag is included deliberately — leaving it
        // out would make two errors that a report distinguishes compare as equal, which
        // is the kind of quiet inconsistency that outlives whoever introduced it.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as ContentError
            if (messageResId != other.messageResId) return false
            if (!messageFormatArgs.contentEquals(other.messageFormatArgs)) return false
            if (diagnosticTag != other.diagnosticTag) return false
            return true
        }

        override fun hashCode(): Int {
            var result = messageResId
            result = 31 * result + messageFormatArgs.contentHashCode()
            result = 31 * result + diagnosticTag.hashCode()
            return result
        }
    }

    /**
     * Unknown/unexpected errors — the mapper's last resort, reached from exactly two
     * places: [fromHttpCode] for a status outside 400..599, and [from] for a throwable
     * that is neither [IOException] nor [retrofit2.HttpException].
     *
     * INTENTIONAL-UNTESTED: this file had no test of its own before this change; the
     * pin arrives with it as `domain/error/PaperlessExceptionTest`, which asserts the
     * discriminator below rather than the old bare string.
     *
     * [diagnosticTag] is the whole point of this class carrying two fields instead of
     * one. PR #403 replaced the raw `error.message` with the localised resource, which
     * fixed a real defect (users saw the enum name `DNS_FAILURE`) but left this branch
     * reading "Unknown error" — the exact text the release notes claimed to have
     * removed. Localising a message that identifies nothing identifies nothing in
     * another language. A user report of it is therefore unactionable, which is why
     * the underlying download failure behind the 2026-07-27 review was still unknown
     * seven weeks later.
     *
     * The tag is a short, low-cardinality discriminator — an exception class name, or
     * `HTTP <code>`. It is deliberately NOT the throwable's message: that can carry a
     * URL, a host name or a server payload, and this value is rendered on screen and
     * shipped as an analytics dimension. The default derives it from the class, so
     * every existing single-argument call site keeps compiling and keeps its meaning.
     */
    data class UnknownError(
        val originalException: Throwable,
        override val diagnosticTag: String =
            originalException::class.java.simpleName.ifEmpty { "Unknown" }
    ) : PaperlessException(
        originalException.message ?: "Unknown error",
        originalException
    ) {
        @StringRes
        override val messageResId: Int = R.string.error_unknown_with_detail

        override val messageFormatArgs: Array<Any> = arrayOf(diagnosticTag)
    }

    /**
     * Cleartext HTTP was attempted against a host the user has not yet
     * accepted via the in-app warning. Surfaced by HttpAllowlistInterceptor
     * and translated from CleartextNotAllowlistedException in the network
     * layer. UI uses [host] to populate the accept-dialog. Issue #233.
     */
    data class CleartextBlocked(
        val host: String
    ) : PaperlessException("Cleartext HTTP not allowlisted for host: $host") {
        @StringRes
        override val messageResId: Int = R.string.error_cleartext_blocked_explain
    }

    /**
     * The certificate presented by [host] no longer matches the pin captured on
     * first contact (Issue #36). Translated from [CertificatePinMismatchException]
     * in the network layer. UI surfaces a blocking re-trust dialog showing
     * [expectedPin] (the trusted pin) vs [actualPin] (the newly presented one).
     */
    data class CertificatePinMismatch(
        val host: String,
        val expectedPin: String,
        val actualPin: String,
    ) : PaperlessException("Certificate pin mismatch for host: $host") {
        @StringRes
        override val messageResId: Int = R.string.error_certificate_changed_explain
    }

    companion object {
        /**
         * Creates appropriate PaperlessException from HTTP status code.
         */
        fun fromHttpCode(code: Int, serverMessage: String? = null): PaperlessException {
            return when (code) {
                401, 403 -> AuthError(code)
                429 -> RateLimitError()

                // 406 must be tested BEFORE the 400..499 range, or it disappears into
                // ClientError's generic "Request error (406)".
                //
                // It is content negotiation, and deliberately NOT labelled an API-version
                // mismatch: ApiVersionInterceptor lists the download, preview and thumb
                // paths in UNPINNED_PATHS, so no version header is sent on them at all,
                // and on the endpoints where one IS sent the interceptor already replays
                // the call unversioned. A 406 that still arrives here therefore cannot be
                // a version problem — the interceptor's own comment calls it "something
                // else (WAF, proxy, captive portal)", and the message says that instead.
                406 -> ContentError(
                    R.string.error_not_acceptable,
                    arrayOf(code),
                    diagnosticTag = "HTTP $code"
                )

                in 400..499 -> ClientError(code, serverMessage)
                in 500..599 -> ServerError(code, serverMessage)

                // A 3xx here means the client did NOT follow the redirect. OkHttp follows
                // 300/301/302/303/307/308 itself and nothing in this app turns that off,
                // so an ordinary redirect — an auth portal, say — never lands in this
                // branch; the app sees the *target's* response instead, which is what the
                // content check in PdfViewerViewModel is for.
                //
                // Honest about what is left: 305, 306, a 3xx with no Location header, or
                // a redirect chain OkHttp gave up on. All of them are rare. An earlier
                // version of this comment named 304 from our own OkHttp cache as the
                // likely case; that was wrong twice over — the cache answers a 304
                // internally and hands the caller the stored 200, and the app sends no
                // conditional request of its own, so a raw 304 cannot surface here.
                //
                // The branch stays because "rare" is not "impossible" and the alternative
                // is the unidentifiable fallback below. It is cheap insurance, not a
                // diagnosis of anything we have observed.
                in 300..399 -> ContentError(
                    R.string.error_unexpected_redirect,
                    arrayOf(code),
                    diagnosticTag = "HTTP $code"
                )

                // Anything left is a 1xx or a nonsense code. The tag carries the number
                // so a screenshot still identifies it; serverMessage stays out of it
                // because it can hold server payload.
                else -> UnknownError(
                    Exception("HTTP $code: $serverMessage"),
                    diagnosticTag = "HTTP $code"
                )
            }
        }

        /**
         * Creates appropriate PaperlessException from any throwable.
         *
         * Distinguishes between server-offline (UnknownHostException, ConnectException, etc.)
         * and general network errors.
         */
        fun from(throwable: Throwable): PaperlessException {
            return when (throwable) {
                is PaperlessException -> throwable

                // Server-specific offline errors
                is UnknownHostException ->
                    ServerUnreachable(ServerOfflineReason.DNS_FAILURE)

                is ConnectException ->
                    ServerUnreachable(ServerOfflineReason.CONNECTION_REFUSED)

                is SocketTimeoutException ->
                    ServerUnreachable(ServerOfflineReason.TIMEOUT)

                // Cleartext block — must come BEFORE the IOException branch
                // because CleartextNotAllowlistedException extends IOException.
                // Issue #233.
                is CleartextNotAllowlistedException ->
                    CleartextBlocked(throwable.host)

                // Certificate pin mismatch — must also come BEFORE the IOException
                // branch because CertificatePinMismatchException extends IOException.
                // Issue #36.
                is CertificatePinMismatchException ->
                    CertificatePinMismatch(throwable.host, throwable.expectedPin, throwable.actualPin)

                // TLS *trust* failures — before the IOException branch, because both of
                // these extend SSLException, which extends IOException.
                //
                // ServerOfflineReason.SSL_ERROR has existed since this enum was written
                // and was reachable only through ServerHealthMonitor and ProtocolDetector;
                // the main API path could never produce it, so an expired or untrusted
                // certificate told the user to "check your internet connection". That
                // sends someone to their router over a problem on their server.
                //
                // These two subclasses ONLY, deliberately — matching on the SSLException
                // family would be a regression, not a fix. Conscrypt raises a plain
                // SSLException for ordinary I/O on an ALREADY ESTABLISHED connection
                // ("Read error: … Connection reset by peer", "Write error: … Broken
                // pipe"), which is the usual outcome of switching networks mid-download.
                // Those are network errors and "check your internet connection" is the
                // right thing to say about them; routing them here would tell the user
                // to inspect a certificate that is perfectly fine. The blast radius is
                // not the PDF viewer either — from() is shared by every repository, and
                // the download's copy loop runs outside withRetry, so it reaches the
                // screen directly.
                is SSLHandshakeException, is SSLPeerUnverifiedException ->
                    ServerUnreachable(ServerOfflineReason.SSL_ERROR, throwable)

                // General network errors (keep existing behavior)
                is IOException -> NetworkError(throwable)

                // HTTP errors mean server IS reachable (even if 4xx/5xx)
                is retrofit2.HttpException -> {
                    val code = throwable.code()
                    val errorBody = throwable.response()?.errorBody()?.string()
                    fromHttpCode(code, errorBody)
                }

                else -> UnknownError(throwable)
            }
        }
    }
}

/**
 * Gets the localized user-friendly error message for display in UI.
 * This is the preferred way to get error messages in the UI layer.
 *
 * @param context Android context for string resource resolution
 * @return Localized error message
 */
fun PaperlessException.getLocalizedMessage(context: Context): String {
    return if (messageFormatArgs.isNotEmpty()) {
        context.getString(messageResId, *messageFormatArgs)
    } else {
        context.getString(messageResId)
    }
}

/**
 * User-friendly error message for display in UI.
 * @deprecated Use [getLocalizedMessage] instead for proper localization.
 * This property returns the raw exception message which may not be localized.
 */
@Deprecated(
    message = "Use getLocalizedMessage(context) for proper localization",
    replaceWith = ReplaceWith("getLocalizedMessage(context)")
)
val PaperlessException.userMessage: String
    get() = message

/**
 * Whether the error is recoverable by retrying.
 */
val PaperlessException.isRetryable: Boolean
    get() = when (this) {
        is PaperlessException.ServerUnreachable -> true // Server might come back online
        is PaperlessException.NetworkError -> true
        is PaperlessException.ServerError -> code in listOf(500, 502, 503, 504)
        is PaperlessException.RateLimitError -> true
        else -> false
    }

/**
 * Whether the error requires re-authentication.
 */
val PaperlessException.requiresReauth: Boolean
    get() = this is PaperlessException.AuthError && code == 401
