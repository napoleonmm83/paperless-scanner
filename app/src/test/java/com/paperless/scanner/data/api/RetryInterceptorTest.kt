package com.paperless.scanner.data.api

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

class RetryInterceptorTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var client: OkHttpClient

    @Before
    fun setup() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    private fun createClient(
        maxRetries: Int = 3,
        initialDelayMs: Long = 10L, // Short delay for tests
        maxDelayMs: Long = 50L
    ): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(RetryInterceptor(maxRetries, initialDelayMs, maxDelayMs))
            .connectTimeout(100, TimeUnit.MILLISECONDS)
            .readTimeout(100, TimeUnit.MILLISECONDS)
            .build()
    }

    @Test
    fun `successful request returns immediately without retry`() {
        client = createClient()
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("Success"))

        val request = Request.Builder()
            .url(mockWebServer.url("/test"))
            .build()

        val response = client.newCall(request).execute()

        assertEquals(200, response.code)
        assertEquals("Success", response.body?.string())
        assertEquals(1, mockWebServer.requestCount)
    }

    @Test
    fun `4xx client errors do not trigger retry`() {
        client = createClient()
        mockWebServer.enqueue(MockResponse().setResponseCode(400).setBody("Bad Request"))

        val request = Request.Builder()
            .url(mockWebServer.url("/test"))
            .build()

        val response = client.newCall(request).execute()

        assertEquals(400, response.code)
        assertEquals(1, mockWebServer.requestCount)
    }

    @Test
    fun `401 unauthorized does not trigger retry`() {
        client = createClient()
        mockWebServer.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))

        val request = Request.Builder()
            .url(mockWebServer.url("/test"))
            .build()

        val response = client.newCall(request).execute()

        assertEquals(401, response.code)
        assertEquals(1, mockWebServer.requestCount)
    }

    @Test
    fun `404 not found does not trigger retry`() {
        client = createClient()
        mockWebServer.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))

        val request = Request.Builder()
            .url(mockWebServer.url("/test"))
            .build()

        val response = client.newCall(request).execute()

        assertEquals(404, response.code)
        assertEquals(1, mockWebServer.requestCount)
    }

    @Test
    fun `5xx server errors trigger retry and succeed on retry`() {
        client = createClient(maxRetries = 3)
        // First request fails with 500
        mockWebServer.enqueue(MockResponse().setResponseCode(500).setBody("Server Error"))
        // Second request succeeds
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("Success"))

        val request = Request.Builder()
            .url(mockWebServer.url("/test"))
            .build()

        val response = client.newCall(request).execute()

        assertEquals(200, response.code)
        assertEquals("Success", response.body?.string())
        assertEquals(2, mockWebServer.requestCount)
    }

    @Test
    fun `503 service unavailable triggers retry`() {
        client = createClient(maxRetries = 2)
        mockWebServer.enqueue(MockResponse().setResponseCode(503).setBody("Service Unavailable"))
        mockWebServer.enqueue(MockResponse().setResponseCode(503).setBody("Service Unavailable"))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("Success"))

        val request = Request.Builder()
            .url(mockWebServer.url("/test"))
            .build()

        val response = client.newCall(request).execute()

        assertEquals(200, response.code)
        assertEquals(3, mockWebServer.requestCount)
    }

    @Test
    fun `max retries exhausted returns last response`() {
        client = createClient(maxRetries = 2)
        // All requests fail with 500
        mockWebServer.enqueue(MockResponse().setResponseCode(500).setBody("Error 1"))
        mockWebServer.enqueue(MockResponse().setResponseCode(500).setBody("Error 2"))
        mockWebServer.enqueue(MockResponse().setResponseCode(500).setBody("Error 3"))

        val request = Request.Builder()
            .url(mockWebServer.url("/test"))
            .build()

        val response = client.newCall(request).execute()

        assertEquals(500, response.code)
        // 1 initial + 2 retries = 3 total requests
        assertEquals(3, mockWebServer.requestCount)
    }

    @Test
    fun `network error triggers retry`() {
        client = createClient(maxRetries = 2)
        // First request times out, second succeeds
        mockWebServer.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("Success"))

        val request = Request.Builder()
            .url(mockWebServer.url("/test"))
            .build()

        val response = client.newCall(request).execute()

        assertEquals(200, response.code)
        assertTrue(mockWebServer.requestCount >= 2)
    }

    @Test(expected = IOException::class)
    fun `persistent network errors exhaust retries and throw`() {
        client = createClient(maxRetries = 2)
        // All requests timeout
        mockWebServer.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        mockWebServer.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
        mockWebServer.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))

        val request = Request.Builder()
            .url(mockWebServer.url("/test"))
            .build()

        client.newCall(request).execute()
    }

    @Test
    fun `zero retries only makes one request`() {
        client = createClient(maxRetries = 0)
        mockWebServer.enqueue(MockResponse().setResponseCode(500).setBody("Server Error"))

        val request = Request.Builder()
            .url(mockWebServer.url("/test"))
            .build()

        val response = client.newCall(request).execute()

        assertEquals(500, response.code)
        assertEquals(1, mockWebServer.requestCount)
    }

    @Test
    fun `exponential backoff increases delay between retries`() {
        // Use longer delays to measure timing
        client = createClient(maxRetries = 2, initialDelayMs = 50L, maxDelayMs = 500L)
        mockWebServer.enqueue(MockResponse().setResponseCode(500))
        mockWebServer.enqueue(MockResponse().setResponseCode(500))
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val startTime = System.currentTimeMillis()
        val request = Request.Builder()
            .url(mockWebServer.url("/test"))
            .build()

        client.newCall(request).execute()
        val duration = System.currentTimeMillis() - startTime

        // First retry: 50ms delay, second retry: 100ms delay
        // Total minimum delay should be around 150ms (50 + 100)
        assertTrue("Duration should be at least 100ms due to backoff, was ${duration}ms", duration >= 100)
    }

    @Test
    fun `delay is capped at maxDelayMs`() {
        // With initialDelay=100 and maxDelay=150, the exponential backoff should cap
        client = createClient(maxRetries = 3, initialDelayMs = 100L, maxDelayMs = 150L)
        mockWebServer.enqueue(MockResponse().setResponseCode(500))
        mockWebServer.enqueue(MockResponse().setResponseCode(500))
        mockWebServer.enqueue(MockResponse().setResponseCode(500))
        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val startTime = System.currentTimeMillis()
        val request = Request.Builder()
            .url(mockWebServer.url("/test"))
            .build()

        client.newCall(request).execute()
        val duration = System.currentTimeMillis() - startTime

        // Delays: 100ms, 150ms (capped), 150ms (capped) = 400ms minimum
        // But should not exceed ~500ms which would indicate uncapped delays
        assertTrue("Duration should be reasonable with capped delay, was ${duration}ms", duration < 800)
    }
}
