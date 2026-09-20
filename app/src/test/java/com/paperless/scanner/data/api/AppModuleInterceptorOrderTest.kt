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
import org.junit.Assert.assertTrue
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
                apiVersionInterceptor = ApiVersionInterceptor(mockk(relaxed = true)),
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

    /**
     * Pins the *registration kind* of the two security-critical interceptors,
     * which the ordering test above cannot see.
     *
     * [CertificatePinningInterceptor] only works as a NETWORK interceptor: an
     * application interceptor never gets a `chain.connection()`, so the pin
     * check would find no certificate on every call. [CacheControlInterceptor]
     * has the mirror-image requirement — the cache layer sits between the two
     * lists, so as an application interceptor its directives would be a no-op.
     *
     * Downgrading either `addNetworkInterceptor` to `addInterceptor` in
     * `AppModule` turns a security control into a permanent silent no-op, and
     * before this test every other test in the suite stayed green through it —
     * the pinning tests wire their own `addNetworkInterceptor`, and the ordering
     * test above passes relaxed mocks that never exercise the wiring.
     *
     * Covers ALL FOUR clients that register the pinning interceptor — default,
     * auth, Paperless-GPT and Coil. Checking only some of them would leave the
     * rest downgradable in silence, which is the very failure this test exists
     * to prevent.
     */
    @Test
    fun `pinning and cache-control are registered as network interceptors`() {
        val tokenManager = mockk<TokenManager>(relaxed = true)
        val holder = mockk<HttpAllowlistHolder>()
        every { holder.snapshot() } returns emptySet()
        val serverUrlHolder = mockk<ServerUrlHolder>()
        every { serverUrlHolder.current() } returns null

        // Identity is compared below, so these must be the very instances passed in.
        val cacheControl = mockk<CacheControlInterceptor>(relaxed = true)
        val certPinning = mockk<CertificatePinningInterceptor>(relaxed = true)

        val cacheDir = Files.createTempDirectory("okhttp-registration-test").toFile()
        val cache = Cache(cacheDir, 1024L * 1024L)

        try {
            val default = AppModule.provideOkHttpClient(
                tokenManager = tokenManager,
                dynamicBaseUrlInterceptor = DynamicBaseUrlInterceptor(serverUrlHolder),
                httpAllowlistInterceptor = HttpAllowlistInterceptor(holder),
                apiVersionInterceptor = ApiVersionInterceptor(mockk(relaxed = true)),
                cloudflareDetectionInterceptor = mockk(relaxed = true),
                adaptiveWriteTimeoutInterceptor = mockk(relaxed = true),
                cacheControlInterceptor = cacheControl,
                certificatePinningInterceptor = certPinning,
                cache = cache,
            )

            assertTrue(
                "default client: pinning must be a NETWORK interceptor",
                default.networkInterceptors.any { it === certPinning }
            )
            assertTrue(
                "default client: pinning must NOT be an application interceptor",
                default.interceptors.none { it === certPinning }
            )
            assertTrue(
                "default client: cache-control must be a NETWORK interceptor",
                default.networkInterceptors.any { it === cacheControl }
            )

            val auth = AppModule.provideAuthOkHttpClient(
                tokenManager = tokenManager,
                httpAllowlistInterceptor = HttpAllowlistInterceptor(holder),
                certificatePinningInterceptor = certPinning,
            )

            assertTrue(
                "auth client: pinning must be a NETWORK interceptor",
                auth.networkInterceptors.any { it === certPinning }
            )
            assertTrue(
                "auth client: pinning must NOT be an application interceptor",
                auth.interceptors.none { it === certPinning }
            )

            // The GPT client carries the same auth token, the Coil client carries
            // document content — a downgrade in either is just as silent.
            val gpt = AppModule.providePaperlessGptOkHttpClient(
                tokenManager = tokenManager,
                paperlessGptBaseUrlInterceptor = mockk(relaxed = true),
                httpAllowlistInterceptor = HttpAllowlistInterceptor(holder),
                certificatePinningInterceptor = certPinning,
            )
            assertTrue(
                "gpt client: pinning must be a NETWORK interceptor",
                gpt.networkInterceptors.any { it === certPinning }
            )
            assertTrue(
                "gpt client: pinning must NOT be an application interceptor",
                gpt.interceptors.none { it === certPinning }
            )

            val coil = AppModule.provideCoilOkHttpClient(
                tokenManager = tokenManager,
                httpAllowlistInterceptor = HttpAllowlistInterceptor(holder),
                certificatePinningInterceptor = certPinning,
            )
            assertTrue(
                "coil client: pinning must be a NETWORK interceptor",
                coil.networkInterceptors.any { it === certPinning }
            )
            assertTrue(
                "coil client: pinning must NOT be an application interceptor",
                coil.interceptors.none { it === certPinning }
            )
        } finally {
            cache.delete()
            cacheDir.deleteRecursively()
        }
    }
}
