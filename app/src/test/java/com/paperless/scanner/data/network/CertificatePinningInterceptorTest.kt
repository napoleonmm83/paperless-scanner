package com.paperless.scanner.data.network

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress

class CertificatePinningInterceptorTest {

    private class FakeCertPinStorage(
        private val map: MutableMap<String, String> = mutableMapOf()
    ) : CertPinStorage {
        override fun loadAll(): Map<String, String> = map.toMap()
        override fun put(host: String, pin: String) { map[host] = pin }
        override fun remove(host: String) { map.remove(host) }
        override fun clear() { map.clear() }
    }

    private lateinit var server: MockWebServer
    private lateinit var pinStore: CertificatePinStore
    private lateinit var observed: ObservedCertHolder
    private lateinit var serverCert: HeldCertificate
    private lateinit var clientCertificates: HandshakeCertificates

    @Before
    fun setUp() {
        server = MockWebServer()
        pinStore = CertificatePinStore(FakeCertPinStorage())
        observed = ObservedCertHolder()

        val localhost = InetAddress.getByName("localhost").canonicalHostName
        serverCert = HeldCertificate.Builder()
            .addSubjectAlternativeName(localhost)
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(serverCert)
            .build()
        clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(serverCert.certificate)
            .build()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun httpsClient(): OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
        .addNetworkInterceptor(CertificatePinningInterceptor(pinStore, observed))
        .build()

    private fun call(client: OkHttpClient) {
        client.newCall(Request.Builder().url(server.url("/")).build()).execute().close()
    }

    @Test
    fun `first contact captures the pin via TOFU and succeeds`() {
        server.enqueue(MockResponse())
        val host = server.url("/").host
        assertNull(pinStore.getPin(host))

        call(httpsClient())

        assertNotNull("expected a pin to be captured on first contact", pinStore.getPin(host))
        assertTrue(pinStore.getPin(host)!!.startsWith("sha256/"))
    }

    @Test
    fun `matching pin passes through`() {
        server.enqueue(MockResponse())
        server.enqueue(MockResponse())
        val client = httpsClient()

        call(client) // TOFU
        call(client) // must not throw — same cert
    }

    @Test
    fun `changed certificate throws and records the mismatch`() {
        server.enqueue(MockResponse())
        val host = server.url("/").host
        pinStore.replacePin(host, "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")

        try {
            call(httpsClient())
            fail("expected CertificatePinMismatchException")
        } catch (e: IOException) {
            val mismatch = e as? CertificatePinMismatchException
                ?: generateSequence(e.cause) { it.cause }
                    .filterIsInstance<CertificatePinMismatchException>()
                    .firstOrNull()
            assertNotNull("expected a CertificatePinMismatchException in the cause chain", mismatch)
            assertEquals(host, mismatch!!.host)
        }

        val recorded = observed.peek(host)
        assertNotNull("mismatch must be recorded for the re-trust dialog", recorded)
        assertEquals(host, recorded!!.host)
    }

    @Test
    fun `cleartext connection without handshake is not pinned`() {
        val plain = MockWebServer()
        try {
            plain.start()
            plain.enqueue(MockResponse())
            val host = plain.url("/").host

            val client = OkHttpClient.Builder()
                .addNetworkInterceptor(CertificatePinningInterceptor(pinStore, observed))
                .build()
            client.newCall(Request.Builder().url(plain.url("/")).build()).execute().close()

            assertNull("cleartext hosts must not be pinned", pinStore.getPin(host))
        } finally {
            plain.shutdown()
        }
    }
}
