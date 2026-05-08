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
import kotlinx.coroutines.test.runTest
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
        client = OkHttpClient.Builder().build()
        authRepository = AuthRepository(context, tokenManager, client, cloudflareDetectionInterceptor, crashlyticsHelper, authDebugService)
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
}
