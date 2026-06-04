package com.paperless.scanner.ui.screens.login

import android.content.Context
import android.util.Log
import com.paperless.scanner.R
import com.paperless.scanner.data.analytics.AnalyticsService
import com.paperless.scanner.data.analytics.AuthDebugService
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.data.network.CertPinStorage
import com.paperless.scanner.data.network.CertificatePinStore
import com.paperless.scanner.data.network.ObservedCertHolder
import com.paperless.scanner.data.repository.AuthRepository
import com.paperless.scanner.util.BiometricHelper
import com.paperless.scanner.util.LoginRateLimiter
import com.paperless.scanner.util.LoginRateLimitState
import kotlinx.coroutines.flow.MutableStateFlow
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
    private lateinit var analyticsService: AnalyticsService
    private lateinit var loginRateLimiter: LoginRateLimiter
    private lateinit var authDebugService: AuthDebugService
    private lateinit var certificatePinStore: CertificatePinStore
    private lateinit var observedCertHolder: ObservedCertHolder

    /** In-memory pin persistence so re-trust behavior is assertable without the keystore. */
    private class FakeCertPinStorage(
        private val map: MutableMap<String, String> = mutableMapOf()
    ) : CertPinStorage {
        override fun loadAll(): Map<String, String> = map.toMap()
        override fun put(host: String, pin: String) { map[host] = pin }
        override fun remove(host: String) { map.remove(host) }
        override fun clear() { map.clear() }
    }

    // Issue: this test was the only ViewModel test using UnconfinedTestDispatcher.
    // Its eager execution (run-until-suspension) combined with the real-time
    // `runBlocking { delay(100) }` waits below let viewModelScope coroutines leak
    // their exceptions past the test boundary, surfacing as
    // `UncaughtExceptionsBeforeTest` in whichever test ran next (cross-test flake).
    // StandardTestDispatcher queues coroutines and only runs them on explicit
    // advanceUntilIdle()/advanceTimeBy(), matching every other ViewModel test and
    // keeping all coroutine work deterministically inside the test scope.
    private val testDispatcher = StandardTestDispatcher()

    /** Stubbed localized text for R.string.error_blocked_by_proxy (#27). */
    private val proxyBlockedMessage =
        "Request blocked by a proxy or firewall (not your login)."

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
        analyticsService = mockk(relaxed = true)
        loginRateLimiter = mockk(relaxed = true)
        authDebugService = mockk(relaxed = true)
        certificatePinStore = CertificatePinStore(FakeCertPinStorage())
        observedCertHolder = ObservedCertHolder()

        // Default mock responses
        every { tokenManager.hasStoredCredentials() } returns false
        every { tokenManager.isBiometricEnabledSync() } returns false
        every { biometricHelper.isAvailable() } returns false

        // Mock rate limiter to allow logins by default
        every { loginRateLimiter.isLoginAllowed() } returns true
        every { loginRateLimiter.rateLimitState } returns MutableStateFlow(LoginRateLimitState.Ready())

        // #27: ProxyBlocked is resolved to user-facing text in the UI layer via
        // getLocalizedMessage(messageResId), so stub the resource lookup.
        every { context.getString(R.string.error_blocked_by_proxy) } returns proxyBlockedMessage
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
            analyticsService = analyticsService,
            loginRateLimiter = loginRateLimiter,
            biometricHelper = biometricHelper,
            authDebugService = authDebugService,
            certificatePinStore = certificatePinStore,
            observedCertHolder = observedCertHolder,
            ioDispatcher = testDispatcher
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
        coEvery { authRepository.login(any(), any(), any()) } returns Result.success(AuthRepository.LoginResult.Success("token123"))

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.login("https://example.com", "user", "pass")
        advanceUntilIdle()

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

        val state = viewModel.uiState.value
        assertTrue(state is LoginUiState.Error)
        assertTrue((state as LoginUiState.Error).message.contains("Invalid credentials"))
    }

    @Test
    fun `login with certificate pin mismatch surfaces CertChanged state`() = runTest {
        coEvery { authRepository.login(any(), any(), any()) } returns
            Result.failure(
                PaperlessException.CertificatePinMismatch(
                    host = "paperless.lan",
                    expectedPin = "sha256/OLD",
                    actualPin = "sha256/NEW"
                )
            )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.login("https://paperless.lan", "user", "pass")
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("expected CertChanged but was $state", state is LoginUiState.CertChanged)
        state as LoginUiState.CertChanged
        assertEquals("paperless.lan", state.host)
        assertEquals("sha256/OLD", state.expectedPin)
        assertEquals("sha256/NEW", state.actualPin)
    }

    @Test
    fun `acceptCertificateChange replaces the pin with the observed certificate`() = runTest {
        certificatePinStore.replacePin("paperless.lan", "sha256/OLD")
        observedCertHolder.record(
            ObservedCertHolder.Mismatch("paperless.lan", "sha256/OLD", "sha256/NEW")
        )
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.acceptCertificateChange("paperless.lan")
        advanceUntilIdle()
        // Pin replacement runs on the injected (test) ioDispatcher.

        assertEquals("sha256/NEW", certificatePinStore.getPin("paperless.lan"))
        // Observed entry is consumed so a later success does not see a stale mismatch.
        assertTrue(observedCertHolder.peek("paperless.lan") == null)
    }

    @Test
    fun `acceptCertificateChange clears stale pin when no observed cert exists`() = runTest {
        certificatePinStore.replacePin("paperless.lan", "sha256/OLD")
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.acceptCertificateChange("paperless.lan")
        advanceUntilIdle()

        // No observed mismatch (e.g. process death) -> drop the pin for TOFU re-capture.
        assertTrue(certificatePinStore.getPin("paperless.lan") == null)
    }

    @Test
    fun `declineCertificateChange consumes the mismatch and leaves the pin unchanged`() = runTest {
        certificatePinStore.replacePin("paperless.lan", "sha256/OLD")
        observedCertHolder.record(
            ObservedCertHolder.Mismatch("paperless.lan", "sha256/OLD", "sha256/NEW")
        )
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.declineCertificateChange("paperless.lan")
        advanceUntilIdle()

        // Pin is NOT trusted (declined)...
        assertEquals("sha256/OLD", certificatePinStore.getPin("paperless.lan"))
        // ...and the mismatch is consumed so the app-wide re-trust dialog (#249) does
        // not re-surface after the user backs out of setup.
        assertTrue(observedCertHolder.peek("paperless.lan") == null)
        assertTrue(observedCertHolder.latest.value == null)
        assertTrue(viewModel.uiState.value is LoginUiState.Idle)
    }

    @Test
    fun `login transitions from Loading to Success`() = runTest {
        // This test verifies the login flow completes successfully
        // Note: Testing intermediate Loading state is unreliable with real IO dispatchers
        coEvery { authRepository.login(any(), any(), any()) } returns Result.success(AuthRepository.LoginResult.Success("token123"))

        val viewModel = createViewModel()
        advanceUntilIdle()

        // Initial state should be Idle
        assertTrue(viewModel.uiState.value is LoginUiState.Idle)

        viewModel.login("https://example.com", "user", "pass")
        advanceUntilIdle()

        // Final state should be Success
        assertTrue(viewModel.uiState.value is LoginUiState.Success)
    }

    // ============ Issue #27: Edge Proxy / WAF Block Tests ============

    @Test
    fun `login blocked by edge proxy is not rate-limited and shows the proxy message`() = runTest {
        // Issue #27: a Cloudflare/WAF block (ProxyBlocked) is NOT a credential
        // error — counting it would lock out a user whose password is correct but
        // who sits behind their own proxy. The repository returns the typed error
        // (code only); the message is resolved here in the UI via messageResId.
        coEvery { authRepository.login(any(), any(), any()) } returns
            Result.failure(PaperlessException.ProxyBlocked(code = 403))

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.login("https://example.com", "user", "pass")
        advanceUntilIdle()

        verify(exactly = 0) { loginRateLimiter.recordFailedAttempt() }
        val state = viewModel.uiState.value
        assertTrue(state is LoginUiState.Error)
        assertEquals(proxyBlockedMessage, (state as LoginUiState.Error).message)
    }

    @Test
    fun `loginWithToken blocked by edge proxy is not rate-limited and shows the proxy message`() = runTest {
        coEvery { authRepository.validateToken(any(), any()) } returns
            Result.failure(PaperlessException.ProxyBlocked(code = 403))

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.loginWithToken("https://example.com", "valid-token")
        advanceUntilIdle()

        verify(exactly = 0) { loginRateLimiter.recordFailedAttempt() }
        val state = viewModel.uiState.value
        assertTrue(state is LoginUiState.Error)
        assertEquals(proxyBlockedMessage, (state as LoginUiState.Error).message)
    }

    @Test
    fun `login with real auth error still counts toward rate limiter`() = runTest {
        // Guard the inverse: genuine credential errors must STILL be rate-limited
        // so the brute-force protection is unaffected by the #27 exclusion.
        coEvery { authRepository.login(any(), any(), any()) } returns
            Result.failure(PaperlessException.AuthError(code = 401))

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.login("https://example.com", "user", "wrongpass")
        advanceUntilIdle()

        verify(exactly = 1) { loginRateLimiter.recordFailedAttempt() }
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

        assertTrue(viewModel.uiState.value is LoginUiState.Error)
    }

    // ==================== Server Detection Tests ====================

    @Test
    fun `onServerUrlChanged with partial IP sets status to Idle`() = runTest {
        // Issue #233: parser-shape gate now silently stays on Idle for inputs
        // the parser rejects (partial IPs, trailing dots). Previously a
        // hardcoded length < 4 heuristic served this purpose but accepted
        // "192." as detection-ready.
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onServerUrlChanged("192.")
        advanceUntilIdle()

        assertTrue(viewModel.serverStatus.value is ServerStatus.Idle)
    }

    @Test
    fun `onServerUrlChanged with bare digit host sets status to Idle`() = runTest {
        // Single-label all-digit hostnames are rejected by the parser as of #233
        // because they are almost always a partial IPv4 the user is mid-typing.
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onServerUrlChanged("192")
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

        assertTrue(viewModel.serverStatus.value is ServerStatus.Error)
    }

    @Test
    fun `onServerUrlChanged with pin mismatch surfaces CertChanged via uiState`() = runTest {
        // Issue #36: a pin mismatch during detection must reach the blocking
        // re-trust dialog (uiState), not be swallowed as a generic serverStatus Error.
        coEvery { authRepository.detectServerProtocol(any()) } returns
            Result.failure(
                PaperlessException.CertificatePinMismatch(
                    host = "paperless.lan",
                    expectedPin = "sha256/OLD",
                    actualPin = "sha256/NEW"
                )
            )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onServerUrlChanged("https://paperless.lan")
        advanceTimeBy(900)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertTrue("expected CertChanged but was $state", state is LoginUiState.CertChanged)
        assertEquals("paperless.lan", (state as LoginUiState.CertChanged).host)
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
        assertTrue(viewModel.uiState.value is LoginUiState.Error)

        viewModel.resetState()

        assertTrue(viewModel.uiState.value is LoginUiState.Idle)
    }

    // ==================== URL Resolution Tests ====================

    @Test
    fun `login uses detected URL when serverStatus is Success`() = runTest {
        coEvery { authRepository.detectServerProtocol("example.com") } returns
            Result.success("https://detected.example.com")
        coEvery { authRepository.login(any(), any(), any()) } returns Result.success(AuthRepository.LoginResult.Success("token123"))

        val viewModel = createViewModel()
        advanceUntilIdle()

        // First detect the server
        viewModel.onServerUrlChanged("example.com")
        advanceTimeBy(900)
        advanceUntilIdle()

        // Then login
        viewModel.login("example.com", "user", "pass")
        advanceUntilIdle()

        // Should use detected URL
        coVerify { authRepository.login("https://detected.example.com", "user", "pass") }
    }

    @Test
    fun `login trims trailing slash from serverUrl`() = runTest {
        coEvery { authRepository.login(any(), any(), any()) } returns Result.success(AuthRepository.LoginResult.Success("token123"))

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.login("https://example.com/", "user", "pass")
        advanceUntilIdle()

        coVerify { authRepository.login("https://example.com", "user", "pass") }
    }

    // --- Issue #233: RequiresHttpAccept state ---

    @Test
    fun `onServerUrlChanged with explicit http scheme and unaccepted host yields RequiresHttpAccept USER_CHOSE_HTTP`() = runTest {
        every { tokenManager.isHostAcceptedForHttp("192.168.178.19") } returns false
        coEvery { authRepository.detectServerProtocol(any()) } returns Result.success("https://shouldnt-call.example")

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onServerUrlChanged("http://192.168.178.19")
        advanceTimeBy(900)
        advanceUntilIdle()

        val status = viewModel.serverStatus.value
        assertTrue("Expected RequiresHttpAccept, got $status", status is ServerStatus.RequiresHttpAccept)
        status as ServerStatus.RequiresHttpAccept
        assertEquals("192.168.178.19", status.host)
        assertEquals(ServerStatus.RequiresHttpAccept.Reason.USER_CHOSE_HTTP, status.reason)
        // Network probe must NOT have been attempted yet — the dialog precedes it.
        coVerify(exactly = 0) { authRepository.detectServerProtocol(any()) }
    }

    @Test
    fun `onServerUrlChanged with explicit http scheme and previously-accepted host proceeds to detection`() = runTest {
        every { tokenManager.isHostAcceptedForHttp("192.168.178.19") } returns true
        coEvery { authRepository.detectServerProtocol(any()) } returns Result.success("http://192.168.178.19")

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onServerUrlChanged("http://192.168.178.19")
        advanceTimeBy(900)
        advanceUntilIdle()

        coVerify { authRepository.detectServerProtocol(any()) }
    }

    @Test
    fun `onServerUrlChanged with explicit http to loopback proceeds without dialog`() = runTest {
        // localhost is in HARD_ALLOWED_HOSTS — dialog must not fire even
        // though the user has never accepted anything in tokenManager.
        every { tokenManager.isHostAcceptedForHttp(any()) } returns false
        coEvery { authRepository.detectServerProtocol(any()) } returns Result.success("http://localhost:8000")

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onServerUrlChanged("http://localhost:8000")
        advanceTimeBy(900)
        advanceUntilIdle()

        coVerify { authRepository.detectServerProtocol(any()) }
        // No dialog state — must transition to Success (or Checking briefly).
        val status = viewModel.serverStatus.value
        assertTrue(
            "Expected Success on loopback path, got $status",
            status is ServerStatus.Success
        )
    }

    @Test
    fun `detection failure with CleartextBlocked yields RequiresHttpAccept HTTPS_FAILED_HTTP_BLOCKED`() = runTest {
        coEvery { authRepository.detectServerProtocol(any()) } returns Result.failure(
            com.paperless.scanner.data.api.PaperlessException.CleartextBlocked("192.168.1.1")
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        // No explicit scheme — exercises the failure-path branch (not the
        // pre-detection gate).
        viewModel.onServerUrlChanged("192.168.1.1")
        advanceTimeBy(900)
        advanceUntilIdle()

        val status = viewModel.serverStatus.value
        assertTrue("Expected RequiresHttpAccept on CleartextBlocked, got $status", status is ServerStatus.RequiresHttpAccept)
        status as ServerStatus.RequiresHttpAccept
        assertEquals("192.168.1.1", status.host)
        assertEquals(ServerStatus.RequiresHttpAccept.Reason.HTTPS_FAILED_HTTP_BLOCKED, status.reason)
    }

    @Test
    fun `onHttpAcceptedForRequiresHttpAccept persists host and re-runs detection`() = runTest {
        // Mock first call: returns CleartextBlocked. Second call (after accept):
        // returns success. The viewModel call sequence should:
        //  1. acceptHttpForHost("192.168.1.1")
        //  2. delay(50) for cache propagation
        //  3. detectServerProtocol again
        every { tokenManager.isHostAcceptedForHttp("192.168.1.1") } returns false
        coEvery { authRepository.detectServerProtocol(any()) } returnsMany listOf(
            Result.failure(com.paperless.scanner.data.api.PaperlessException.CleartextBlocked("192.168.1.1")),
            Result.success("http://192.168.1.1")
        )

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.onServerUrlChanged("192.168.1.1")
        advanceTimeBy(900)
        advanceUntilIdle()

        // Confirm we landed on the dialog state first.
        assertTrue(viewModel.serverStatus.value is ServerStatus.RequiresHttpAccept)

        // Now accept.
        viewModel.onHttpAcceptedForRequiresHttpAccept("192.168.1.1", "192.168.1.1")
        advanceTimeBy(100) // Cover the 50ms delay inside the VM + slack
        advanceUntilIdle()

        coVerify { tokenManager.acceptHttpForHost("192.168.1.1") }
        coVerify(exactly = 2) { authRepository.detectServerProtocol(any()) }
    }
}
