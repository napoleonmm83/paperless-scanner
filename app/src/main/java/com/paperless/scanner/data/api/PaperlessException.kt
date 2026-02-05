package com.paperless.scanner.data.api

import android.content.Context
import androidx.annotation.StringRes
import com.paperless.scanner.R
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

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
     * Server is unreachable (DNS failure, connection refused, timeout, etc.)
     * Distinguishes server-offline from general network errors.
     */
    data class ServerUnreachable(
        val reason: ServerOfflineReason
    ) : PaperlessException(reason.name) {
        @StringRes
        override val messageResId: Int = reason.messageResId
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
        override val messageFormatArgs: Array<Any> = emptyArray()
    ) : PaperlessException("Content error") {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as ContentError
            if (messageResId != other.messageResId) return false
            if (!messageFormatArgs.contentEquals(other.messageFormatArgs)) return false
            return true
        }

        override fun hashCode(): Int {
            var result = messageResId
            result = 31 * result + messageFormatArgs.contentHashCode()
            return result
        }
    }

    /**
     * Unknown/unexpected errors
     */
    data class UnknownError(
        val originalException: Throwable
    ) : PaperlessException(
        originalException.message ?: "Unknown error",
        originalException
    ) {
        @StringRes
        override val messageResId: Int = R.string.error_unknown
    }

    companion object {
        /**
         * Creates appropriate PaperlessException from HTTP status code.
         */
        fun fromHttpCode(code: Int, serverMessage: String? = null): PaperlessException {
            return when (code) {
                401, 403 -> AuthError(code)
                429 -> RateLimitError()
                in 400..499 -> ClientError(code, serverMessage)
                in 500..599 -> ServerError(code, serverMessage)
                else -> UnknownError(Exception("HTTP $code: $serverMessage"))
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
