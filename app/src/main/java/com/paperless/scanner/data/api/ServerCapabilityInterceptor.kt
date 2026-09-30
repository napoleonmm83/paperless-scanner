package com.paperless.scanner.data.api

import javax.inject.Inject
import okhttp3.Interceptor
import okhttp3.Response

class ServerCapabilityInterceptor @Inject constructor(private val store: ServerCapabilityStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val networkResponse = response.networkResponse
        val actualRequest = response.request
        val session = actualRequest.tag(ServerRequestSession::class.java)
        if (response.isSuccessful && networkResponse != null && networkResponse.code != 304 &&
            session != null && !actualRequest.header("Authorization").isNullOrBlank()) {
            store.observe(session, actualRequest.url, response.header("X-Version"), response.header("X-Api-Version"))
        }
        return response
    }
}
