package com.paperless.scanner.ui.screens.settings

import com.paperless.scanner.data.analytics.AnalyticsService
import com.paperless.scanner.data.datastore.TokenManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
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

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private lateinit var tokenManager: TokenManager
    private lateinit var analyticsService: AnalyticsService

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        tokenManager = mockk(relaxed = true)
        analyticsService = mockk(relaxed = true)

        // Default mock responses
        coEvery { tokenManager.serverUrl } returns flowOf("https://paperless.example.com")
        coEvery { tokenManager.token } returns flowOf("test-token")
        coEvery { tokenManager.uploadNotificationsEnabled } returns flowOf(true)
        coEvery { tokenManager.uploadQuality } returns flowOf("auto")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): SettingsViewModel {
        return SettingsViewModel(
            tokenManager = tokenManager,
            analyticsService = analyticsService
        )
    }

    // ==================== Initial State Tests ====================

    @Test
    fun `initial uiState has correct defaults`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("https://paperless.example.com", state.serverUrl)
        assertTrue(state.isConnected)
        assertTrue(state.showUploadNotifications)
        assertEquals(UploadQuality.AUTO, state.uploadQuality)
    }

    @Test
    fun `isConnected is false when no token`() = runTest {
        coEvery { tokenManager.token } returns flowOf(null)

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isConnected)
    }

    @Test
    fun `isConnected is false when token is blank`() = runTest {
        coEvery { tokenManager.token } returns flowOf("")

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isConnected)
    }

    @Test
    fun `serverUrl is empty when not configured`() = runTest {
        coEvery { tokenManager.serverUrl } returns flowOf(null)

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals("", viewModel.uiState.value.serverUrl)
    }

    // ==================== Upload Quality Tests ====================

    @Test
    fun `loadSettings parses upload quality correctly`() = runTest {
        coEvery { tokenManager.uploadQuality } returns flowOf("high")

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(UploadQuality.HIGH, viewModel.uiState.value.uploadQuality)
    }

    @Test
    fun `loadSettings defaults to AUTO for unknown quality`() = runTest {
        coEvery { tokenManager.uploadQuality } returns flowOf("unknown")

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertEquals(UploadQuality.AUTO, viewModel.uiState.value.uploadQuality)
    }

    @Test
    fun `setUploadQuality updates state and persists`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setUploadQuality(UploadQuality.HIGH)
        advanceUntilIdle()

        assertEquals(UploadQuality.HIGH, viewModel.uiState.value.uploadQuality)
        coVerify { tokenManager.setUploadQuality("high") }
    }

    @Test
    fun `setUploadQuality LOW persists correct key`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setUploadQuality(UploadQuality.LOW)
        advanceUntilIdle()

        coVerify { tokenManager.setUploadQuality("low") }
    }

    @Test
    fun `setUploadQuality MEDIUM persists correct key`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setUploadQuality(UploadQuality.MEDIUM)
        advanceUntilIdle()

        coVerify { tokenManager.setUploadQuality("medium") }
    }

    // ==================== Upload Notifications Tests ====================

    @Test
    fun `setShowUploadNotifications updates state and persists`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setShowUploadNotifications(false)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.showUploadNotifications)
        coVerify { tokenManager.setUploadNotificationsEnabled(false) }
    }

    @Test
    fun `setShowUploadNotifications enables notifications`() = runTest {
        coEvery { tokenManager.uploadNotificationsEnabled } returns flowOf(false)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.setShowUploadNotifications(true)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.showUploadNotifications)
        coVerify { tokenManager.setUploadNotificationsEnabled(true) }
    }

    // ==================== Logout Tests ====================

    @Test
    fun `logout clears credentials`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.logout()
        advanceUntilIdle()

        coVerify { tokenManager.clearCredentials() }
    }

    // ==================== UploadQuality Enum Tests ====================

    @Test
    fun `UploadQuality entries have correct keys`() {
        assertEquals("auto", UploadQuality.AUTO.key)
        assertEquals("low", UploadQuality.LOW.key)
        assertEquals("medium", UploadQuality.MEDIUM.key)
        assertEquals("high", UploadQuality.HIGH.key)
    }

    @Test
    fun `all quality options load correctly from stored key`() = runTest {
        UploadQuality.entries.forEach { quality ->
            coEvery { tokenManager.uploadQuality } returns flowOf(quality.key)

            val viewModel = createViewModel()
            advanceUntilIdle()

            assertEquals(quality, viewModel.uiState.value.uploadQuality)
        }
    }
}
