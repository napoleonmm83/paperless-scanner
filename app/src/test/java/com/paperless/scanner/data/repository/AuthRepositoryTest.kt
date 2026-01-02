package com.paperless.scanner.data.repository

import com.paperless.scanner.data.datastore.TokenManager
import io.mockk.coEvery
import io.mockk.coVerify
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

class AuthRepositoryTest {

    private lateinit var mockWebServer: MockWebServer
    private lateinit var tokenManager: TokenManager
    private lateinit var authRepository: AuthRepository
    private lateinit var client: OkHttpClient

    @Before
    fun setup() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        tokenManager = mockk(relaxed = true)
        client = OkHttpClient.Builder().build()
        authRepository = AuthRepository(tokenManager, client)
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
        assertEquals(expectedToken, result.getOrNull())
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
        assertTrue(result.exceptionOrNull()?.message?.contains("401") == true)
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
