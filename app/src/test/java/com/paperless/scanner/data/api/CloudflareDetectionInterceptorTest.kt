package com.paperless.scanner.data.api

import android.util.Log
import com.paperless.scanner.data.datastore.ServerUrlHolder
import com.paperless.scanner.data.datastore.TokenManager
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * Regression guard for issue #33: [CloudflareDetectionInterceptor] runs on every
 * OkHttp response (on a dispatcher thread). It used to read the server URL via the
 * `runBlocking` `TokenManager.getServerUrlSync()`, which can starve the OkHttp
 * dispatcher pool under DataStore/crypto contention. The interceptor now reads the
 * non-blocking [ServerUrlHolder.current] (a single atomic load).
 *
 * The decisive assertion is `verify(exactly = 0) { tokenManager.getServerUrlSync() }`:
 * the blocking call must NEVER run on the hot path. Cloudflare detection and the
 * reset-on-server-change behavior are also asserted to confirm no regression.
 *
 * Mirrors the mockk + `mockkStatic(Log)` style of [AppModuleInterceptorOrderTest].
 */
class CloudflareDetectionInterceptorTest {

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    private fun response(code: Int, cfRay: String? = null): Response {
        val request = Request.Builder().url("https://docs.example.com/api/").build()
        val builder = Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("msg")
            .body("".toResponseBody(null))
        if (cfRay != null) builder.header("cf-ray", cfRay)
        return builder.build()
    }

    private fun chainReturning(response: Response): Interceptor.Chain {
        val chain = mockk<Interceptor.Chain>()
        every { chain.request() } returns response.request
        every { chain.proceed(any()) } returns response
        return chain
    }

    @Test
    fun `intercept reads ServerUrlHolder and never the blocking getServerUrlSync`() = runTest {
        val tokenManager = mockk<TokenManager>(relaxed = true)
        val holder = mockk<ServerUrlHolder>()
        every { holder.current() } returns "https://docs.example.com"
        val interceptor = CloudflareDetectionInterceptor(
            tokenManager, holder, CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        )

        interceptor.intercept(chainReturning(response(200, cfRay = "ray-1")))

        // THE #33 contract: the blocking runBlocking read must never run on the
        // OkHttp dispatcher hot path; the non-blocking holder is read instead.
        verify(exactly = 0) { tokenManager.getServerUrlSync() }
        verify(atLeast = 1) { holder.current() }
    }

    @Test
    fun `detects cloudflare via cf-ray header and persists once per session`() = runTest {
        val tokenManager = mockk<TokenManager>(relaxed = true)
        val holder = mockk<ServerUrlHolder>()
        every { holder.current() } returns "https://docs.example.com"
        val interceptor = CloudflareDetectionInterceptor(
            tokenManager, holder, CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        )

        // Same server across two responses: the hasDetected latch fires detection once.
        interceptor.intercept(chainReturning(response(200, cfRay = "ray-1")))
        interceptor.intercept(chainReturning(response(200, cfRay = "ray-2")))

        coVerify(exactly = 1) { tokenManager.setServerUsesCloudflare(true) }
    }

    @Test
    fun `resets detection when the server url changes`() = runTest {
        val tokenManager = mockk<TokenManager>(relaxed = true)
        val holder = mockk<ServerUrlHolder>()
        every { holder.current() } returnsMany listOf("https://a.example.com", "https://b.example.com")
        val interceptor = CloudflareDetectionInterceptor(
            tokenManager, holder, CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        )

        // First server is behind Cloudflare; after the URL changes the latch resets
        // and detection runs again for the new server (this time without cf-ray).
        interceptor.intercept(chainReturning(response(200, cfRay = "ray-1")))
        interceptor.intercept(chainReturning(response(200, cfRay = null)))

        coVerify(exactly = 1) { tokenManager.setServerUsesCloudflare(true) }
        coVerify(exactly = 1) { tokenManager.setServerUsesCloudflare(false) }
    }
}
