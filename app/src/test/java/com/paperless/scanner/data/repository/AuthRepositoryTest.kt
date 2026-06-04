package com.paperless.scanner.data.repository

import android.content.Context
import androidx.test.filters.SmallTest
import com.paperless.scanner.R
import com.paperless.scanner.data.analytics.AuthDebugService
import com.paperless.scanner.data.analytics.CrashlyticsHelper
import com.paperless.scanner.data.api.CloudflareDetectionInterceptor
import com.paperless.scanner.data.api.PaperlessException
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    fun `login with cloudflare 403 maps to proxy-blocked not bad credentials`() = runTest {
        // Issue #27: an edge proxy / WAF (Cloudflare) returns an HTML challenge
        // with a 403, which previously got mis-mapped to "bad credentials".
        // The login is fine — the request never reached Paperless — so we must
        // surface a distinct, typed ProxyBlocked error instead.
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("cf-ray", "8a1b2c3d4e5f6789-FRA")
                .setHeader("Server", "cloudflare")
                .setHeader("Content-Type", "text/html; charset=UTF-8")
                .setBody(
                    "<!DOCTYPE html><html><head><title>Attention Required! | Cloudflare</title></head>" +
                        "<body>Sorry, you have been blocked.</body></html>"
                )
        )

        val serverUrl = mockWebServer.url("/").toString()
        val result = authRepository.login(serverUrl, "validuser", "validpass")

        assertTrue(result.isFailure)
        // #27: must be a distinct ProxyBlocked type (not AuthError), so the login
        // rate limiter never counts a WAF block as a failed credential attempt.
        // The user-facing message is resolved later in the UI via messageResId,
        // so the repository carries only the typed error + HTTP code (no string).
        val exception = result.exceptionOrNull()
        assertTrue(
            "Cloudflare 403 must surface as ProxyBlocked, not AuthError",
            exception is PaperlessException.ProxyBlocked
        )
        assertEquals(403, (exception as PaperlessException.ProxyBlocked).code)
    }

    @Test
    fun `login 403 from Cloudflare-proxied backend with JSON body stays a credential error`() = runTest {
        // Issue #27 regression guard (codex P2): a Paperless instance that simply sits behind
        // Cloudflare returns a legitimate 401/403 as JSON, yet Cloudflare still adds cf-ray /
        // Server: cloudflare headers. Those headers alone must NOT trigger the proxy-blocked
        // message — only a real HTML challenge page (or cf-mitigated) does.
        val proxyBlockedMessage =
            "Request blocked by a proxy or firewall (not your login). Check the server's WAF/Cloudflare settings."
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("cf-ray", "8a1b2c3d4e5f6789-FRA")
                .setHeader("Server", "cloudflare")
                .setHeader("Content-Type", "application/json")
                .setBody("""{"detail": "Invalid credentials"}""")
        )

        val serverUrl = mockWebServer.url("/").toString()
        val result = authRepository.login(serverUrl, "wronguser", "wrongpass")

        assertTrue(result.isFailure)
        assertFalse(
            "A normal Cloudflare-proxied JSON 403 must NOT be a ProxyBlocked",
            result.exceptionOrNull() is PaperlessException.ProxyBlocked
        )
        assertNotEquals(
            "A normal Cloudflare-proxied JSON 403 must NOT be mapped to the proxy-blocked message",
            proxyBlockedMessage,
            result.exceptionOrNull()?.message
        )
    }

    @Test
    fun `login 502 HTML error page without Cloudflare markers is NOT a proxy block`() = runTest {
        // Issue #27 (codex P2): a generic reverse-proxy / origin HTML error page
        // (e.g. nginx 502, a maintenance or 404 page) has a <title> but no
        // Cloudflare challenge markers. It must fall through to normal HTTP
        // handling, NOT be mislabeled as a WAF ProxyBlocked with "check your
        // Cloudflare/WAF settings" guidance.
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(502)
                .setHeader("Server", "nginx")
                .setHeader("Content-Type", "text/html; charset=UTF-8")
                .setBody(
                    "<!DOCTYPE html><html><head><title>502 Bad Gateway</title></head>" +
                        "<body><center><h1>502 Bad Gateway</h1></center></body></html>"
                )
        )

        val serverUrl = mockWebServer.url("/").toString()
        val result = authRepository.login(serverUrl, "user", "pass")

        assertTrue(result.isFailure)
        assertFalse(
            "A generic nginx 502 HTML page must NOT be classified as ProxyBlocked",
            result.exceptionOrNull() is PaperlessException.ProxyBlocked
        )
    }

    @Test
    fun `login Cloudflare-branded 502 outage page is NOT a proxy block`() = runTest {
        // Issue #27 (codex P2 ×2): when a Cloudflare-proxied ORIGIN is down,
        // Cloudflare serves a branded 5xx outage page (502/521/522/525) that
        // contains the word "cloudflare" but is NOT a WAF challenge/block. It
        // must surface as the real server error, not a ProxyBlocked with
        // "check your Cloudflare/WAF settings" guidance. Only the block-page
        // phrases ("Attention Required"/"Sorry, you have been blocked") or the
        // cf-mitigated header may trigger a proxy block.
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(502)
                .setHeader("Server", "cloudflare")
                .setHeader("cf-ray", "8a1b2c3d4e5f6789-FRA")
                .setHeader("Content-Type", "text/html; charset=UTF-8")
                .setBody(
                    "<!DOCTYPE html><html><head><title>502 Bad Gateway</title></head>" +
                        "<body>Error 502 — Bad gateway. Cloudflare Ray ID: 8a1b2c3d. " +
                        "The web server reported a bad gateway error.</body></html>"
                )
        )

        val serverUrl = mockWebServer.url("/").toString()
        val result = authRepository.login(serverUrl, "user", "pass")

        assertTrue(result.isFailure)
        assertFalse(
            "A Cloudflare-branded 502 outage page must NOT be classified as ProxyBlocked",
            result.exceptionOrNull() is PaperlessException.ProxyBlocked
        )
    }

    @Test
    fun `login with cf-mitigated header is a proxy block even without an HTML body`() = runTest {
        // Issue #27 (CodeRabbit): the cf-mitigated header is set ONLY on an active
        // Cloudflare challenge/block and is a definitive signal on its own — it must
        // trigger ProxyBlocked even when the body is JSON rather than an HTML page.
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("cf-mitigated", "challenge")
                .setHeader("Content-Type", "application/json")
                .setBody("""{"detail":"blocked"}""")
        )

        val serverUrl = mockWebServer.url("/").toString()
        val result = authRepository.login(serverUrl, "user", "pass")

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(
            "A cf-mitigated response must surface as ProxyBlocked",
            exception is PaperlessException.ProxyBlocked
        )
        assertEquals(403, (exception as PaperlessException.ProxyBlocked).code)
    }

    @Test
    fun `validateToken blocked by Cloudflare maps to ProxyBlocked`() = runTest {
        // Issue #27 (CodeRabbit): isEdgeProxyBlock() is reused by the token path,
        // so a WAF block during token validation must also surface as ProxyBlocked
        // (not an invalid-token / no-permission error).
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setHeader("cf-ray", "8a1b2c3d4e5f6789-FRA")
                .setHeader("Server", "cloudflare")
                .setHeader("Content-Type", "text/html; charset=UTF-8")
                .setBody(
                    "<!DOCTYPE html><html><head><title>Attention Required! | Cloudflare</title></head>" +
                        "<body>Sorry, you have been blocked.</body></html>"
                )
        )

        val serverUrl = mockWebServer.url("/").toString()
        val result = authRepository.validateToken(serverUrl, "some-token")

        assertTrue(result.isFailure)
        val exception = result.exceptionOrNull()
        assertTrue(
            "A WAF block during token validation must surface as ProxyBlocked",
            exception is PaperlessException.ProxyBlocked
        )
        assertEquals(403, (exception as PaperlessException.ProxyBlocked).code)
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

    @Test
    fun `logout swallows IOException from cache eviction`() = runTest {
        // CR PR #235 R1: evictAll() does blocking disk I/O and can throw
        // IOException (full disk, corrupt journal, etc.). Credentials are
        // already cleared at this point, so a disk hiccup must not surface
        // as a logout failure to the UI.
        coEvery { tokenManager.clearCredentials() } returns Unit
        every { httpCache.evictAll() } throws java.io.IOException("disk full")

        // Must not throw — assertion is the absence of an exception escaping.
        authRepository.logout()

        coVerify { tokenManager.clearCredentials() }
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
