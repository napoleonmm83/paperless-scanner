package com.paperless.scanner.ui.navigation

import app.cash.turbine.test
import com.paperless.scanner.data.network.CertPinStorage
import com.paperless.scanner.data.network.CertificatePinStore
import com.paperless.scanner.data.network.ObservedCertHolder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * #249: app-wide in-session re-trust handler. Uses the real [CertificatePinStore]
 * over an in-memory [CertPinStorage] fake and the real [ObservedCertHolder] so the
 * re-trust behavior is assertable without the Android keystore. The pendingMismatch
 * StateFlow is asserted with Turbine (per .coderabbit.yaml).
 */
class CertReTrustViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    /** In-memory pin persistence so re-trust behavior is assertable without the keystore. */
    private class FakeCertPinStorage(
        private val map: MutableMap<String, String> = mutableMapOf()
    ) : CertPinStorage {
        override fun loadAll(): Map<String, String> = map.toMap()
        override fun put(host: String, pin: String) { map[host] = pin }
        override fun remove(host: String) { map.remove(host) }
        override fun clear() { map.clear() }
    }

    private lateinit var pinStore: CertificatePinStore
    private lateinit var holder: ObservedCertHolder

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        pinStore = CertificatePinStore(FakeCertPinStorage())
        holder = ObservedCertHolder()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = CertReTrustViewModel(pinStore, holder, testDispatcher)

    @Test
    fun `pendingMismatch emits the holder's recorded mismatch`() = runTest(testDispatcher) {
        val vm = viewModel()
        vm.pendingMismatch.test {
            assertNull(awaitItem())

            val m = ObservedCertHolder.Mismatch("paperless.lan", "sha256/OLD", "sha256/NEW")
            holder.record(m)

            assertEquals(m, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `acceptCertificateChange replaces the pin and clears the dialog`() = runTest(testDispatcher) {
        pinStore.replacePin("paperless.lan", "sha256/OLD")
        holder.record(ObservedCertHolder.Mismatch("paperless.lan", "sha256/OLD", "sha256/NEW"))
        val vm = viewModel()

        vm.acceptCertificateChange("paperless.lan", "sha256/NEW")
        advanceUntilIdle()

        assertEquals("sha256/NEW", pinStore.getPin("paperless.lan"))
        vm.pendingMismatch.test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `acceptCertificateChange ignores and preserves a cert newer than the one approved`() = runTest(testDispatcher) {
        pinStore.replacePin("paperless.lan", "sha256/OLD")
        holder.record(ObservedCertHolder.Mismatch("paperless.lan", "sha256/OLD", "sha256/SHOWN"))
        // A second failing request records a DIFFERENT cert before the user taps
        // re-trust (load balancer / MITM). The user only ever saw "sha256/SHOWN".
        val newer = ObservedCertHolder.Mismatch("paperless.lan", "sha256/OLD", "sha256/OTHER")
        holder.record(newer)
        val vm = viewModel()

        vm.acceptCertificateChange("paperless.lan", "sha256/SHOWN")
        advanceUntilIdle()

        // The unseen newer cert is NOT trusted...
        assertEquals("sha256/OLD", pinStore.getPin("paperless.lan"))
        // ...and the newer mismatch is preserved so the dialog re-prompts with it
        // instead of being silently dismissed.
        vm.pendingMismatch.test {
            assertEquals(newer, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `acceptCertificateChange without a live mismatch leaves the existing pin untouched`() = runTest(testDispatcher) {
        pinStore.replacePin("paperless.lan", "sha256/OLD")
        // No holder.record(): the live mismatch was already resolved/raced away.
        // The pin must NOT be dropped — that would let the next connection
        // TOFU-trust a different certificate than the user approved.
        val vm = viewModel()

        vm.acceptCertificateChange("paperless.lan", "sha256/NEW")
        advanceUntilIdle()

        assertEquals("sha256/OLD", pinStore.getPin("paperless.lan"))
    }

    @Test
    fun `declineCertificateChange dismisses without changing the pin`() = runTest(testDispatcher) {
        pinStore.replacePin("paperless.lan", "sha256/OLD")
        holder.record(ObservedCertHolder.Mismatch("paperless.lan", "sha256/OLD", "sha256/NEW"))
        val vm = viewModel()

        vm.declineCertificateChange("paperless.lan")

        assertEquals("sha256/OLD", pinStore.getPin("paperless.lan"))
        vm.pendingMismatch.test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}
