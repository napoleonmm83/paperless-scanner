package com.paperless.scanner.data.repository

import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.datastore.TokenManager
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
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
