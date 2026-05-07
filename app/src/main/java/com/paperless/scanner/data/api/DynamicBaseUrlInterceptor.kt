package com.paperless.scanner.data.api

import com.paperless.scanner.data.datastore.ServerUrlHolder
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

/**
 * Interceptor that dynamically sets the base URL for each request.
 *
 * Retrofit is constructed with a placeholder URL; the actual server URL is
 * read from [ServerUrlHolder] at request time via a non-blocking atomic
 * load. Previously this read used `runBlocking { tokenManager.serverUrl.first() }`,
 * which serialized concurrent requests under DataStore lock contention
 * (see issue #124 / finding F-097). The holder collects the same Flow once
 * on the application scope and caches the latest value in an
 * [java.util.concurrent.atomic.AtomicReference], so the hot path is now a
 * pure volatile read with no allocation.
 */
class DynamicBaseUrlInterceptor @Inject constructor(
    private val serverUrlHolder: ServerUrlHolder,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val originalRequest = chain.request()

        val serverUrl = serverUrlHolder.current() ?: return chain.proceed(originalRequest)

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
