package com.paperless.scanner.data.network

import com.paperless.scanner.data.datastore.TokenManager
import io.mockk.coEvery
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class PinRecoveryCoordinatorTest {
    private class Storage : CertPinStorage {
        var broken = true
        var resetFails = false
        val pins = mutableMapOf<String, String>()
        var onPut: (() -> Unit)? = null
        override fun loadAll(): Map<String, String> = if (broken) emptyMap() else pins.toMap()
        override fun hasLoadFailure(): Boolean = broken
        override fun put(host: String, pin: String) { onPut?.invoke(); pins[host] = pin }
        override fun remove(host: String) { pins.remove(host) }
        override fun clear() { pins.clear() }
        override fun resetCorruptedStore() {
            if (resetFails) throw IOException("reset failed")
            pins.clear()
            broken = false
        }
    }

    @Test
    fun `reset arms durable gate before clearing pins and then requires manual enrollment`() = runTest {
        val storage = Storage()
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.getPinRecoveryPhase() } returns "none"
        coEvery { tokenManager.beginPinRecovery() } coAnswers {
            assertTrue(storage.broken)
        }
        coEvery { tokenManager.finishPinRecovery() } coAnswers {
            assertFalse(storage.broken)
        }
        val holder = ObservedCertHolder()
        val store = CertificatePinStore(storage)
        val coordinator = PinRecoveryCoordinator(tokenManager, store, holder)

        coordinator.resetCorruptedPins()

        assertEquals(PinRecoveryPhase.MANUAL_ENROLLMENT, coordinator.phase.value)
        coVerifyOrder {
            tokenManager.beginPinRecovery()
            tokenManager.finishPinRecovery()
        }
        assertThrows(CertificateFirstTrustRequiredException::class.java) {
            coordinator.requireNetworkAccess("paperless.lan", null, "sha256/NEW")
        }
        assertEquals("sha256/NEW", holder.firstTrust.value?.presentedPin)
        assertTrue(coordinator.confirmFirstTrust("paperless.lan", "sha256/NEW"))
        assertEquals("sha256/NEW", store.getPin("paperless.lan"))
    }

    @Test
    fun `failed reset remains closed and cannot approve a fingerprint`() = runTest {
        val storage = Storage().apply { resetFails = true }
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.getPinRecoveryPhase() } returns "none"
        coEvery { tokenManager.beginPinRecovery() } returns Unit
        val store = CertificatePinStore(storage)
        val coordinator = PinRecoveryCoordinator(tokenManager, store, ObservedCertHolder())

        assertThrows(IOException::class.java) {
            kotlinx.coroutines.runBlocking { coordinator.resetCorruptedPins() }
        }
        assertEquals(PinRecoveryPhase.RESET_PENDING, coordinator.phase.value)
        assertTrue(store.recoveryRequired.value)
        assertThrows(CertificatePinRecoveryRequiredException::class.java) {
            coordinator.requireNetworkAccess("paperless.lan", null, "sha256/NEW")
        }
        assertFalse(coordinator.confirmFirstTrust("paperless.lan", "sha256/NEW"))
    }

    @Test
    fun `confirmation cannot race with a newly observed fingerprint`() {
        val storage = Storage().apply { broken = false }
        val enteredPut = CountDownLatch(1)
        val releasePut = CountDownLatch(1)
        storage.onPut = {
            enteredPut.countDown()
            assertTrue(releasePut.await(5, TimeUnit.SECONDS))
        }
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.getPinRecoveryPhase() } returns "manual_enrollment"
        val holder = ObservedCertHolder()
        val coordinator = PinRecoveryCoordinator(tokenManager, CertificatePinStore(storage), holder)
        assertThrows(CertificateFirstTrustRequiredException::class.java) {
            coordinator.requireNetworkAccess("paperless.lan", null, "sha256/OLD")
        }
        val executor = Executors.newFixedThreadPool(2)
        try {
            val confirmation = executor.submit<Boolean> {
                coordinator.confirmFirstTrust("paperless.lan", "sha256/OLD")
            }
            assertTrue(enteredPut.await(5, TimeUnit.SECONDS))
            val contenderStarted = CountDownLatch(1)
            val contender = executor.submit {
                contenderStarted.countDown()
                coordinator.requireNetworkAccess("paperless.lan", null, "sha256/NEW")
            }
            assertTrue(contenderStarted.await(5, TimeUnit.SECONDS))
            Thread.sleep(100)
            releasePut.countDown()
            assertTrue(confirmation.get(5, TimeUnit.SECONDS))
            contender.get(5, TimeUnit.SECONDS)
            assertEquals("sha256/OLD", storage.pins["paperless.lan"])
            assertEquals(null, holder.firstTrust.value)
        } finally {
            releasePut.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `pending reset after restart blocks even if pin storage now opens empty`() {
        val storage = Storage().apply { broken = false }
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.getPinRecoveryPhase() } returns "reset_pending"
        val coordinator = PinRecoveryCoordinator(tokenManager, CertificatePinStore(storage), ObservedCertHolder())

        assertThrows(CertificatePinRecoveryRequiredException::class.java) {
            coordinator.requireNetworkAccess("paperless.lan", null, "sha256/NEW")
        }
    }
}
