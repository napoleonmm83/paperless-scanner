package com.paperless.scanner.util

import android.content.Context
import com.paperless.scanner.data.datastore.TokenManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mindrot.jbcrypt.BCrypt
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Unit tests for [AppLockManager].
 *
 * Uses Robolectric for the Application context (ProcessLifecycleOwner) and
 * mockk for [TokenManager]. BCrypt runs against real `org.mindrot.jbcrypt`
 * because it has no Android dependencies.
 *
 * Coverage focus (per #156 acceptance criteria):
 * - Setup / disable / change password
 * - Unlock with password (success / wrong / blocked while locked out)
 * - Unlock with biometric (works during temporary lockout)
 * - Failed-attempt counting + temporary lockout (every 5) + permanent lockout (15)
 * - Lockout-until enforcement, getRemainingLockoutSeconds, getRemainingAttempts
 * - lock() / refreshLockoutState transition out of LockedOut after expiry
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class AppLockManagerTest {

    private lateinit var context: Context
    private lateinit var tokenManager: TokenManager
    private lateinit var appLockEnabledFlow: MutableStateFlow<Boolean>

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        tokenManager = mockk(relaxed = true)
        appLockEnabledFlow = MutableStateFlow(false)

        // Default tokenManager state: no app-lock, no credentials, no lockout.
        every { tokenManager.getAppLockFailedAttemptsSync() } returns 0
        every { tokenManager.getAppLockLockoutUntilSync() } returns 0L
        every { tokenManager.hasStoredCredentials() } returns false
        every { tokenManager.isAppLockEnabledSync() } returns false
        every { tokenManager.isAppLockEnabled() } returns appLockEnabledFlow
        every { tokenManager.isAppLockBiometricEnabled() } returns false
        coEvery { tokenManager.getAppLockPasswordHash() } returns null
    }

    private fun newManager(): AppLockManager = AppLockManager(context, tokenManager)

    /**
     * For tests where the app should be locked from the start, both
     * `isAppLockEnabledSync()` AND the `isAppLockEnabled()` Flow must report true.
     * Otherwise the init-time Flow observer immediately resets the state to Unlocked.
     */
    private fun enableAppLock() {
        every { tokenManager.hasStoredCredentials() } returns true
        every { tokenManager.isAppLockEnabledSync() } returns true
        appLockEnabledFlow.value = true
    }

    // ==================== Initial State ====================

    @Test
    fun `initial state is Unlocked when app-lock not configured`() {
        val manager = newManager()
        assertTrue(manager.lockState.value is AppLockState.Unlocked)
    }

    @Test
    fun `initial state is Locked when credentials stored and app-lock enabled`() {
        enableAppLock()

        val manager = newManager()
        assertTrue(manager.lockState.value is AppLockState.Locked)
    }

    @Test
    fun `initial state is LockedOut when persisted lockout still active`() {
        enableAppLock()
        every { tokenManager.getAppLockFailedAttemptsSync() } returns 5
        every { tokenManager.getAppLockLockoutUntilSync() } returns System.currentTimeMillis() + 60_000L

        val manager = newManager()
        val state = manager.lockState.value
        assertTrue(state is AppLockState.LockedOut)
        assertFalse((state as AppLockState.LockedOut).isPermanent)
    }

    // ==================== Setup / Disable / Change ====================

    @Test
    fun `setupAppLock stores hashed password and resets lockout`() = runTest {
        val manager = newManager()
        val hashSlot = slot<String>()
        coEvery { tokenManager.setAppLockPassword(capture(hashSlot)) } returns Unit

        manager.setupAppLock("Sw0rdfish!")

        coVerify { tokenManager.setAppLockEnabled(true) }
        coVerify { tokenManager.clearAppLockLockoutState() }
        // Stored value is a real BCrypt hash that verifies against the input.
        assertTrue(BCrypt.checkpw("Sw0rdfish!", hashSlot.captured))
        assertEquals(0, manager.getFailedAttempts())
    }

    @Test
    fun `disableAppLock clears password and biometric flag`() = runTest {
        val manager = newManager()

        manager.disableAppLock()

        coVerify { tokenManager.setAppLockEnabled(false) }
        coVerify { tokenManager.setAppLockPassword(null) }
        coVerify { tokenManager.setAppLockBiometricEnabled(false) }
        assertEquals(0, manager.getFailedAttempts())
    }

    @Test
    fun `changePassword succeeds when current password matches`() = runTest {
        val oldHash = BCrypt.hashpw("oldpass", BCrypt.gensalt(4))
        coEvery { tokenManager.getAppLockPasswordHash() } returns oldHash

        val manager = newManager()
        val newHashSlot = slot<String>()
        coEvery { tokenManager.setAppLockPassword(capture(newHashSlot)) } returns Unit

        val result = manager.changePassword("oldpass", "newpass")

        assertTrue(result)
        assertTrue(BCrypt.checkpw("newpass", newHashSlot.captured))
    }

    @Test
    fun `changePassword fails and counts attempt when current password wrong`() = runTest {
        val storedHash = BCrypt.hashpw("rightpass", BCrypt.gensalt(4))
        coEvery { tokenManager.getAppLockPasswordHash() } returns storedHash

        val manager = newManager()
        val result = manager.changePassword("wrongpass", "newpass")

        assertFalse(result)
        assertEquals(1, manager.getFailedAttempts())
    }

    @Test
    fun `changePassword fails when no password stored`() = runTest {
        coEvery { tokenManager.getAppLockPasswordHash() } returns null

        val manager = newManager()

        assertFalse(manager.changePassword("any", "any"))
    }

    // ==================== Unlock with Password ====================

    @Test
    fun `unlockWithPassword returns true and transitions to Unlocked on correct password`() = runTest {
        val storedHash = BCrypt.hashpw("correct", BCrypt.gensalt(4))
        coEvery { tokenManager.getAppLockPasswordHash() } returns storedHash
        enableAppLock()

        val manager = newManager()
        val result = manager.unlockWithPassword("correct")

        assertTrue(result)
        assertTrue(manager.lockState.value is AppLockState.Unlocked)
        coVerify { tokenManager.clearAppLockLockoutState() }
    }

    @Test
    fun `unlockWithPassword returns false on wrong password and increments failed attempts`() = runTest {
        val storedHash = BCrypt.hashpw("correct", BCrypt.gensalt(4))
        coEvery { tokenManager.getAppLockPasswordHash() } returns storedHash

        val manager = newManager()
        val result = manager.unlockWithPassword("wrong")

        assertFalse(result)
        assertEquals(1, manager.getFailedAttempts())
    }

    @Test
    fun `unlockWithPassword returns false when no password configured`() = runTest {
        coEvery { tokenManager.getAppLockPasswordHash() } returns null

        val manager = newManager()
        assertFalse(manager.unlockWithPassword("anything"))
    }

    @Test
    fun `unlockWithPassword blocked while in temporary lockout`() = runTest {
        val storedHash = BCrypt.hashpw("correct", BCrypt.gensalt(4))
        coEvery { tokenManager.getAppLockPasswordHash() } returns storedHash
        enableAppLock()
        every { tokenManager.getAppLockFailedAttemptsSync() } returns 5
        every { tokenManager.getAppLockLockoutUntilSync() } returns System.currentTimeMillis() + 60_000L

        val manager = newManager()
        // Even with the correct password, we're rejected during the lockout window.
        val result = manager.unlockWithPassword("correct")

        assertFalse(result)
    }

    // ==================== Lockout Flow ====================

    @Test
    fun `5 wrong attempts trigger temporary lockout`() = runTest {
        val storedHash = BCrypt.hashpw("correct", BCrypt.gensalt(4))
        coEvery { tokenManager.getAppLockPasswordHash() } returns storedHash

        val manager = newManager()
        repeat(5) { manager.unlockWithPassword("wrong") }

        assertEquals(5, manager.getFailedAttempts())
        assertTrue(manager.isInTemporaryLockout())
        assertTrue(manager.lockState.value is AppLockState.LockedOut)
        coVerify { tokenManager.setAppLockLockoutState(5, any()) }
    }

    @Test
    fun `temporary lockout emits an AUDIT log line (issue #29)`() = runTest {
        ShadowLog.clear()
        val storedHash = BCrypt.hashpw("correct", BCrypt.gensalt(4))
        coEvery { tokenManager.getAppLockPasswordHash() } returns storedHash

        val manager = newManager()
        repeat(5) { manager.unlockWithPassword("wrong") }

        // Reaching the threshold must leave a security-audit trail (no PII / hashes).
        val auditLogs = ShadowLog.getLogsForTag("AppLockManager")
            .filter { it.msg.contains("[AUDIT]") }
        assertTrue(
            "Temporary-lockout threshold must emit an [AUDIT] WARN log",
            auditLogs.any { it.msg.contains("Temporary lockout") }
        )
    }

    @Test
    fun `15 wrong attempts trigger permanent lockout and clear credentials`() = runTest {
        val storedHash = BCrypt.hashpw("correct", BCrypt.gensalt(4))
        coEvery { tokenManager.getAppLockPasswordHash() } returns storedHash
        enableAppLock()
        // Attempts 5 and 10 set lockoutUntil to now+30min. We need to bypass the lockout
        // window so the 6th-15th attempts can actually run. Simulate by clearing
        // lockoutUntil between batches via real wall-clock — easier: stub past time
        // for the lockoutUntil to expire instantly between batches.
        // Trick: use System.currentTimeMillis offset in the past via getAppLockLockoutUntilSync? No,
        // lockoutUntil is in-memory after first lockout. So we can't easily skip ahead in time.
        // Workaround: set MAX_FAILED_ATTEMPTS to expire instantly is impossible.
        // Instead drive 15 by directly: each call to handleFailedAttempt is internal.
        // Realistic approach: after the 5th attempt we are in lockout — further attempts are blocked.
        // So permanent lockout via wrong-pw-only requires waiting 30min between batches.
        // Strategy: skip this scenario via real time, instead trigger via repeated changePassword
        // where wrong-old-password also calls handleFailedAttempt without checking lockout state.
        // Verify by inspection of the source: changePassword path calls handleFailedAttempt directly,
        // does NOT check isInTemporaryLockout(). So 15 wrong changePassword attempts reach the
        // permanent threshold.
        val manager = newManager()
        repeat(15) { manager.changePassword("wrong", "irrelevant") }

        // After the 15th attempt the permanent-lockout branch runs disableAppLock(),
        // which resets failedAttempts to 0 — so we cannot assert the counter here.
        // The observable side-effects are clearCredentials() + permanent LockedOut state.
        coVerify { tokenManager.clearCredentials() }
        val state = manager.lockState.value
        assertTrue(state is AppLockState.LockedOut)
        assertTrue((state as AppLockState.LockedOut).isPermanent)
    }

    @Test
    fun `getRemainingAttempts decreases with failed attempts`() = runTest {
        val storedHash = BCrypt.hashpw("correct", BCrypt.gensalt(4))
        coEvery { tokenManager.getAppLockPasswordHash() } returns storedHash

        val manager = newManager()
        assertEquals(5, manager.getRemainingAttempts())

        manager.unlockWithPassword("wrong")
        assertEquals(4, manager.getRemainingAttempts())

        manager.unlockWithPassword("wrong")
        assertEquals(3, manager.getRemainingAttempts())
    }

    @Test
    fun `getRemainingAttempts is zero while in temporary lockout`() {
        enableAppLock()
        every { tokenManager.getAppLockFailedAttemptsSync() } returns 5
        every { tokenManager.getAppLockLockoutUntilSync() } returns System.currentTimeMillis() + 60_000L

        val manager = newManager()
        assertEquals(0, manager.getRemainingAttempts())
    }

    @Test
    fun `getRemainingLockoutSeconds returns positive value during lockout`() {
        enableAppLock()
        every { tokenManager.getAppLockFailedAttemptsSync() } returns 5
        every { tokenManager.getAppLockLockoutUntilSync() } returns System.currentTimeMillis() + 60_000L

        val manager = newManager()
        assertTrue(manager.getRemainingLockoutSeconds() > 50)
    }

    @Test
    fun `getRemainingLockoutSeconds returns zero when no lockout`() {
        val manager = newManager()
        assertEquals(0, manager.getRemainingLockoutSeconds())
    }

    @Test
    fun `isInTemporaryLockout returns false after lockoutUntil expires`() {
        enableAppLock()
        every { tokenManager.getAppLockFailedAttemptsSync() } returns 5
        // Lockout already expired (1 second in the past).
        every { tokenManager.getAppLockLockoutUntilSync() } returns System.currentTimeMillis() - 1_000L

        val manager = newManager()
        assertFalse(manager.isInTemporaryLockout())
    }

    // ==================== Biometric ====================

    @Test
    fun `unlockWithBiometric transitions to Unlocked when biometric enabled`() = runTest {
        every { tokenManager.isAppLockBiometricEnabled() } returns true
        enableAppLock()

        val manager = newManager()
        manager.unlockWithBiometric()

        // unlockWithBiometric launches into the internal scope; give it a tick.
        Thread.sleep(50)

        assertTrue(manager.lockState.value is AppLockState.Unlocked)
        coVerify { tokenManager.clearAppLockLockoutState() }
    }

    @Test
    fun `unlockWithBiometric is a no-op when biometric disabled`() = runTest {
        every { tokenManager.isAppLockBiometricEnabled() } returns false
        enableAppLock()

        val manager = newManager()
        // Initial state is Locked — biometric should NOT change it because disabled.
        manager.unlockWithBiometric()
        Thread.sleep(50)

        assertTrue(manager.lockState.value is AppLockState.Locked)
    }

    @Test
    fun `biometric is rejected during temporary lockout (Issue #32)`() = runTest {
        // Issue #32 changed the contract: biometric MUST NOT bypass the
        // temporary PIN lockout, otherwise the 30-minute brute-force window
        // can be circumvented via a biometric-spoof flaw or a coerced caller.
        every { tokenManager.isAppLockBiometricEnabled() } returns true
        enableAppLock()
        every { tokenManager.getAppLockFailedAttemptsSync() } returns 5
        every { tokenManager.getAppLockLockoutUntilSync() } returns System.currentTimeMillis() + 60_000L

        val manager = newManager()
        assertTrue(manager.isInTemporaryLockout())
        assertTrue(manager.lockState.value is AppLockState.LockedOut)

        manager.unlockWithBiometric()
        Thread.sleep(50)

        // Biometric must NOT have unlocked the app — state stays LockedOut.
        // The state assertion is enough; no need to peek at collaborator calls.
        assertTrue(manager.lockState.value is AppLockState.LockedOut)
    }

    @Test
    fun `biometric throttle blocks within 1s window and clears after (Issue #32)`() = runTest {
        // Defense-in-depth against a caller spamming unlockWithBiometric.
        // Uses ShadowSystemClock to drive the clock deterministically rather
        // than relying on Robolectric's default elapsedRealtime() == 0
        // happening to round-trip through the throttle math.
        every { tokenManager.isAppLockBiometricEnabled() } returns true
        enableAppLock()

        val manager = newManager()
        assertTrue(manager.lockState.value is AppLockState.Locked)

        // Call 1: unlocks.
        manager.unlockWithBiometric()
        Thread.sleep(50)
        assertTrue(manager.lockState.value is AppLockState.Unlocked)

        // Re-lock; advance only 500 ms (still within the 1s throttle window).
        manager.lock()
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(500))

        // Call 2: must be throttled — state stays Locked.
        manager.unlockWithBiometric()
        Thread.sleep(50)
        assertTrue(manager.lockState.value is AppLockState.Locked)

        // Advance past the 1 s threshold.
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(550))

        // Call 3: throttle window has elapsed — unlocks.
        manager.unlockWithBiometric()
        Thread.sleep(50)
        assertTrue(manager.lockState.value is AppLockState.Unlocked)
    }

    // ==================== Lock + Refresh ====================

    @Test
    fun `lock transitions to Locked state`() {
        val manager = newManager()
        manager.lock()
        assertTrue(manager.lockState.value is AppLockState.Locked)
    }

    @Test
    fun `refreshLockoutState transitions LockedOut to Locked when expired`() = runTest {
        enableAppLock()
        every { tokenManager.getAppLockFailedAttemptsSync() } returns 5
        // Initial lockoutUntil is in the future so init enters LockedOut state.
        every { tokenManager.getAppLockLockoutUntilSync() } returns System.currentTimeMillis() + 100L

        val manager = newManager()
        assertTrue(manager.lockState.value is AppLockState.LockedOut)

        Thread.sleep(150) // wait past the 100ms lockout window
        manager.refreshLockoutState()
        Thread.sleep(50) // give scope.launch a tick

        assertTrue(manager.lockState.value is AppLockState.Locked)
    }
}
