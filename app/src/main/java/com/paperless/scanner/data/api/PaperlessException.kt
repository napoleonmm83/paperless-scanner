package com.paperless.scanner.data.api

import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Reasons why the Paperless server is unreachable.
 */
enum class ServerOfflineReason {
    NO_INTERNET,        // No internet connection available
    DNS_FAILURE,        // Server hostname could not be resolved
    CONNECTION_REFUSED, // Server port not reachable
    TIMEOUT,            // Request timeout
    VPN_REQUIRED,       // Server requires VPN (detected via specific patterns)
    UNKNOWN             // Unknown offline reason
}

/**
 * Custom exception hierarchy for Paperless-ngx API errors.
 *
 * Provides specific error types for better error handling and user-facing messages.
 */
sealed class PaperlessException(
    override val message: String,
    override val cause: Throwable? = null
) : Exception(message, cause) {

    /**
     * Server is unreachable (DNS failure, connection refused, timeout, etc.)
     * Distinguishes server-offline from general network errors.
     */
    data class ServerUnreachable(
        val reason: ServerOfflineReason,
        override val message: String = when (reason) {
            ServerOfflineReason.NO_INTERNET ->
                "Keine Internetverbindung - bitte Netzwerk prüfen"
            ServerOfflineReason.DNS_FAILURE ->
                "Server-Adresse konnte nicht aufgelöst werden. Bitte Server-URL in Einstellungen prüfen."
            ServerOfflineReason.CONNECTION_REFUSED ->
                "Server ist nicht erreichbar. Bitte prüfen ob der Server läuft."
            ServerOfflineReason.TIMEOUT ->
                "Server antwortet nicht. Bitte später erneut versuchen."
            ServerOfflineReason.VPN_REQUIRED ->
                "VPN-Verbindung erforderlich. Bitte VPN aktivieren und erneut versuchen."
            ServerOfflineReason.UNKNOWN ->
                "Server nicht erreichbar - bitte Verbindung prüfen"
        }
    ) : PaperlessException(message)

    /**
     * Network-related errors (no connection, DNS failure, timeout, etc.)
     */
    data class NetworkError(
        val originalException: IOException,
        override val message: String = originalException.message ?: "Netzwerkfehler: Überprüfe deine Internetverbindung"
    ) : PaperlessException(message, originalException)

    /**
     * Authentication errors (401, 403, invalid token)
     */
    data class AuthError(
        val code: Int,
        override val message: String = when (code) {
            401 -> "Sitzung abgelaufen - bitte erneut anmelden"
            403 -> "Zugriff verweigert - fehlende Berechtigung"
            else -> "Authentifizierungsfehler"
        }
    ) : PaperlessException(message)

    /**
     * Server errors (5xx status codes)
     */
    data class ServerError(
        val code: Int,
        val serverMessage: String? = null,
        override val message: String = when (code) {
            500 -> "Interner Serverfehler - bitte später erneut versuchen"
            502 -> "Server nicht erreichbar (Bad Gateway)"
            503 -> "Server vorübergehend nicht verfügbar"
            504 -> "Server-Timeout - bitte später erneut versuchen"
            else -> "Serverfehler ($code)"
        }
    ) : PaperlessException(message)

    /**
     * Client errors (4xx status codes, excluding auth errors)
     */
    data class ClientError(
        val code: Int,
        val serverMessage: String? = null,
        override val message: String = when (code) {
            400 -> serverMessage ?: "Ungültige Anfrage"
            404 -> "Ressource nicht gefunden"
            409 -> "Konflikt - Ressource wurde geändert"
            413 -> "Datei zu groß"
            422 -> serverMessage ?: "Validierungsfehler"
            else -> serverMessage ?: "Anfragefehler ($code)"
        }
    ) : PaperlessException(message)

    /**
     * Rate limiting error (429)
     */
    data class RateLimitError(
        val retryAfterSeconds: Int? = null,
        override val message: String = if (retryAfterSeconds != null) {
            "Zu viele Anfragen - bitte $retryAfterSeconds Sekunden warten"
        } else {
            "Zu viele Anfragen - bitte später erneut versuchen"
        }
    ) : PaperlessException(message)

    /**
     * Validation/parsing errors (malformed response, unexpected format)
     */
    data class ParseError(
        val details: String? = null,
        override val message: String = details ?: "Unerwartete Serverantwort"
    ) : PaperlessException(message)

    /**
     * File/content related errors
     */
    data class ContentError(
        override val message: String
    ) : PaperlessException(message)

    /**
     * Unknown/unexpected errors
     */
    data class UnknownError(
        val originalException: Throwable,
        override val message: String = originalException.message ?: "Unbekannter Fehler"
    ) : PaperlessException(message, originalException)

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
 * User-friendly error message for display in UI.
 */
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
