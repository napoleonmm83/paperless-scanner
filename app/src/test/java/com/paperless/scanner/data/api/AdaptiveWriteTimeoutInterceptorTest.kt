package com.paperless.scanner.data.api

import android.util.Log
import com.paperless.scanner.data.datastore.CloudflareDetectionHolder
import com.paperless.scanner.util.NetworkConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class AdaptiveWriteTimeoutInterceptorTest {

    /**
     * Tests verify the chain's write timeout is overridden as expected by
     * placing a spy interceptor immediately after the SUT and capturing
     * [Interceptor.Chain.writeTimeoutMillis]. The spy reads the timeout that
     * any later interceptor / network call would observe.
     */

    private lateinit var mockWebServer: MockWebServer

    /** Default global write timeout used by the test client (millis). */
    private val globalWriteTimeoutMs = 60_000

    @Before
    fun setup() {
        // android.util.Log is a stub in JVM unit tests; mock it so the
        // interceptor's Log.d call does not throw UnsatisfiedLinkError.
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0

        mockWebServer = MockWebServer()
        mockWebServer.start()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        unmockkStatic(Log::class)
    }

    private fun runRequest(
        cloudflareDetected: Boolean?,
        request: Request,
    ): Int {
        val holder = mockk<CloudflareDetectionHolder>()
        every { holder.current() } returns cloudflareDetected

        var capturedTimeoutMs = -1
        val client = OkHttpClient.Builder()
            .addInterceptor(AdaptiveWriteTimeoutInterceptor(holder))
            .addInterceptor { chain ->
                capturedTimeoutMs = chain.writeTimeoutMillis()
                chain.proceed(chain.request())
            }
            .writeTimeout(globalWriteTimeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .build()

        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        client.newCall(request).execute().use { /* close response body */ }
        return capturedTimeoutMs
    }

    private fun uploadRequest(bodySizeBytes: Int): Request {
        val body = ByteArray(bodySizeBytes)
            .toRequestBody("application/octet-stream".toMediaType())
        return Request.Builder()
            .url(mockWebServer.url("/api/documents/post_document/"))
            .post(body)
            .build()
    }

    @Test
    fun `non-upload request leaves writeTimeout at global default`() {
        val request = Request.Builder()
            .url(mockWebServer.url("/api/health/"))
            .get()
            .build()

        val timeoutMs = runRequest(cloudflareDetected = false, request = request)

        assertEquals(globalWriteTimeoutMs, timeoutMs)
    }

    @Test
    fun `non-upload POST to a different endpoint is not adapted`() {
        val request = Request.Builder()
            .url(mockWebServer.url("/api/token/"))
            .post(byteArrayOf().toRequestBody("application/json".toMediaType()))
            .build()

        val timeoutMs = runRequest(cloudflareDetected = false, request = request)

        assertEquals(globalWriteTimeoutMs, timeoutMs)
    }

    @Test
    fun `small upload uses base timeout when Cloudflare not detected`() {
        // Body size < 1 MB — sizeMb rounds up to 1 → base + 1 * perMb.
        val timeoutMs = runRequest(
            cloudflareDetected = false,
            request = uploadRequest(bodySizeBytes = 100_000),
        )

        val expectedSec =
            NetworkConfig.WRITE_TIMEOUT_BASE_SECONDS + NetworkConfig.WRITE_TIMEOUT_PER_MB_SECONDS
        assertEquals(expectedSec.toInt() * 1_000, timeoutMs)
    }

    @Test
    fun `medium upload scales with payload size when Cloudflare not detected`() {
        // 10 MB body → base + 10 * perMb seconds.
        val tenMb = 10 * 1024 * 1024
        val timeoutMs = runRequest(
            cloudflareDetected = false,
            request = uploadRequest(bodySizeBytes = tenMb),
        )

        val expectedSec =
            NetworkConfig.WRITE_TIMEOUT_BASE_SECONDS + 10L * NetworkConfig.WRITE_TIMEOUT_PER_MB_SECONDS
        assertEquals(expectedSec.toInt() * 1_000, timeoutMs)
    }

    @Test
    fun `large upload is capped at Cloudflare ceiling when CF detected`() {
        // 30 MB body → uncapped would be base + 30 * perMb = 180s; cap is 90s.
        val thirtyMb = 30 * 1024 * 1024
        val timeoutMs = runRequest(
            cloudflareDetected = true,
            request = uploadRequest(bodySizeBytes = thirtyMb),
        )

        assertEquals(
            NetworkConfig.WRITE_TIMEOUT_CLOUDFLARE_CAP_SECONDS.toInt() * 1_000,
            timeoutMs,
        )
    }

    @Test
    fun `small upload is also capped when Cloudflare detected`() {
        // 100 KB body would compute to 122s without cap; cap is 90s and
        // applies as a ceiling regardless of how small the payload is.
        val timeoutMs = runRequest(
            cloudflareDetected = true,
            request = uploadRequest(bodySizeBytes = 100_000),
        )

        assertEquals(
            NetworkConfig.WRITE_TIMEOUT_CLOUDFLARE_CAP_SECONDS.toInt() * 1_000,
            timeoutMs,
        )
    }

    @Test
    fun `null Cloudflare state is treated as not-detected`() {
        // Holder hasn't received the first emission yet — no cap applied.
        val tenMb = 10 * 1024 * 1024
        val timeoutMs = runRequest(
            cloudflareDetected = null,
            request = uploadRequest(bodySizeBytes = tenMb),
        )

        val expectedSec =
            NetworkConfig.WRITE_TIMEOUT_BASE_SECONDS + 10L * NetworkConfig.WRITE_TIMEOUT_PER_MB_SECONDS
        assertEquals(expectedSec.toInt() * 1_000, timeoutMs)
    }

    @Test
    fun `chunked body falls back to base timeout when Cloudflare not detected`() {
        // A streaming body with unknown length reports contentLength == -1
        // — the interceptor coerces to 0, so sizeMb rounds to 0 and only the
        // base timeout is applied.
        val streamingBody = object : okhttp3.RequestBody() {
            override fun contentType(): okhttp3.MediaType =
                "application/octet-stream".toMediaType()
            override fun contentLength(): Long = -1L
            override fun writeTo(sink: okio.BufferedSink) {
                sink.writeUtf8("streamed payload")
            }
        }
        val request = Request.Builder()
            .url(mockWebServer.url("/api/documents/post_document/"))
            .post(streamingBody)
            .build()

        val timeoutMs = runRequest(cloudflareDetected = false, request = request)

        assertEquals(
            NetworkConfig.WRITE_TIMEOUT_BASE_SECONDS.toInt() * 1_000,
            timeoutMs,
        )
    }
}
