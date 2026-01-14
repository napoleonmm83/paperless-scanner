package com.paperless.scanner.data.ai.paperlessgpt

import com.paperless.scanner.data.datastore.TokenManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * Interceptor that dynamically sets the base URL for Paperless-GPT requests.
 *
 * **Modes:**
 * 1. **Standalone Service**: Uses custom URL from settings (paperlessGptUrl)
 * 2. **Integrated Plugin**: Falls back to Paperless-ngx URL when paperlessGptUrl is null
 *
 * **Implementation Note:**
 * - runBlocking is acceptable here (OkHttp thread pool, not main thread)
 * - DataStore reads are fast (in-memory cache after first read)
 */
class PaperlessGptBaseUrlInterceptor @Inject constructor(
    private val tokenManager: TokenManager
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // Get Paperless-GPT URL (or fallback to Paperless-ngx URL for integrated mode)
        val baseUrl = runBlocking {
            val gptUrl = tokenManager.paperlessGptUrl.first()
            if (gptUrl.isNullOrBlank()) {
                // Integrated mode: Use Paperless-ngx URL
                tokenManager.serverUrl.first()
            } else {
                // Standalone mode: Use dedicated Paperless-GPT URL
                gptUrl
            }
        }?.trimEnd('/') ?: return chain.proceed(originalRequest)

        val newBaseUrl = baseUrl.toHttpUrlOrNull() ?: return chain.proceed(originalRequest)

        // Replace the placeholder host with the actual server URL
        val newUrl = originalRequest.url.newBuilder()
            .scheme(newBaseUrl.scheme)
            .host(newBaseUrl.host)
            .port(newBaseUrl.port)
            .build()

        val newRequest = originalRequest.newBuilder()
            .url(newUrl)
            .build()

        return chain.proceed(newRequest)
    }
}
