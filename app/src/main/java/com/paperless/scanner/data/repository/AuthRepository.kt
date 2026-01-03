package com.paperless.scanner.data.repository

import android.util.Log
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.datastore.TokenManager
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.net.ssl.SSLException
import javax.net.ssl.SSLHandshakeException

class AuthRepository @Inject constructor(
    private val tokenManager: TokenManager,
    private val client: OkHttpClient
) {
    companion object {
        private const val TAG = "AuthRepository"
        private const val DETECTION_TIMEOUT_SECONDS = 10L
    }

    // Dedicated client with shorter timeout for protocol detection
    private val detectionClient: OkHttpClient by lazy {
        client.newBuilder()
            .connectTimeout(DETECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(DETECTION_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    /**
     * Detects whether the server uses HTTPS or HTTP.
     * Tries HTTPS first (preferred), falls back to HTTP if SSL fails.
     *
     * @param host The hostname without protocol (e.g., "paperless.example.com")
     * @return The full URL with detected protocol (e.g., "https://paperless.example.com")
     */
    suspend fun detectServerProtocol(host: String): Result<String> {
        // Clean the host - remove any existing protocol and path
        val cleanHost = host
            .trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .split("/").first()  // Remove any path
            .trimEnd('/')

        if (cleanHost.isBlank()) {
            return Result.failure(PaperlessException.ContentError("Server-Adresse fehlt"))
        }

        // Validate host format
        if (cleanHost.contains(" ")) {
            return Result.failure(PaperlessException.ContentError("Ungültige Server-Adresse"))
        }

        Log.d(TAG, "Detecting protocol for: $cleanHost")

        // Try HTTPS first (preferred)
        val httpsResult = tryProtocol("https", cleanHost)
        if (httpsResult.isSuccess) {
            return httpsResult
        }

        // Try HTTP as fallback
        val httpResult = tryProtocol("http", cleanHost)
        if (httpResult.isSuccess) {
            return httpResult
        }

        // Both failed - return the most informative error
        val httpsError = httpsResult.exceptionOrNull()
        val httpError = httpResult.exceptionOrNull()

        Log.e(TAG, "Both protocols failed. HTTPS: ${httpsError?.message}, HTTP: ${httpError?.message}")

        // Return the most specific error message
        // Priority: specific errors > generic errors
        val httpsMsg = httpsError?.message ?: ""
        val httpMsg = httpError?.message ?: ""

        return when {
            // Server not found errors
            httpsMsg.contains("nicht gefunden") -> Result.failure(httpsError!!)
            httpMsg.contains("nicht gefunden") -> Result.failure(httpError!!)

            // Not a Paperless server
            httpsMsg.contains("Kein Paperless") -> Result.failure(httpsError!!)
            httpMsg.contains("Kein Paperless") -> Result.failure(httpError!!)

            // SSL specific errors (prefer these as they're actionable)
            httpsMsg.contains("SSL") || httpsMsg.contains("Zertifikat") -> Result.failure(httpsError!!)

            // Connection refused (server might be down)
            httpsMsg.contains("abgelehnt") || httpMsg.contains("abgelehnt") -> {
                Result.failure(PaperlessException.NetworkError(
                    IOException("Verbindung abgelehnt. Prüfe ob der Server läuft und erreichbar ist.")
                ))
            }

            // Timeout
            httpsMsg.contains("Zeitüberschreitung") || httpMsg.contains("Zeitüberschreitung") -> {
                Result.failure(PaperlessException.NetworkError(
                    IOException("Zeitüberschreitung. Server ist nicht erreichbar oder zu langsam.")
                ))
            }

            // Network issues
            httpsMsg.contains("Netzwerk") || httpMsg.contains("Netzwerk") -> {
                Result.failure(PaperlessException.NetworkError(
                    IOException("Keine Netzwerkverbindung. Prüfe deine Internetverbindung.")
                ))
            }

            // Fallback with more context
            else -> Result.failure(
                PaperlessException.NetworkError(
                    IOException("Server nicht erreichbar unter \"$cleanHost\". Prüfe die Adresse und Netzwerkverbindung.")
                )
            )
        }
    }

    private fun tryProtocol(protocol: String, host: String): Result<String> {
        val url = "$protocol://$host"
        Log.d(TAG, "Trying $protocol: $url")

        return try {
            // First, try to verify this is actually a Paperless server
            // by checking /api/ response for Paperless-specific endpoints
            val apiRequest = Request.Builder()
                .url("$url/api/")
                .get()
                .build()

            detectionClient.newCall(apiRequest).execute().use { response ->
                Log.d(TAG, "$protocol /api/ response: ${response.code}")

                when (response.code) {
                    in 200..299 -> {
                        // Got a response - check if it's actually Paperless
                        val body = response.body?.string() ?: ""
                        if (isPaperlessApiResponse(body)) {
                            Log.d(TAG, "$protocol - Verified as Paperless server")
                            return Result.success(url)
                        } else {
                            Log.d(TAG, "$protocol - /api/ exists but not Paperless format")
                            // Fall through to secondary check
                        }
                    }
                    401, 403 -> {
                        // Auth required at /api/ - verify with documents endpoint
                        return verifyPaperlessWithDocumentsEndpoint(url, protocol)
                    }
                    404 -> {
                        Log.d(TAG, "$protocol - /api/ not found")
                        return Result.failure(PaperlessException.NetworkError(
                            IOException("Kein Paperless-Server. Die /api/ Schnittstelle wurde nicht gefunden.")
                        ))
                    }
                    502 -> {
                        return Result.failure(PaperlessException.NetworkError(
                            IOException("Server antwortet nicht (Bad Gateway). Ist Paperless gestartet?")
                        ))
                    }
                    503 -> {
                        return Result.failure(PaperlessException.NetworkError(
                            IOException("Server vorübergehend nicht verfügbar. Bitte später erneut versuchen.")
                        ))
                    }
                    504 -> {
                        return Result.failure(PaperlessException.NetworkError(
                            IOException("Server-Timeout. Der Server braucht zu lange zum Antworten.")
                        ))
                    }
                    else -> {
                        Log.d(TAG, "$protocol - Unexpected response: ${response.code}")
                        // Fall through to secondary check
                    }
                }
            }

            // Secondary check: try /api/documents/ endpoint
            // This is more specific to Paperless and should return 401 if it's Paperless
            verifyPaperlessWithDocumentsEndpoint(url, protocol)

        } catch (e: UnknownHostException) {
            Log.e(TAG, "$protocol - Unknown host: ${e.message}")
            val suggestion = if (host.contains(".local") || !host.contains(".")) {
                "Server \"$host\" nicht gefunden. Prüfe, ob du im richtigen Netzwerk bist."
            } else {
                "Server \"$host\" nicht gefunden. Prüfe die Adresse auf Tippfehler."
            }
            Result.failure(PaperlessException.NetworkError(IOException(suggestion)))
        } catch (e: SSLHandshakeException) {
            Log.d(TAG, "$protocol - SSL handshake failed: ${e.message}")
            Result.failure(PaperlessException.NetworkError(
                IOException("SSL-Zertifikat ungültig oder abgelaufen. Prüfe die Server-Konfiguration.")
            ))
        } catch (e: SSLException) {
            Log.d(TAG, "$protocol - SSL error: ${e.message}")
            val message = e.message?.lowercase() ?: ""
            val errorText = when {
                message.contains("handshake") -> "SSL-Verbindung fehlgeschlagen. Server unterstützt möglicherweise kein HTTPS."
                message.contains("certificate") -> "SSL-Zertifikat konnte nicht überprüft werden."
                else -> "Sichere Verbindung nicht möglich. Prüfe die SSL-Konfiguration."
            }
            Result.failure(PaperlessException.NetworkError(IOException(errorText)))
        } catch (e: SocketTimeoutException) {
            Log.d(TAG, "$protocol - Timeout: ${e.message}")
            Result.failure(PaperlessException.NetworkError(
                IOException("Zeitüberschreitung. Server antwortet nicht oder ist zu langsam.")
            ))
        } catch (e: ConnectException) {
            Log.d(TAG, "$protocol - Connection refused: ${e.message}")
            val message = e.message?.lowercase() ?: ""
            val errorText = when {
                message.contains("refused") -> "Verbindung abgelehnt. Läuft der Server auf diesem Port?"
                message.contains("reset") -> "Verbindung zurückgesetzt. Der Server hat die Verbindung unterbrochen."
                else -> "Verbindung fehlgeschlagen. Prüfe ob der Server läuft."
            }
            Result.failure(PaperlessException.NetworkError(IOException(errorText)))
        } catch (e: IOException) {
            Log.d(TAG, "$protocol - IO error: ${e.message}")
            val message = e.message?.lowercase() ?: ""
            val errorText = when {
                message.contains("network") -> "Keine Netzwerkverbindung. Prüfe deine Internetverbindung."
                message.contains("host") -> "Server-Adresse ungültig."
                else -> "Verbindungsfehler: ${e.message ?: "Unbekannter Fehler"}"
            }
            Result.failure(PaperlessException.NetworkError(IOException(errorText)))
        }
    }

    /**
     * Checks if the API response body contains Paperless-specific endpoints.
     * Paperless-ngx /api/ returns JSON with links to: documents, tags, correspondents, etc.
     */
    private fun isPaperlessApiResponse(body: String): Boolean {
        if (body.isBlank()) return false

        val lowerBody = body.lowercase()

        // Check for multiple Paperless-specific API endpoints
        val paperlessEndpoints = listOf(
            "documents",
            "tags",
            "correspondents",
            "document_types",
            "saved_views"
        )

        // Must contain at least 3 of these endpoints to be considered Paperless
        val matchCount = paperlessEndpoints.count { lowerBody.contains("\"$it\"") || lowerBody.contains("/$it/") }
        Log.d(TAG, "Paperless endpoint match count: $matchCount")

        return matchCount >= 3
    }

    /**
     * Secondary verification using /api/documents/ endpoint.
     * This endpoint is specific to Paperless and should return:
     * - 401/403 if auth required (valid Paperless)
     * - 200 with results array if public (valid Paperless)
     * - 404 if not Paperless
     */
    private fun verifyPaperlessWithDocumentsEndpoint(url: String, protocol: String): Result<String> {
        Log.d(TAG, "$protocol - Verifying with /api/documents/ endpoint")

        return try {
            val request = Request.Builder()
                .url("$url/api/documents/?page_size=1")
                .get()
                .build()

            detectionClient.newCall(request).execute().use { response ->
                Log.d(TAG, "$protocol /api/documents/ response: ${response.code}")

                when (response.code) {
                    in 200..299 -> {
                        // Check if response has Paperless document structure
                        val body = response.body?.string() ?: ""
                        if (body.contains("\"results\"") && (body.contains("\"count\"") || body.contains("\"id\""))) {
                            Log.d(TAG, "$protocol - Verified as Paperless via documents endpoint")
                            Result.success(url)
                        } else {
                            Log.d(TAG, "$protocol - /api/documents/ exists but wrong format")
                            Result.failure(PaperlessException.NetworkError(
                                IOException("Server hat eine /api/ Schnittstelle, aber es ist kein Paperless-ngx Server.")
                            ))
                        }
                    }
                    401, 403 -> {
                        // Auth required - this is expected for Paperless
                        Log.d(TAG, "$protocol - Verified as Paperless (auth required)")
                        Result.success(url)
                    }
                    404 -> {
                        Log.d(TAG, "$protocol - /api/documents/ not found - not Paperless")
                        Result.failure(PaperlessException.NetworkError(
                            IOException("Server hat eine /api/ Schnittstelle, aber es ist kein Paperless-ngx Server.")
                        ))
                    }
                    else -> {
                        Log.d(TAG, "$protocol - Unexpected documents response: ${response.code}")
                        Result.failure(PaperlessException.NetworkError(
                            IOException("Unerwartete Antwort vom Server (${response.code}). Ist dies ein Paperless-ngx Server?")
                        ))
                    }
                }
            }
        } catch (e: IOException) {
            Log.e(TAG, "$protocol - Documents endpoint check failed", e)
            Result.failure(PaperlessException.NetworkError(
                IOException("Verbindungsfehler bei der Server-Überprüfung.")
            ))
        }
    }

    suspend fun login(serverUrl: String, username: String, password: String): Result<String> {
        return try {
            val normalizedUrl = serverUrl.trimEnd('/')
            val formBody = FormBody.Builder()
                .add("username", username)
                .add("password", password)
                .build()

            val request = Request.Builder()
                .url("$normalizedUrl/api/token/")
                .post(formBody)
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                val json = JSONObject(responseBody ?: "{}")
                val token = json.optString("token", "")

                if (token.isNotBlank()) {
                    tokenManager.saveCredentials(normalizedUrl, token)
                    Result.success(token)
                } else {
                    Result.failure(PaperlessException.ParseError("Token nicht in Antwort gefunden"))
                }
            } else {
                val errorBody = response.body?.string() ?: ""
                val errorMessage = parseLoginError(errorBody, response.code)
                val exception = PaperlessException.AuthError(
                    code = response.code,
                    message = errorMessage
                )
                Result.failure(exception)
            }
        } catch (e: IOException) {
            Result.failure(PaperlessException.NetworkError(e))
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    /**
     * Parses login error response and returns a user-friendly message
     */
    private fun parseLoginError(errorBody: String, responseCode: Int): String {
        val lowerBody = errorBody.lowercase()

        // Check for MFA/2FA related errors
        return when {
            lowerBody.contains("otp") ||
            lowerBody.contains("mfa") ||
            lowerBody.contains("totp") ||
            lowerBody.contains("two-factor") ||
            lowerBody.contains("2fa") ||
            lowerBody.contains("authenticator") -> {
                "Du hast eine zusätzliche Anmeldeschutz aktiviert. " +
                "Bitte wechsle oben auf \"Schlüssel\" und melde dich mit deinem Zugangsschlüssel an."
            }
            responseCode == 401 || responseCode == 403 -> {
                "Benutzername oder Passwort ist falsch"
            }
            else -> {
                "Anmeldung fehlgeschlagen"
            }
        }
    }

    suspend fun validateToken(serverUrl: String, token: String): Result<Unit> {
        return try {
            val normalizedUrl = serverUrl.trimEnd('/')
            // Clean the token - remove any whitespace or line breaks from OCR
            val cleanToken = token.trim().replace("\\s+".toRegex(), "")

            Log.d(TAG, "Validating token (length: ${cleanToken.length}) against: $normalizedUrl")

            val request = Request.Builder()
                .url("$normalizedUrl/api/tags/?page_size=1")
                .header("Authorization", "Token $cleanToken")
                .get()
                .build()

            val response = client.newCall(request).execute()
            Log.d(TAG, "Token validation response: ${response.code}")

            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                // Provide better error message for token validation failures
                val errorMessage = when (response.code) {
                    401 -> "Schlüssel ungültig. Bitte prüfe, ob du den richtigen Schlüssel verwendest."
                    403 -> "Zugriff verweigert. Dieser Schlüssel hat keine Berechtigung."
                    else -> "Verbindung fehlgeschlagen (${response.code})"
                }
                Result.failure(PaperlessException.AuthError(response.code, errorMessage))
            }
        } catch (e: IOException) {
            Log.e(TAG, "Token validation network error", e)
            Result.failure(PaperlessException.NetworkError(e))
        } catch (e: Exception) {
            Log.e(TAG, "Token validation error", e)
            Result.failure(PaperlessException.from(e))
        }
    }

    suspend fun logout() {
        tokenManager.clearCredentials()
    }
}
