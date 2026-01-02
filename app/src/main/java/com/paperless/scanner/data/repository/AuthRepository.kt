package com.paperless.scanner.data.repository

import com.paperless.scanner.data.datastore.TokenManager
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import javax.inject.Inject

class AuthRepository @Inject constructor(
    private val tokenManager: TokenManager,
    private val client: OkHttpClient
) {

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
                    Result.failure(Exception("Token not found in response"))
                }
            } else {
                val errorBody = response.body?.string()
                Result.failure(Exception("Login failed: ${response.code} - $errorBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun validateToken(serverUrl: String, token: String): Result<Unit> {
        return try {
            val normalizedUrl = serverUrl.trimEnd('/')

            // Try to fetch tags to validate the token
            val request = Request.Builder()
                .url("$normalizedUrl/api/tags/?page_size=1")
                .header("Authorization", "Token $token")
                .get()
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                Result.success(Unit)
            } else if (response.code == 401 || response.code == 403) {
                Result.failure(Exception("Ungültiger Token"))
            } else {
                Result.failure(Exception("Serverfehler: ${response.code}"))
            }
        } catch (e: Exception) {
            Result.failure(Exception("Verbindung fehlgeschlagen: ${e.message}"))
        }
    }

    suspend fun logout() {
        tokenManager.clearCredentials()
    }
}
