package com.paperless.scanner.data.network

import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CertificateFingerprintTest {

    @Test
    fun `spkiPin is in OkHttp sha256 base64 form`() {
        val cert = HeldCertificate.Builder().build()

        val pin = CertificateFingerprint.spkiPin(cert.certificate)

        assertTrue("pin should start with 'sha256/' but was '$pin'", pin.startsWith("sha256/"))
    }

    @Test
    fun `same certificate yields the same pin`() {
        val cert = HeldCertificate.Builder().build()

        val first = CertificateFingerprint.spkiPin(cert.certificate)
        val second = CertificateFingerprint.spkiPin(cert.certificate)

        assertEquals(first, second)
    }

    @Test
    fun `renewal reusing the same key pair keeps the pin stable`() {
        // SPKI pinning must survive a legitimate renewal that reuses the key pair,
        // so a cert that only changed its validity window does NOT trip the dialog.
        val original = HeldCertificate.Builder()
            .commonName("paperless.lan")
            .build()
        val renewed = HeldCertificate.Builder()
            .commonName("paperless.lan")
            .keyPair(original.keyPair)
            .build()

        assertEquals(
            CertificateFingerprint.spkiPin(original.certificate),
            CertificateFingerprint.spkiPin(renewed.certificate)
        )
    }

    @Test
    fun `different key pair yields a different pin`() {
        val a = HeldCertificate.Builder().build()
        val b = HeldCertificate.Builder().build()

        assertNotEquals(
            CertificateFingerprint.spkiPin(a.certificate),
            CertificateFingerprint.spkiPin(b.certificate)
        )
    }
}
