package com.paperless.scanner.data.datastore

import android.content.Context
import com.paperless.scanner.util.AppLockTimeout
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit tests for [TokenManager].
 *
 * Uses Robolectric for the Android Context so that DataStore can use a real
 * (Robolectric-managed) preferences file. SecureTokenStorage is mocked because
 * it depends on the Android Keystore which is not available in unit tests.
 *
 * Coverage focus (per #155 acceptance criteria):
 * - Credential save/clear (token via mocked secure storage, serverUrl via DataStore)
 * - SSL host accept/remove/list
 * - App-lock state: enabled, biometric, timeout, lockout failed-attempts/until
 * - Representative DataStore preference flows (theme, AI flags, Paperless-GPT URL)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class TokenManagerTest {

    private lateinit var context: Context
    private lateinit var secureStorage: TokenStorage
    private lateinit var tokenManager: TokenManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        secureStorage = mockk(relaxed = true)
        // Default: no token in secure storage, migration already complete.
        every { secureStorage.getToken() } returns null
        every { secureStorage.isMigrationCompleted() } returns true
        every { secureStorage.saveToken(any()) } returns true
        every { secureStorage.clearToken() } returns true
        tokenManager = TokenManager(context, secureStorage)
    }

    @After
    fun tearDown() {
        // Reset the singleton DataStore between tests — Robolectric reuses the
        // application Context across the same VM, so preferences would otherwise leak.
        runBlocking { tokenManager.clearCredentials() }
    }

    // ==================== Credentials ====================

    @Test
    fun `saveCredentials persists serverUrl and saves token via secure storage`() = runTest {
        tokenManager.saveCredentials(serverUrl = "https://paperless.example.com", token = "abc123")

        verify { secureStorage.saveToken("abc123") }
        assertEquals("https://paperless.example.com", tokenManager.serverUrl.first())
    }

    @Test
    fun `saveCredentials trims trailing slash from serverUrl`() = runTest {
        tokenManager.saveCredentials(serverUrl = "https://paperless.example.com/", token = "tkn")

        assertEquals("https://paperless.example.com", tokenManager.serverUrl.first())
    }

    @Test
    fun `clearCredentials clears secure storage token and DataStore preferences`() = runTest {
        tokenManager.saveCredentials("https://x.example.com", "tok")
        tokenManager.setUploadQuality("high")

        tokenManager.clearCredentials()

        verify { secureStorage.clearToken() }
        assertNull(tokenManager.serverUrl.first())
        // Default returned because preference was cleared.
        assertEquals("auto", tokenManager.uploadQuality.first())
    }

    @Test
    fun `getTokenSync delegates to secure storage`() {
        every { secureStorage.getToken() } returns "synctoken"

        assertEquals("synctoken", tokenManager.getTokenSync())
    }

    @Test
    fun `hasStoredCredentials true when token and serverUrl present`() = runTest {
        every { secureStorage.getToken() } returns "tok"
        tokenManager.saveCredentials("https://x.example.com", "tok")

        assertTrue(tokenManager.hasStoredCredentials())
    }

    @Test
    fun `hasStoredCredentials false when token missing`() = runTest {
        every { secureStorage.getToken() } returns null
        tokenManager.saveCredentials("https://x.example.com", "tok")

        assertFalse(tokenManager.hasStoredCredentials())
    }

    @Test
    fun `hasStoredCredentials false when serverUrl missing`() {
        every { secureStorage.getToken() } returns "tok"
        // No serverUrl saved.

        assertFalse(tokenManager.hasStoredCredentials())
    }

    // ==================== SSL Hosts ====================

    @Test
    fun `acceptSslForHost adds host and isHostAcceptedForSsl returns true`() = runTest {
        tokenManager.acceptSslForHost("self-signed.example.com")

        assertTrue(tokenManager.isHostAcceptedForSsl("self-signed.example.com"))
        assertFalse(tokenManager.isHostAcceptedForSsl("other.example.com"))
    }

    @Test
    fun `acceptSslForHost is idempotent`() = runTest {
        tokenManager.acceptSslForHost("a.example.com")
        tokenManager.acceptSslForHost("a.example.com")

        assertEquals(listOf("a.example.com"), tokenManager.getAcceptedSslHosts())
    }

    @Test
    fun `removeAcceptedSslHost removes only the named host`() = runTest {
        tokenManager.acceptSslForHost("a.example.com")
        tokenManager.acceptSslForHost("b.example.com")

        tokenManager.removeAcceptedSslHost("a.example.com")

        assertEquals(listOf("b.example.com"), tokenManager.getAcceptedSslHosts())
    }

    @Test
    fun `getAcceptedSslHosts returns empty list when none stored`() {
        assertEquals(emptyList<String>(), tokenManager.getAcceptedSslHosts())
    }

    // ==================== HTTP Hosts (cleartext allowlist) ====================

    @Test
    fun `acceptHttpForHost adds host and isHostAcceptedForHttp returns true`() = runTest {
        tokenManager.acceptHttpForHost("paperless.lan")

        assertTrue(tokenManager.isHostAcceptedForHttp("paperless.lan"))
        assertFalse(tokenManager.isHostAcceptedForHttp("other.lan"))
    }

    @Test
    fun `acceptHttpForHost is idempotent and case-insensitive`() = runTest {
        tokenManager.acceptHttpForHost("paperless.lan")
        tokenManager.acceptHttpForHost("Paperless.LAN")

        assertEquals(listOf("paperless.lan"), tokenManager.getAcceptedHttpHosts())
    }

    @Test
    fun `removeAcceptedHttpHost removes only the named host`() = runTest {
        tokenManager.acceptHttpForHost("a.lan")
        tokenManager.acceptHttpForHost("b.lan")

        tokenManager.removeAcceptedHttpHost("a.lan")

        assertEquals(listOf("b.lan"), tokenManager.getAcceptedHttpHosts())
    }

    // Issue #222: a Unicode (IDN) host must be persisted in its ASCII/Punycode
    // form so it matches OkHttp's `url.host` in the interceptor; otherwise the
    // accepted host is silently denied.
    @Test
    fun `acceptHttpForHost normalizes an IDN host to its ASCII form`() = runTest {
        val ascii = java.net.IDN.toASCII("päperless.lan")

        tokenManager.acceptHttpForHost("päperless.lan")

        assertEquals(listOf(ascii), tokenManager.getAcceptedHttpHosts())
        // Lookup by either the Unicode or the ASCII spelling resolves to the same entry.
        assertTrue(tokenManager.isHostAcceptedForHttp("päperless.lan"))
        assertTrue(tokenManager.isHostAcceptedForHttp(ascii))
    }

    // Issue #222 (CodeRabbit follow-up): a bracketed IPv6 literal must be stored
    // in OkHttp's bare url.host form so the interceptor comparison matches.
    @Test
    fun `acceptHttpForHost strips brackets from an IPv6 literal`() = runTest {
        tokenManager.acceptHttpForHost("[2001:db8::1]")

        assertEquals(listOf("2001:db8::1"), tokenManager.getAcceptedHttpHosts())
        assertTrue(tokenManager.isHostAcceptedForHttp("2001:db8::1"))
    }

    @Test
    fun `removeAcceptedHttpHost matches an IDN host by either spelling`() = runTest {
        tokenManager.acceptHttpForHost("päperless.lan")

        tokenManager.removeAcceptedHttpHost("päperless.lan")

        assertEquals(emptyList<String>(), tokenManager.getAcceptedHttpHosts())
    }

    // ==================== App-Lock ====================

    @Test
    fun `setAppLockEnabled persists and isAppLockEnabledSync reflects it`() = runTest {
        assertFalse(tokenManager.isAppLockEnabledSync())

        tokenManager.setAppLockEnabled(true)
        assertTrue(tokenManager.isAppLockEnabledSync())

        tokenManager.setAppLockEnabled(false)
        assertFalse(tokenManager.isAppLockEnabledSync())
    }

    @Test
    fun `setAppLockPassword null clears the stored hash`() = runTest {
        tokenManager.setAppLockPassword("hash-abc")
        assertEquals("hash-abc", tokenManager.getAppLockPasswordHash())

        tokenManager.setAppLockPassword(null)
        assertNull(tokenManager.getAppLockPasswordHash())
    }

    @Test
    fun `setAppLockBiometricEnabled persists`() = runTest {
        assertFalse(tokenManager.isAppLockBiometricEnabled())

        tokenManager.setAppLockBiometricEnabled(true)
        assertTrue(tokenManager.isAppLockBiometricEnabled())
    }

    @Test
    fun `getAppLockTimeout returns IMMEDIATE when not set`() = runTest {
        assertEquals(AppLockTimeout.IMMEDIATE, tokenManager.getAppLockTimeout())
    }

    @Test
    fun `setAppLockTimeout persists across reads`() = runTest {
        tokenManager.setAppLockTimeout(AppLockTimeout.FIVE_MINUTES)

        assertEquals(AppLockTimeout.FIVE_MINUTES, tokenManager.getAppLockTimeout())
    }

    @Test
    fun `setAppLockLockoutState persists failed attempts and lockout until`() = runTest {
        tokenManager.setAppLockLockoutState(failedAttempts = 3, lockoutUntil = 1_700_000_000_000L)

        assertEquals(3, tokenManager.getAppLockFailedAttemptsSync())
        assertEquals(1_700_000_000_000L, tokenManager.getAppLockLockoutUntilSync())
    }

    @Test
    fun `clearAppLockLockoutState resets to zero`() = runTest {
        tokenManager.setAppLockLockoutState(failedAttempts = 5, lockoutUntil = 9_999L)

        tokenManager.clearAppLockLockoutState()

        assertEquals(0, tokenManager.getAppLockFailedAttemptsSync())
        assertEquals(0L, tokenManager.getAppLockLockoutUntilSync())
    }

    // ==================== Theme ====================

    @Test
    fun `getThemeModeSync defaults to system when not set`() {
        assertEquals("system", tokenManager.getThemeModeSync())
    }

    @Test
    fun `setThemeMode persists`() = runTest {
        tokenManager.setThemeMode("dark")

        assertEquals("dark", tokenManager.getThemeModeSync())
    }

    // ==================== AI Flags (representative) ====================

    @Test
    fun `aiSuggestionsEnabled defaults to true`() = runTest {
        assertTrue(tokenManager.getAiSuggestionsEnabledSync())
        assertTrue(tokenManager.aiSuggestionsEnabled.first())
    }

    @Test
    fun `setAiSuggestionsEnabled persists across reads`() = runTest {
        tokenManager.setAiSuggestionsEnabled(false)

        assertFalse(tokenManager.getAiSuggestionsEnabledSync())
    }

    @Test
    fun `aiNewTagsEnabled defaults to true and persists changes`() = runTest {
        assertTrue(tokenManager.getAiNewTagsEnabledSync())

        tokenManager.setAiNewTagsEnabled(false)
        assertFalse(tokenManager.getAiNewTagsEnabledSync())
    }

    // ==================== Paperless-GPT ====================

    @Test
    fun `setPaperlessGptUrl trims trailing slash`() = runTest {
        tokenManager.setPaperlessGptUrl("https://gpt.example.com/")

        assertEquals("https://gpt.example.com", tokenManager.getPaperlessGptUrlSync())
    }

    @Test
    fun `setPaperlessGptUrl null clears the value`() = runTest {
        tokenManager.setPaperlessGptUrl("https://gpt.example.com")
        tokenManager.setPaperlessGptUrl(null)

        assertNull(tokenManager.getPaperlessGptUrlSync())
    }

    @Test
    fun `setPaperlessGptUrl blank clears the value`() = runTest {
        tokenManager.setPaperlessGptUrl("https://gpt.example.com")
        tokenManager.setPaperlessGptUrl("   ")

        assertNull(tokenManager.getPaperlessGptUrlSync())
    }

    // ==================== Upload Notifications + Quality ====================

    @Test
    fun `uploadNotificationsEnabled defaults to true and toggles`() = runTest {
        assertTrue(tokenManager.uploadNotificationsEnabled.first())

        tokenManager.setUploadNotificationsEnabled(false)
        assertFalse(tokenManager.uploadNotificationsEnabled.first())
    }

    @Test
    fun `uploadQuality defaults to auto and persists set value`() = runTest {
        assertEquals("auto", tokenManager.uploadQuality.first())

        tokenManager.setUploadQuality("low")
        assertEquals("low", tokenManager.uploadQuality.first())
    }
}
