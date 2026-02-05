package com.paperless.scanner.data.repository

import android.content.Context
import com.paperless.scanner.R
import com.paperless.scanner.data.analytics.AuthDebugService
import com.paperless.scanner.data.analytics.CrashlyticsHelper
import com.paperless.scanner.data.api.CloudflareDetectionInterceptor
import com.paperless.scanner.data.datastore.TokenManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

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
    fun `login success returns token and saves credentials`() = runBlocking {
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
    fun `login with trailing slash normalizes url`() = runBlocking {
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
    fun `login failure returns error with status code`() = runBlocking {
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
    fun `login with empty token returns failure`() = runBlocking {
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
    fun `login with missing token field returns failure`() = runBlocking {
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
    fun `login with network error returns failure`() = runBlocking {
        mockWebServer.shutdown()

        val result = authRepository.login("http://localhost:9999", "user", "pass")

        assertTrue(result.isFailure)
    }

    @Test
    fun `logout clears credentials`() = runBlocking {
        coEvery { tokenManager.clearCredentials() } returns Unit

        authRepository.logout()

        coVerify { tokenManager.clearCredentials() }
    }
}
