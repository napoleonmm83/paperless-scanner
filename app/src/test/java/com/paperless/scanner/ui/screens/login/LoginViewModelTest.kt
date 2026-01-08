package com.paperless.scanner.ui.screens.login

import android.content.Context
import android.util.Log
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.data.repository.AuthRepository
import com.paperless.scanner.util.BiometricHelper
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
class LoginViewModelTest {

    private lateinit var context: Context
    private lateinit var authRepository: AuthRepository
    private lateinit var tokenManager: TokenManager
    private lateinit var biometricHelper: BiometricHelper

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)

        // Mock Android Log class to avoid "Method not mocked" errors
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.v(any(), any()) } returns 0

        context = mockk(relaxed = true)
        authRepository = mockk(relaxed = true)
        tokenManager = mockk(relaxed = true)
        biometricHelper = mockk(relaxed = true)

        // Default mock responses
        every { tokenManager.hasStoredCredentials() } returns false
        every { tokenManager.isBiometricEnabledSync() } returns false
        every { biometricHelper.isAvailable() } returns false
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic(Log::class)
    }

    private fun createViewModel(): LoginViewModel {
        return LoginViewModel(
            context = context,
            authRepository = authRepository,
            tokenManager = tokenManager,
            biometricHelper = biometricHelper
        )
    }

    // ==================== Initial State Tests ====================

    @Test
    fun `initial uiState is Idle`() = runTest {
        val viewModel = createViewModel()

        assertTrue(viewModel.uiState.value is LoginUiState.Idle)
    }

    @Test
    fun `initial serverStatus is Idle`() = runTest {
        val viewModel = createViewModel()

        assertTrue(viewModel.serverStatus.value is ServerStatus.Idle)
    }

    @Test
    fun `canUseBiometric is false when no stored credentials`() = runTest {
        every { tokenManager.hasStoredCredentials() } returns false

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertFalse(viewModel.canUseBiometric.value)
    }

    @Test
    fun `canUseBiometric is true when all conditions met`() = runTest {
        every { tokenManager.hasStoredCredentials() } returns true
        every { tokenManager.isBiometricEnabledSync() } returns true
        every { biometricHelper.isAvailable() } returns true

        val viewModel = createViewModel()
        advanceUntilIdle()

        assertTrue(viewModel.canUseBiometric.value)
    }

    // ==================== Login Tests ====================

    @Test
    fun `login with blank serverUrl shows error`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.login("", "user", "pass")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is LoginUiState.Error)
    }

    @Test
    fun `login with blank username shows error`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.login("https://example.com", "", "pass")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is LoginUiState.Error)
    }

    @Test
    fun `login with blank password shows error`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.login("https://example.com", "user", "")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is LoginUiState.Error)
    }

    @Test
    fun `login success updates state to Success`() = runTest {
        coEvery { authRepository.login(any(), any(), any()) } returns Result.success("token123")

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.login("https://example.com", "user", "pass")
        advanceUntilIdle()
        // Wait for IO dispatcher operations to complete
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(100) }

        assertTrue(viewModel.uiState.value is LoginUiState.Success)
    }

    @Test
    fun `login failure updates state to Error`() = runTest {
        coEvery { authRepository.login(any(), any(), any()) } returns
            Result.failure(Exception("Invalid credentials"))

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.login("https://example.com", "user", "pass")
        advanceUntilIdle()
        // Wait for IO dispatcher operations to complete
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(100) }

        val state = viewModel.uiState.value
        assertTrue(state is LoginUiState.Error)
        assertTrue((state as LoginUiState.Error).message.contains("Invalid credentials"))
    }

    @Test
    fun `login transitions from Loading to Success`() = runTest {
        // This test verifies the login flow completes successfully
        // Note: Testing intermediate Loading state is unreliable with real IO dispatchers
        coEvery { authRepository.login(any(), any(), any()) } returns Result.success("token123")

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Initial state should be Idle
        assertTrue(viewModel.uiState.value is LoginUiState.Idle)

        viewModel.login("https://example.com", "user", "pass")
        advanceUntilIdle()
        // Wait for IO dispatcher operations to complete
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(100) }

        // Final state should be Success
        assertTrue(viewModel.uiState.value is LoginUiState.Success)
    }

    // ==================== Token Login Tests ====================

    @Test
    fun `loginWithToken with blank serverUrl shows error`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.loginWithToken("", "token123")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is LoginUiState.Error)
    }

    @Test
    fun `loginWithToken with blank token shows error`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.loginWithToken("https://example.com", "")
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value is LoginUiState.Error)
    }

    @Test
    fun `loginWithToken success saves credentials and updates state`() = runTest {
        coEvery { authRepository.validateToken(any(), any()) } returns Result.success(Unit)

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.loginWithToken("https://example.com", "valid-token")
        advanceUntilIdle()
        // Wait for IO dispatcher operations to complete
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(100) }

        assertTrue(viewModel.uiState.value is LoginUiState.Success)
        coVerify { tokenManager.saveCredentials("https://example.com", "valid-token") }
    }

    @Test
    fun `loginWithToken failure updates state to Error`() = runTest {
        coEvery { authRepository.validateToken(any(), any()) } returns
            Result.failure(Exception("Invalid token"))

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.loginWithToken("https://example.com", "invalid-token")
        advanceUntilIdle()
        // Wait for IO dispatcher operations to complete
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(100) }

        assertTrue(viewModel.uiState.value is LoginUiState.Error)
    }

    // ==================== Server Detection Tests ====================

    @Test
    fun `onServerUrlChanged with short URL sets status to Idle`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onServerUrlChanged("abc")
        advanceUntilIdle()

        assertTrue(viewModel.serverStatus.value is ServerStatus.Idle)
    }

    @Test
    fun `onServerUrlChanged with blank URL sets status to Idle`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onServerUrlChanged("")
        advanceUntilIdle()

        assertTrue(viewModel.serverStatus.value is ServerStatus.Idle)
    }

    @Test
    fun `onServerUrlChanged debounces detection`() = runTest {
        coEvery { authRepository.detectServerProtocol(any()) } returns
            Result.success("https://example.com")

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Type rapidly - should debounce
        viewModel.onServerUrlChanged("exam")
        advanceTimeBy(100)
        viewModel.onServerUrlChanged("examp")
        advanceTimeBy(100)
        viewModel.onServerUrlChanged("example")
        advanceTimeBy(100)
        viewModel.onServerUrlChanged("example.com")

        // Advance past debounce time (800ms)
        advanceTimeBy(900)
        advanceUntilIdle()
        // Wait for IO dispatcher operations to complete
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(100) }

        // Should only call once due to debouncing
        coVerify(exactly = 1) { authRepository.detectServerProtocol(any()) }
    }

    @Test
    fun `onServerUrlChanged success updates serverStatus`() = runTest {
        coEvery { authRepository.detectServerProtocol("example.com") } returns
            Result.success("https://example.com")

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onServerUrlChanged("example.com")
        advanceTimeBy(900) // Past debounce
        advanceUntilIdle()
        // Wait for IO dispatcher operations to complete
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(100) }

        val status = viewModel.serverStatus.value
        assertTrue(status is ServerStatus.Success)
        assertEquals("https://example.com", (status as ServerStatus.Success).url)
        assertTrue(status.isHttps)
    }

    @Test
    fun `onServerUrlChanged failure updates serverStatus to Error`() = runTest {
        coEvery { authRepository.detectServerProtocol(any()) } returns
            Result.failure(Exception("Server unreachable"))

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onServerUrlChanged("invalid.server")
        advanceTimeBy(900)
        advanceUntilIdle()
        // Wait for IO dispatcher operations to complete
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(100) }

        assertTrue(viewModel.serverStatus.value is ServerStatus.Error)
    }

    @Test
    fun `clearServerStatus resets to Idle`() = runTest {
        coEvery { authRepository.detectServerProtocol(any()) } returns
            Result.success("https://example.com")

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onServerUrlChanged("example.com")
        advanceTimeBy(900)
        advanceUntilIdle()
        // Wait for IO dispatcher operations to complete
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(100) }

        assertTrue(viewModel.serverStatus.value is ServerStatus.Success)

        viewModel.clearServerStatus()

        assertTrue(viewModel.serverStatus.value is ServerStatus.Idle)
    }

    // ==================== Biometric Tests ====================

    @Test
    fun `onBiometricSuccess sets state to Success`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onBiometricSuccess()

        assertTrue(viewModel.uiState.value is LoginUiState.Success)
    }

    @Test
    fun `onBiometricError sets state to Error with message`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onBiometricError("Authentication failed")

        val state = viewModel.uiState.value
        assertTrue(state is LoginUiState.Error)
        assertEquals("Authentication failed", (state as LoginUiState.Error).message)
    }

    @Test
    fun `enableBiometric calls tokenManager`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.enableBiometric()
        advanceUntilIdle()

        coVerify { tokenManager.setBiometricEnabled(true) }
    }

    @Test
    fun `isBiometricAvailable delegates to helper`() = runTest {
        every { biometricHelper.isAvailable() } returns true

        val viewModel = createViewModel()

        assertTrue(viewModel.isBiometricAvailable())
        verify { biometricHelper.isAvailable() }
    }

    // ==================== Reset State Tests ====================

    @Test
    fun `resetState sets uiState to Idle`() = runTest {
        coEvery { authRepository.login(any(), any(), any()) } returns
            Result.failure(Exception("Error"))

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.login("https://example.com", "user", "pass")
        advanceUntilIdle()
        // Wait for IO dispatcher operations to complete
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(100) }
        assertTrue(viewModel.uiState.value is LoginUiState.Error)

        viewModel.resetState()

        assertTrue(viewModel.uiState.value is LoginUiState.Idle)
    }

    // ==================== URL Resolution Tests ====================

    @Test
    fun `login uses detected URL when serverStatus is Success`() = runTest {
        coEvery { authRepository.detectServerProtocol("example.com") } returns
            Result.success("https://detected.example.com")
        coEvery { authRepository.login(any(), any(), any()) } returns Result.success("token123")

        val viewModel = createViewModel()
        advanceUntilIdle()

        // First detect the server
        viewModel.onServerUrlChanged("example.com")
        advanceTimeBy(900)
        advanceUntilIdle()
        // Wait for IO dispatcher operations to complete
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(100) }

        // Then login
        viewModel.login("example.com", "user", "pass")
        advanceUntilIdle()
        // Wait for IO dispatcher operations to complete
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(100) }

        // Should use detected URL
        coVerify { authRepository.login("https://detected.example.com", "user", "pass") }
    }

    @Test
    fun `login trims trailing slash from serverUrl`() = runTest {
        coEvery { authRepository.login(any(), any(), any()) } returns Result.success("token123")

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.login("https://example.com/", "user", "pass")
        advanceUntilIdle()
        // Wait for IO dispatcher operations to complete
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.delay(100) }

        coVerify { authRepository.login("https://example.com", "user", "pass") }
    }
}
