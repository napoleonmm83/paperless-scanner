package com.paperless.scanner.data.repository

import android.util.Log
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.datastore.TokenManager
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.net.ssl.SSLException

class AuthRepository @Inject constructor(
    private val tokenManager: TokenManager,
    private val client: OkHttpClient
) {
    companion object {
        private const val TAG = "AuthRepository"
    }

    /**
     * Detects whether the server uses HTTPS or HTTP.
     * Tries HTTPS first (preferred), falls back to HTTP if SSL fails.
     *
     * @param host The hostname without protocol (e.g., "paperless.example.com")
     * @return The full URL with detected protocol (e.g., "https://paperless.example.com")
     */
    suspend fun detectServerProtocol(host: String): Result<String> {
        // Clean the host - remove any existing protocol
        val cleanHost = host
            .trim()
            .removePrefix("https://")
            .removePrefix("http://")
            .trimEnd('/')

        if (cleanHost.isBlank()) {
            return Result.failure(PaperlessException.ContentError("Server-Adresse fehlt"))
        }

        // Try HTTPS first (preferred)
        val httpsUrl = "https://$cleanHost"
        Log.d(TAG, "Trying HTTPS: $httpsUrl")

        try {
            val request = Request.Builder()
                .url("$httpsUrl/api/")
                .head()
                .build()

            client.newCall(request).execute().use { response ->
                // Any response (even 401/403) means server is reachable via HTTPS
                Log.d(TAG, "HTTPS successful: ${response.code}")
                return Result.success(httpsUrl)
            }
        } catch (e: SSLException) {
            Log.d(TAG, "HTTPS SSL error, trying HTTP: ${e.message}")
            // SSL error - try HTTP
        } catch (e: SocketTimeoutException) {
            Log.d(TAG, "HTTPS timeout, trying HTTP: ${e.message}")
            // Timeout - might be wrong protocol, try HTTP
        } catch (e: UnknownHostException) {
            // Host not found - don't try HTTP, fail immediately
            Log.e(TAG, "Unknown host: ${e.message}")
            return Result.failure(PaperlessException.NetworkError(IOException("Server nicht gefunden: $cleanHost")))
        } catch (e: IOException) {
            Log.d(TAG, "HTTPS IO error, trying HTTP: ${e.message}")
            // Other IO error - try HTTP
        }

        // Try HTTP as fallback
        val httpUrl = "http://$cleanHost"
        Log.d(TAG, "Trying HTTP: $httpUrl")

        return try {
            val request = Request.Builder()
                .url("$httpUrl/api/")
                .head()
                .build()

            client.newCall(request).execute().use { response ->
                Log.d(TAG, "HTTP successful: ${response.code}")
                Result.success(httpUrl)
            }
        } catch (e: UnknownHostException) {
            Log.e(TAG, "Unknown host on HTTP: ${e.message}")
            Result.failure(PaperlessException.NetworkError(IOException("Server nicht gefunden: $cleanHost")))
        } catch (e: IOException) {
            Log.e(TAG, "Both protocols failed: ${e.message}")
            Result.failure(PaperlessException.NetworkError(IOException("Server nicht erreichbar: $cleanHost")))
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
                val errorBody = response.body?.string()
                val exception = when (response.code) {
                    401, 403 -> PaperlessException.AuthError(
                        code = response.code,
                        message = "Ungültige Anmeldedaten"
                    )
                    else -> PaperlessException.fromHttpCode(response.code, errorBody)
                }
                Result.failure(exception)
            }
        } catch (e: IOException) {
            Result.failure(PaperlessException.NetworkError(e))
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    suspend fun validateToken(serverUrl: String, token: String): Result<Unit> {
        return try {
            val normalizedUrl = serverUrl.trimEnd('/')

            val request = Request.Builder()
                .url("$normalizedUrl/api/tags/?page_size=1")
                .header("Authorization", "Token $token")
                .get()
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(PaperlessException.fromHttpCode(response.code))
            }
        } catch (e: IOException) {
            Result.failure(PaperlessException.NetworkError(e))
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    suspend fun logout() {
        tokenManager.clearCredentials()
    }
}
