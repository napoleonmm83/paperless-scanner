package com.paperless.scanner.data.api

import com.paperless.scanner.data.datastore.ServerUrlHolder
import io.mockk.every
import io.mockk.mockk
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class DynamicBaseUrlInterceptorTest {

    private lateinit var mockWebServer: MockWebServer

    @Before
    fun setup() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    private fun createClient(holder: ServerUrlHolder): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor(DynamicBaseUrlInterceptor(holder))
            .build()
    }

    @Test
    fun `interceptor rewrites request URL to holder value`() {
        val mockUrl = mockWebServer.url("/api/test").toString().trimEnd('/')
        // Holder returns just the host part (matches DataStore-stored format).
        val authority = mockUrl.substringBefore("/api/test")
        val holder = mockk<ServerUrlHolder>()
        every { holder.current() } returns authority

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val client = createClient(holder)
        // Use a placeholder URL — the interceptor should rewrite scheme/host/port.
        val request = Request.Builder()
            .url("http://placeholder.local/api/test")
            .build()

        val response = client.newCall(request).execute()

        assertEquals(200, response.code)
        // Verify MockWebServer actually received the rewritten request.
        val recorded = mockWebServer.takeRequest()
        assertEquals("/api/test", recorded.path)
    }

    @Test
    fun `interceptor passes request through unchanged when holder is empty`() {
        val holder = mockk<ServerUrlHolder>()
        every { holder.current() } returns null

        val client = createClient(holder)
        // A real request to MockWebServer (using the real URL, not the
        // placeholder) verifies the interceptor does NOT rewrite.
        mockWebServer.enqueue(MockResponse().setResponseCode(204))
        val request = Request.Builder()
            .url(mockWebServer.url("/passthrough"))
            .build()

        val response = client.newCall(request).execute()

        assertEquals(204, response.code)
        val recorded = mockWebServer.takeRequest()
        assertEquals("/passthrough", recorded.path)
    }

    @Test
    fun `interceptor passes through when holder value is malformed`() {
        val holder = mockk<ServerUrlHolder>()
        // Not a valid HttpUrl — the interceptor should pass through.
        every { holder.current() } returns "not a url"

        val client = createClient(holder)
        mockWebServer.enqueue(MockResponse().setResponseCode(204))
        val request = Request.Builder()
            .url(mockWebServer.url("/passthrough"))
            .build()

        val response = client.newCall(request).execute()
        assertEquals(204, response.code)
    }

    @Test
    fun `interceptor preserves request path and query during rewrite`() {
        val mockUrl = mockWebServer.url("/").toString().trimEnd('/')
        val holder = mockk<ServerUrlHolder>()
        every { holder.current() } returns mockUrl

        mockWebServer.enqueue(MockResponse().setResponseCode(200))

        val client = createClient(holder)
        val request = Request.Builder()
            .url("http://placeholder.local/api/documents/?page=2&pageSize=25")
            .build()

        client.newCall(request).execute()

        val recorded = mockWebServer.takeRequest()
        assertEquals("/api/documents/?page=2&pageSize=25", recorded.path)
    }
}
