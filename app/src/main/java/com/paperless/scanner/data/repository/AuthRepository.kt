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

        // Prioritize specific errors
        return when {
            httpsError is PaperlessException.NetworkError &&
                httpsError.message?.contains("nicht gefunden") == true -> Result.failure(httpsError)
            httpError is PaperlessException.NetworkError &&
                httpError.message?.contains("nicht gefunden") == true -> Result.failure(httpError)
            else -> Result.failure(
                PaperlessException.NetworkError(
                    IOException("Server nicht erreichbar. Prüfe die Adresse und Internetverbindung.")
                )
            )
        }
    }

    private fun tryProtocol(protocol: String, host: String): Result<String> {
        val url = "$protocol://$host"
        Log.d(TAG, "Trying $protocol: $url")

        return try {
            val request = Request.Builder()
                .url("$url/api/")
                .head()
                .build()

            detectionClient.newCall(request).execute().use { response ->
                // Any response (even 401/403/404) means server is reachable
                Log.d(TAG, "$protocol successful: ${response.code}")
                Result.success(url)
            }
        } catch (e: UnknownHostException) {
            Log.e(TAG, "$protocol - Unknown host: ${e.message}")
            Result.failure(PaperlessException.NetworkError(IOException("Server nicht gefunden: $host")))
        } catch (e: SSLHandshakeException) {
            Log.d(TAG, "$protocol - SSL handshake failed: ${e.message}")
            Result.failure(PaperlessException.NetworkError(IOException("SSL-Zertifikat ungültig")))
        } catch (e: SSLException) {
            Log.d(TAG, "$protocol - SSL error: ${e.message}")
            Result.failure(PaperlessException.NetworkError(IOException("SSL-Fehler")))
        } catch (e: SocketTimeoutException) {
            Log.d(TAG, "$protocol - Timeout: ${e.message}")
            Result.failure(PaperlessException.NetworkError(IOException("Zeitüberschreitung")))
        } catch (e: ConnectException) {
            Log.d(TAG, "$protocol - Connection refused: ${e.message}")
            Result.failure(PaperlessException.NetworkError(IOException("Verbindung abgelehnt")))
        } catch (e: IOException) {
            Log.d(TAG, "$protocol - IO error: ${e.message}")
            Result.failure(PaperlessException.NetworkError(e))
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
