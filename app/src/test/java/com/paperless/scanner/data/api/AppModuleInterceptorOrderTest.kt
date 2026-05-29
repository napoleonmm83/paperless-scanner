package com.paperless.scanner.data.api

import android.util.Log
import com.paperless.scanner.data.datastore.ServerUrlHolder
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.data.network.CertificatePinningInterceptor
import com.paperless.scanner.di.AppModule
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import okhttp3.Cache
import okhttp3.Request
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.nio.file.Files

/**
 * Pins the interceptor ordering contract in `AppModule.provideOkHttpClient`
 * (issue #221): [HttpAllowlistInterceptor] MUST run BEFORE the token-attaching
 * interceptor, so a request to a non-allowlisted host fails closed *before* the
 * bearer token is ever added. PR #213 enforced this with code-comments only; a
 * single refactoring slip would silently leak the token to a non-allowlisted host.
 *
 * The test builds the REAL client from the actual provider (not a hand-mirrored
 * copy) and drives a denied cleartext request through it. The token interceptor
 * reads the bearer token via [TokenManager.getTokenSync]; on the deny path that
 * call must NEVER happen, because the allowlist throws first. If the two
 * interceptors were swapped in `AppModule`, the token interceptor would run
 * before the allowlist and `getTokenSync()` would be invoked — failing this test.
 *
 * Referenced from the four `provide*OkHttpClient` KDocs/comments in `AppModule`.
 */
class AppModuleInterceptorOrderTest {

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `provideOkHttpClient denies non-allowlisted host before attaching the bearer token`() {
        // Token source: if the token interceptor ever runs, it calls this.
        val tokenManager = mockk<TokenManager>(relaxed = true)
        every { tokenManager.getTokenSync() } returns "test-token"

        // Allowlist holder: nothing accepted → every cleartext non-loopback host denies.
        val holder = mockk<HttpAllowlistHolder>()
        every { holder.snapshot() } returns emptySet()

        // DynamicBaseUrlInterceptor passes through when no server URL is configured,
        // so it leaves our test host intact for the allowlist to inspect.
        val serverUrlHolder = mockk<ServerUrlHolder>()
        every { serverUrlHolder.current() } returns null

        // Downstream interceptors sit after the token interceptor in the chain and
        // are never reached on the deny path; relaxed mocks keep the builder happy.
        val cloudflare = mockk<CloudflareDetectionInterceptor>(relaxed = true)
        val adaptive = mockk<AdaptiveWriteTimeoutInterceptor>(relaxed = true)
        val cacheControl = mockk<CacheControlInterceptor>(relaxed = true)
        val certPinning = mockk<CertificatePinningInterceptor>(relaxed = true)

        val cacheDir = Files.createTempDirectory("okhttp-allowlist-order-test").toFile()
        val cache = Cache(cacheDir, 1024L * 1024L)

        try {
            val client = AppModule.provideOkHttpClient(
                tokenManager = tokenManager,
                dynamicBaseUrlInterceptor = DynamicBaseUrlInterceptor(serverUrlHolder),
                httpAllowlistInterceptor = HttpAllowlistInterceptor(holder),
                cloudflareDetectionInterceptor = cloudflare,
                adaptiveWriteTimeoutInterceptor = adaptive,
                cacheControlInterceptor = cacheControl,
                certificatePinningInterceptor = certPinning,
                cache = cache,
            )

            val call = client.newCall(Request.Builder().url("http://evil.example.com/api/").build())

            // Deny path: the allowlist throws before any socket/token work.
            assertThrows(IOException::class.java) { call.execute() }
            // THE contract: the token interceptor never ran, so no bearer token was
            // attached. A swap of allowlist/token order in AppModule breaks this.
            verify(exactly = 0) { tokenManager.getTokenSync() }
        } finally {
            cache.delete()
            cacheDir.deleteRecursively()
        }
    }
}
