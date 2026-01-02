package com.paperless.scanner.data.api

import com.paperless.scanner.data.datastore.TokenManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * Interceptor that dynamically sets the base URL for each request.
 *
 * This allows Retrofit to be created with a placeholder URL, and the actual
 * server URL is read from TokenManager at request time. The runBlocking call
 * is acceptable here because:
 * 1. OkHttp interceptors run on the OkHttp dispatcher thread pool, not the main thread
 * 2. DataStore read operations are fast (in-memory cache after first read)
 * 3. This avoids blocking the main thread during Hilt initialization
 */
class DynamicBaseUrlInterceptor @Inject constructor(
    private val tokenManager: TokenManager
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        // Get the stored server URL (runs on OkHttp thread, not main thread)
        val serverUrl = runBlocking {
            tokenManager.serverUrl.first()
        }?.trimEnd('/') ?: return chain.proceed(originalRequest)

        val newBaseUrl = serverUrl.toHttpUrlOrNull() ?: return chain.proceed(originalRequest)

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
