package com.paperless.scanner.data.health

import android.util.Log
import app.cash.turbine.test
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.ServerOfflineReason
import com.paperless.scanner.data.api.models.TagsResponse
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.data.network.NetworkMonitor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Unit Tests for ServerHealthMonitor.
 *
 * Tests verify:
 * - Correct health check results for different network conditions
 * - StateFlow updates (serverStatus, isServerReachable)
 * - Exception handling and mapping to ServerOfflineReason
 * - Auto-check on network reconnect
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerHealthMonitorTest {

    private lateinit var serverHealthMonitor: ServerHealthMonitor
    private lateinit var api: PaperlessApi
    private lateinit var tokenManager: TokenManager
    private lateinit var networkMonitor: NetworkMonitor

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var isOnlineFlow: MutableStateFlow<Boolean>

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // Mock android.util.Log
        mockkStatic(Log::class)
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.i(any(), any()) } returns 0

        // Mock dependencies
        api = mockk()
        tokenManager = mockk()
        networkMonitor = mockk()

        // Default mock for api.getTags to prevent uncaught exceptions from init{} block
        // The init{} block's Flow collector runs on Dispatchers.IO (async!) and can trigger
        // checkServerHealth() which calls api.getTags(). Without this default mock, tests
        // can have race conditions where async IO coroutines from previous tests throw exceptions.
        val defaultResponse = mockk<TagsResponse>(relaxed = true)
        coEvery { api.getTags(any(), any()) } returns defaultResponse

        // Setup NetworkMonitor - Create fresh flow for each test to prevent cross-test contamination
        // Start with offline to prevent auto health check in init
        isOnlineFlow = MutableStateFlow(false)
        every { networkMonitor.isOnline } returns isOnlineFlow
        every { networkMonitor.checkOnlineStatus() } returns true

        // Mock TokenManager.getServerUrlSync() for isPrivateNetworkUrl check in checkServerHealth
        every { tokenManager.getServerUrlSync() } returns "https://example.com"

        // Create ServerHealthMonitor
        serverHealthMonitor = ServerHealthMonitor(api, tokenManager, networkMonitor)
    }

    @After
    fun tearDown() {
        // Wait for async IO coroutines from ServerHealthMonitor.init{} to complete
        // The init{} block starts a Flow collector on Dispatchers.IO which can run
        // asynchronously between tests. Without this delay, uncaught exceptions from
        // previous tests can leak into subsequent tests.
        Thread.sleep(200)

        Dispatchers.resetMain()
        unmockkStatic(Log::class)
    }

    @Test
    fun `checkServerHealth returns Success when API responds successfully`() = runTest {
        // Given: API getTags succeeds
        val mockResponse = mockk<TagsResponse>(relaxed = true)
        coEvery { api.getTags(page = 1, pageSize = 1) } returns mockResponse

        // When: checkServerHealth is called
        val result = serverHealthMonitor.checkServerHealth()

        // Then: Result is Success
        assertEquals(ServerHealthResult.Success, result)

        // And: serverStatus is Online
        serverHealthMonitor.serverStatus.test {
            val status = awaitItem()
            assertTrue("Expected ServerStatus.Online", status is ServerStatus.Online)
        }
    }

    @Test
    fun `checkServerHealth returns NoInternet when network is offline`() = runTest {
        // Given: NetworkMonitor reports offline
        every { networkMonitor.checkOnlineStatus() } returns false

        // When: checkServerHealth is called
        val result = serverHealthMonitor.checkServerHealth()

        // Then: Result is NoInternet
        assertEquals(ServerHealthResult.NoInternet, result)

        // And: serverStatus is Offline with NO_INTERNET reason
        serverHealthMonitor.serverStatus.test {
            val status = awaitItem()
            assertTrue("Expected ServerStatus.Offline", status is ServerStatus.Offline)
            assertEquals(
                ServerOfflineReason.NO_INTERNET,
                (status as ServerStatus.Offline).reason
            )
        }

        // And: API was never called
        coVerify(exactly = 0) { api.getTags(any(), any()) }
    }

    @Test
    fun `checkServerHealth returns DnsFailure on UnknownHostException`() = runTest {
        // Given: API throws UnknownHostException (DNS failure)
        coEvery { api.getTags(page = 1, pageSize = 1) } throws UnknownHostException("example.com")

        // When: checkServerHealth is called
        val result = serverHealthMonitor.checkServerHealth()

        // Then: Result is DnsFailure
        assertEquals(ServerHealthResult.DnsFailure, result)

        // And: serverStatus is Offline with DNS_FAILURE reason
        serverHealthMonitor.serverStatus.test {
            val status = awaitItem()
            assertTrue("Expected ServerStatus.Offline", status is ServerStatus.Offline)
            assertEquals(
                ServerOfflineReason.DNS_FAILURE,
                (status as ServerStatus.Offline).reason
            )
        }
    }

    @Test
    fun `checkServerHealth returns ConnectionRefused on ConnectException`() = runTest {
        // Given: API throws ConnectException (connection refused)
        coEvery { api.getTags(page = 1, pageSize = 1) } throws ConnectException("Connection refused")

        // When: checkServerHealth is called
        val result = serverHealthMonitor.checkServerHealth()

        // Then: Result is ConnectionRefused
        assertEquals(ServerHealthResult.ConnectionRefused, result)

        // And: serverStatus is Offline with CONNECTION_REFUSED reason
        serverHealthMonitor.serverStatus.test {
            val status = awaitItem()
            assertTrue("Expected ServerStatus.Offline", status is ServerStatus.Offline)
            assertEquals(
                ServerOfflineReason.CONNECTION_REFUSED,
                (status as ServerStatus.Offline).reason
            )
        }
    }

    @Test
    fun `checkServerHealth returns Timeout on SocketTimeoutException`() = runTest {
        // Given: API throws SocketTimeoutException
        coEvery { api.getTags(page = 1, pageSize = 1) } throws SocketTimeoutException("timeout")

        // When: checkServerHealth is called
        val result = serverHealthMonitor.checkServerHealth()

        // Then: Result is Timeout
        assertEquals(ServerHealthResult.Timeout, result)

        // And: serverStatus is Offline with TIMEOUT reason
        serverHealthMonitor.serverStatus.test {
            val status = awaitItem()
            assertTrue("Expected ServerStatus.Offline", status is ServerStatus.Offline)
            assertEquals(
                ServerOfflineReason.TIMEOUT,
                (status as ServerStatus.Offline).reason
            )
        }
    }

    @Test
    fun `checkServerHealth treats HTTP 404 as server offline`() = runTest {
        // Given: API returns HTTP 404 (likely reverse proxy responding, but Paperless offline)
        val mockResponse = mockk<Response<TagsResponse>>()
        every { mockResponse.code() } returns 404
        every { mockResponse.message() } returns "Not Found"
        coEvery { api.getTags(page = 1, pageSize = 1) } throws HttpException(mockResponse)

        // When: checkServerHealth is called
        val result = serverHealthMonitor.checkServerHealth()

        // Then: Result is Error (server offline)
        assertTrue("Expected ServerHealthResult.Error", result is ServerHealthResult.Error)

        // And: serverStatus is Offline
        serverHealthMonitor.serverStatus.test {
            val status = awaitItem()
            assertTrue("Expected ServerStatus.Offline", status is ServerStatus.Offline)
            assertEquals(
                ServerOfflineReason.UNKNOWN,
                (status as ServerStatus.Offline).reason
            )
        }
    }

    @Test
    fun `checkServerHealth treats other HTTP errors as server online`() = runTest {
        // Given: API returns HTTP error (e.g., 401 Unauthorized)
        // HTTP errors (except 404) mean server IS reachable, just auth/permission issue
        val mockResponse = mockk<Response<TagsResponse>>()
        every { mockResponse.code() } returns 401
        every { mockResponse.message() } returns "Unauthorized"
        coEvery { api.getTags(page = 1, pageSize = 1) } throws HttpException(mockResponse)

        // When: checkServerHealth is called
        val result = serverHealthMonitor.checkServerHealth()

        // Then: Result is Success (server is reachable)
        assertEquals(ServerHealthResult.Success, result)

        // And: serverStatus is Online
        serverHealthMonitor.serverStatus.test {
            val status = awaitItem()
            assertTrue("Expected ServerStatus.Online", status is ServerStatus.Online)
        }
    }

    @Test
    fun `serverStatus StateFlow updates correctly`() = runTest {
        // Given: Initial state is Unknown
        serverHealthMonitor.serverStatus.test {
            val initialStatus = awaitItem()
            assertTrue("Initial status should be Unknown", initialStatus is ServerStatus.Unknown)

            // When: checkServerHealth succeeds
            val mockResponse = mockk<TagsResponse>(relaxed = true)
            coEvery { api.getTags(page = 1, pageSize = 1) } returns mockResponse
            serverHealthMonitor.checkServerHealth()
            advanceUntilIdle()

            // Then: Status updates to Online
            val onlineStatus = awaitItem()
            assertTrue("Status should be Online", onlineStatus is ServerStatus.Online)

            // When: checkServerHealth fails with DNS error
            coEvery { api.getTags(page = 1, pageSize = 1) } throws UnknownHostException("example.com")
            serverHealthMonitor.checkServerHealth()
            advanceUntilIdle()

            // Then: Status updates to Offline with DNS_FAILURE
            val offlineStatus = awaitItem()
            assertTrue("Status should be Offline", offlineStatus is ServerStatus.Offline)
            assertEquals(
                ServerOfflineReason.DNS_FAILURE,
                (offlineStatus as ServerStatus.Offline).reason
            )
        }
    }

    @Test
    fun `isServerReachable combines isOnline and serverStatus correctly`() = runTest {
        // Given: Network is online and server health check succeeds
        val mockResponse = mockk<TagsResponse>(relaxed = true)
        coEvery { api.getTags(page = 1, pageSize = 1) } returns mockResponse

        // Set network online for checkOnlineStatus
        every { networkMonitor.checkOnlineStatus() } returns true

        // Collect isServerReachable flow and verify state changes
        serverHealthMonitor.isServerReachable.test {
            // Initial state should be false (offline)
            assertFalse("Should start offline", awaitItem())

            // When: Network comes online (triggers init{} block auto health check)
            isOnlineFlow.value = true
            advanceUntilIdle()

            // Then: isServerReachable becomes true
            assertTrue("Server should be reachable after network online", awaitItem())

            // When: Network goes offline
            isOnlineFlow.value = false
            advanceUntilIdle()

            // Then: isServerReachable becomes false (even if serverStatus is Online)
            assertFalse("Server should NOT be reachable when offline", awaitItem())
        }
    }

    @Test
    fun `checkServerHealth handles unknown exceptions gracefully`() = runTest {
        // Given: API throws unknown exception
        coEvery { api.getTags(page = 1, pageSize = 1) } throws RuntimeException("Unknown error")

        // When: checkServerHealth is called
        val result = serverHealthMonitor.checkServerHealth()

        // Then: Result is Error with message
        assertTrue("Expected ServerHealthResult.Error", result is ServerHealthResult.Error)
        assertEquals("Unknown error", (result as ServerHealthResult.Error).message)

        // And: serverStatus is Offline with UNKNOWN reason
        serverHealthMonitor.serverStatus.test {
            val status = awaitItem()
            assertTrue("Expected ServerStatus.Offline", status is ServerStatus.Offline)
            assertEquals(
                ServerOfflineReason.UNKNOWN,
                (status as ServerStatus.Offline).reason
            )
        }
    }
}
