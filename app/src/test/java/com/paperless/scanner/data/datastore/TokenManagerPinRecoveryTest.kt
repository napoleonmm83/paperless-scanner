package com.paperless.scanner.data.datastore

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class TokenManagerPinRecoveryTest {
    @Test
    fun `recovery removes ssl exceptions and survives credential clearing`() = runTest {
        val storage = mockk<TokenStorage>(relaxed = true)
        every { storage.isMigrationCompleted() } returns true
        every { storage.consumeRecoveredCryptoFailure() } returns null
        val tokenManager = TokenManager(RuntimeEnvironment.getApplication(), storage)
        tokenManager.clearCredentials()
        tokenManager.acceptSslForHost("paperless.lan")

        tokenManager.beginPinRecovery()
        assertEquals("reset_pending", tokenManager.getPinRecoveryPhase())
        assertFalse(tokenManager.isHostAcceptedForSsl("paperless.lan"))

        tokenManager.finishPinRecovery()
        tokenManager.clearCredentials()
        assertEquals("manual_enrollment", tokenManager.getPinRecoveryPhase())
    }
}
