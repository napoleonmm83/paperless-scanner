package com.paperless.scanner.data.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CertificatePinStoreTest {

    /** In-memory [CertPinStorage] so the store's TOFU/replace logic is testable
     *  without the Android Keystore. */
    private class FakeCertPinStorage(
        private val initial: MutableMap<String, String> = mutableMapOf()
    ) : CertPinStorage {
        val persisted: MutableMap<String, String> = initial
        override fun loadAll(): Map<String, String> = persisted.toMap()
        override fun put(host: String, pin: String) { persisted[host] = pin }
        override fun remove(host: String) { persisted.remove(host) }
        override fun clear() { persisted.clear() }
    }

    @Test
    fun `getPin returns null when no pin captured`() {
        val store = CertificatePinStore(FakeCertPinStorage())

        assertNull(store.getPin("paperless.lan"))
    }

    @Test
    fun `setPinIfAbsent captures pin on first call and returns true`() {
        val store = CertificatePinStore(FakeCertPinStorage())

        val captured = store.setPinIfAbsent("paperless.lan", "sha256/AAA")

        assertTrue(captured)
        assertEquals("sha256/AAA", store.getPin("paperless.lan"))
    }

    @Test
    fun `setPinIfAbsent does not overwrite an existing pin`() {
        val store = CertificatePinStore(FakeCertPinStorage())
        store.setPinIfAbsent("paperless.lan", "sha256/AAA")

        val captured = store.setPinIfAbsent("paperless.lan", "sha256/BBB")

        assertFalse(captured)
        assertEquals("sha256/AAA", store.getPin("paperless.lan"))
    }

    @Test
    fun `replacePin overwrites an existing pin`() {
        val store = CertificatePinStore(FakeCertPinStorage())
        store.setPinIfAbsent("paperless.lan", "sha256/AAA")

        store.replacePin("paperless.lan", "sha256/BBB")

        assertEquals("sha256/BBB", store.getPin("paperless.lan"))
    }

    @Test
    fun `host keys are normalized to lowercase`() {
        val store = CertificatePinStore(FakeCertPinStorage())

        store.setPinIfAbsent("Paperless.LAN", "sha256/AAA")

        assertEquals("sha256/AAA", store.getPin("paperless.lan"))
        // A differently-cased lookup of the same host hits the same pin, so a
        // second TOFU capture is correctly suppressed.
        assertFalse(store.setPinIfAbsent("PAPERLESS.lan", "sha256/BBB"))
    }

    @Test
    fun `removePin forgets the pin`() {
        val store = CertificatePinStore(FakeCertPinStorage())
        store.setPinIfAbsent("paperless.lan", "sha256/AAA")

        store.removePin("paperless.lan")

        assertNull(store.getPin("paperless.lan"))
    }

    @Test
    fun `pins are loaded from storage on construction`() {
        val storage = FakeCertPinStorage(mutableMapOf("paperless.lan" to "sha256/AAA"))

        val store = CertificatePinStore(storage)

        assertEquals("sha256/AAA", store.getPin("paperless.lan"))
    }

    @Test
    fun `writes are mirrored to persistent storage`() {
        val storage = FakeCertPinStorage()
        val store = CertificatePinStore(storage)

        store.setPinIfAbsent("paperless.lan", "sha256/AAA")
        store.replacePin("other.lan", "sha256/CCC")

        assertEquals("sha256/AAA", storage.persisted["paperless.lan"])
        assertEquals("sha256/CCC", storage.persisted["other.lan"])
    }
}
