package com.paperless.scanner.data.repository

import android.content.Context
import androidx.test.filters.SmallTest
import com.paperless.scanner.R
import com.paperless.scanner.data.analytics.AuthDebugService
import com.paperless.scanner.data.analytics.CrashlyticsHelper
import com.paperless.scanner.data.api.CloudflareDetectionInterceptor
import com.paperless.scanner.data.datastore.TokenManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Repository tests for [AuthRepository].
 *
 * Marked `@SmallTest` because [AuthRepository] depends on Context, OkHttp,
 * TokenManager, and analytics — no Room DAO. Robolectric is required for
 * `Context` access. Pure unit test scope per Issue #137.
 */
@SmallTest
@RunWith(RobolectricTestRunner::class)
class AuthRepositoryTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var context: Context
    private lateinit var tokenManager: TokenManager
    private lateinit var cloudflareDetectionInterceptor: CloudflareDetectionInterceptor
    private lateinit var crashlyticsHelper: CrashlyticsHelper
    private lateinit var authDebugService: AuthDebugService
    private lateinit var httpCache: Cache
    private lateinit var authRepository: AuthRepository
    private lateinit var client: OkHttpClient

    @Before
    fun setup() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        context = mockk(relaxed = true)
        // Mock specific string resource lookups (relaxed mock returns "" for unmocked getString calls)
        every { context.getString(R.string.error_username_password_incorrect) } returns "Invalid username or password"
        every { context.getString(R.string.error_token_not_in_response) } returns "Token not found in response"
        tokenManager = mockk(relaxed = true)
        cloudflareDetectionInterceptor = mockk(relaxed = true)
        crashlyticsHelper = mockk(relaxed = true)
        authDebugService = mockk(relaxed = true)
        httpCache = mockk(relaxed = true)
        client = OkHttpClient.Builder().build()
        authRepository = AuthRepository(context, tokenManager, client, cloudflareDetectionInterceptor, crashlyticsHelper, authDebugService, httpCache)
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `login success returns token and saves credentials`() = runTest {
        val expectedToken = "test-token-12345"
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"token": "$expectedToken"}""")
        )

        val serverUrl = mockWebServer.url("/").toString().trimEnd('/')
        val result = authRepository.login(serverUrl, "testuser", "testpass")

        assertTrue(result.isSuccess)
        val loginResult = result.getOrNull() as? AuthRepository.LoginResult.Success
        assertEquals(expectedToken, loginResult?.token)
        coVerify { tokenManager.saveCredentials(serverUrl, expectedToken) }

        val request = mockWebServer.takeRequest()
        assertEquals("/api/token/", request.path)
        assertEquals("POST", request.method)
        assertTrue(request.body.readUtf8().contains("username=testuser"))
    }

    @Test
    fun `login with trailing slash normalizes url`() = runTest {
        val expectedToken = "test-token"
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"token": "$expectedToken"}""")
        )

        val serverUrl = mockWebServer.url("/").toString()
        val result = authRepository.login("$serverUrl/", "user", "pass")

        assertTrue(result.isSuccess)
        coVerify { tokenManager.saveCredentials(serverUrl.trimEnd('/'), expectedToken) }
    }

    @Test
    fun `login failure returns error with status code`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"detail": "Invalid credentials"}""")
        )

        val serverUrl = mockWebServer.url("/").toString()
        val result = authRepository.login(serverUrl, "wronguser", "wrongpass")

        assertTrue(result.isFailure)
        // AuthRepository now uses string resources for i18n - verify error is returned
        assertTrue(result.exceptionOrNull()?.message?.isNotEmpty() == true)
    }

    @Test
    fun `login with empty token returns failure`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"token": ""}""")
        )

        val serverUrl = mockWebServer.url("/").toString()
        val result = authRepository.login(serverUrl, "user", "pass")

        assertTrue(result.isFailure)
        // Message now uses string resources - verify mocked string is returned
        assertEquals("Token not found in response", result.exceptionOrNull()?.message)
    }

    @Test
    fun `login with missing token field returns failure`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"error": "unexpected response"}""")
        )

        val serverUrl = mockWebServer.url("/").toString()
        val result = authRepository.login(serverUrl, "user", "pass")

        assertTrue(result.isFailure)
        // Message now uses string resources - verify mocked string is returned
        assertEquals("Token not found in response", result.exceptionOrNull()?.message)
    }

    @Test
    fun `login with network error returns failure`() = runTest {
        mockWebServer.shutdown()

        val result = authRepository.login("http://localhost:9999", "user", "pass")

        assertTrue(result.isFailure)
    }

    @Test
    fun `logout clears credentials`() = runTest {
        coEvery { tokenManager.clearCredentials() } returns Unit

        authRepository.logout()

        coVerify { tokenManager.clearCredentials() }
    }

    @Test
    fun `logout evicts shared http cache`() = runTest {
        // Issue #131 AC: cache cleared on logout so a different account or
        // server on the same device starts cold, not on the prior user's
        // cached document/tag responses.
        coEvery { tokenManager.clearCredentials() } returns Unit

        authRepository.logout()

        verify { httpCache.evictAll() }
    }

    // --- detectServerProtocol tests (Issue #140: NPE safety on null error variables) ---

    @Test
    fun `detectServerProtocol with unreachable host returns failure without NPE`() = runTest {
        // Both HTTPS and HTTP must fail without crashing. Before the fix this path used
        // !! on potentially-null error variables, so the test guards that fix stays.
        val result = authRepository.detectServerProtocol("nonexistent-host-test-12345.invalid")

        assertTrue("Expected failure for unreachable host", result.isFailure)
        assertNotNull("Exception must not be null after both protocols fail", result.exceptionOrNull())
    }

    @Test
    fun `detectServerProtocol falls back to HTTP when HTTPS fails`() = runTest {
        // MockWebServer runs HTTP only — HTTPS attempt fails, HTTP /api/ succeeds.
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"documents":"/api/documents/","tags":"/api/tags/",""" +
                        """"correspondents":"/api/correspondents/",""" +
                        """"document_types":"/api/document_types/",""" +
                        """"saved_views":"/api/saved_views/"}"""
                )
        )

        val host = "${mockWebServer.hostName}:${mockWebServer.port}"
        val result = authRepository.detectServerProtocol(host)

        assertTrue(
            "Should succeed via HTTP fallback. Error: ${result.exceptionOrNull()?.message}",
            result.isSuccess
        )
        assertEquals("http://$host", result.getOrNull())
    }

    @Test
    fun `detectServerProtocol with empty host returns ContentError`() = runTest {
        val result = authRepository.detectServerProtocol("")

        assertTrue("Empty host should fail parse", result.isFailure)
        assertTrue(
            "Expected ContentError, got: ${result.exceptionOrNull()?.javaClass?.simpleName}",
            result.exceptionOrNull() is com.paperless.scanner.data.api.PaperlessException.ContentError
        )
    }

    // --- Issue #233: userScheme respect + CleartextBlocked propagation ---

    @Test
    fun `detectServerProtocol with explicit http scheme only probes HTTP`() = runTest {
        // MockWebServer runs HTTP only. Enqueue exactly ONE Paperless-shaped
        // response — if the code under test also probed HTTPS, the
        // enqueue queue would still serve this for the HTTP attempt and
        // requestCount would be >1 (any extra probe), or the call would
        // fail trying to TLS-handshake against a non-TLS port. Either way
        // the test below detects regression to the old dual-probe behavior.
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"correspondents":"/api/correspondents/","documents":"/api/documents/","tags":"/api/tags/"}""")
        )

        val host = "${mockWebServer.hostName}:${mockWebServer.port}"
        val result = authRepository.detectServerProtocol("http://$host")

        assertTrue("Expected success when HTTP /api/ is Paperless", result.isSuccess)
        assertEquals("http://$host", result.getOrNull())
        assertEquals(
            "Should make exactly one request (HTTP only, no HTTPS probe)",
            1, mockWebServer.requestCount
        )
    }

    @Test
    fun `detectServerProtocol with explicit https scheme does not fall back to HTTP`() = runTest {
        // MockWebServer is HTTP-only — the HTTPS probe will fail. We must
        // NOT see a subsequent HTTP request — that would be the old fallback
        // behavior that turns "user typed https://" into an unencrypted
        // connection silently. AC-A explicitly forbids that.
        //
        // We can't use mockWebServer.requestCount as the assertion: depending
        // on the host OS, MockWebServer's TCP listener may count the failed
        // TLS ClientHello bytes as 0 or 1 connections. Instead we verify via
        // the AuthDebugService breadcrumb — the https-only path logs
        // SERVER_DETECT_FAILED_HTTPS_ONLY, the dual-probe path logs
        // SERVER_DETECT_FAILED with both attempts populated.
        val host = "${mockWebServer.hostName}:${mockWebServer.port}"
        val result = authRepository.detectServerProtocol("https://$host")

        assertTrue("HTTPS-only probe must fail when server is plain HTTP", result.isFailure)
        verify {
            authDebugService.logAuthFailure(
                authType = any(),
                serverUrl = any(),
                errorType = "SERVER_DETECT_FAILED_HTTPS_ONLY",
                errorMessage = any(),
                serverDetection = any()
            )
        }
    }

    @Test
    fun `detectServerProtocol propagates CleartextBlocked when interceptor denies`() = runTest {
        // Wire a HttpAllowlistInterceptor with an empty allowlist into a
        // fresh OkHttpClient and a fresh AuthRepository. detectServerProtocol
        // for a non-loopback http:// URL must surface CleartextBlocked, not
        // a generic NetworkError, so LoginViewModel can route to the dialog.
        val allowlistHolder = mockk<com.paperless.scanner.data.api.HttpAllowlistHolder>()
        every { allowlistHolder.snapshot() } returns emptySet()
        val interceptor = com.paperless.scanner.data.api.HttpAllowlistInterceptor(allowlistHolder)
        val wiredClient = OkHttpClient.Builder().addInterceptor(interceptor).build()
        val wiredRepo = AuthRepository(
            context, tokenManager, wiredClient, cloudflareDetectionInterceptor,
            crashlyticsHelper, authDebugService, httpCache
        )

        // Non-loopback host that the interceptor will refuse. We pass with
        // explicit http:// so detectServerProtocol takes the http-only path.
        val result = wiredRepo.detectServerProtocol("http://192.168.178.19")

        assertTrue("Expected failure when interceptor blocks", result.isFailure)
        val cause = result.exceptionOrNull()
        assertTrue(
            "Expected CleartextBlocked, got: ${cause?.javaClass?.simpleName} - ${cause?.message}",
            cause is com.paperless.scanner.data.api.PaperlessException.CleartextBlocked
        )
        assertEquals(
            "Host field should match the requested host",
            "192.168.178.19",
            (cause as com.paperless.scanner.data.api.PaperlessException.CleartextBlocked).host
        )
    }
}
