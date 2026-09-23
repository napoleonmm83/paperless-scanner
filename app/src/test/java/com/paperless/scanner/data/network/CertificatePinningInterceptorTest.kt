package com.paperless.scanner.data.network

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import okhttp3.Connection
import okhttp3.Interceptor
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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.Socket

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

        // MockWebServer 5.x reports the plain host name for server.url(), while 4.x
        // reported the CANONICAL one. On a machine whose hosts file maps 127.0.0.1 to
        // something else — a Docker Desktop entry does exactly that — the canonical
        // name is not "localhost", the SAN then misses the URL host, and hostname
        // verification fails. Cover both names so the fixture does not depend on the
        // developer's hosts file.
        val canonical = InetAddress.getByName("localhost").canonicalHostName
        val certBuilder = HeldCertificate.Builder()
            .addSubjectAlternativeName("localhost")
        if (canonical != "localhost") {
            certBuilder.addSubjectAlternativeName(canonical)
        }
        serverCert = certBuilder.build()
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

    private fun httpsClient(
        coordinator: PinRecoveryCoordinator = mockk(relaxed = true),
    ): OkHttpClient = OkHttpClient.Builder()
        .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
        .addNetworkInterceptor(CertificatePinningInterceptor(pinStore, observed, coordinator))
        .build()

    @Test
    fun `manual enrollment sends no authorization before fingerprint confirmation`() {
        val tokenManager = mockk<com.paperless.scanner.data.datastore.TokenManager>()
        every { tokenManager.getPinRecoveryPhase() } returns "manual_enrollment"
        val coordinator = PinRecoveryCoordinator(tokenManager, pinStore, observed)
        val client = httpsClient(coordinator)
        val request = Request.Builder().url(server.url("/api/"))
            .header("Authorization", "Token SECRET").build()

        assertThrows(CertificateFirstTrustRequiredException::class.java) {
            client.newCall(request).execute().close()
        }
        assertEquals(0, server.requestCount)
        val host = server.url("/").host
        val candidate = observed.peekFirstTrust(host)!!
        assertTrue(coordinator.confirmFirstTrust(host, candidate.presentedPin))

        server.enqueue(MockResponse().setResponseCode(200))
        client.newCall(request).execute().close()
        assertEquals(1, server.requestCount)
    }

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
    fun `failed first pin write blocks the request before it reaches the server`() {
        pinStore = CertificatePinStore(object : CertPinStorage {
            override fun loadAll(): Map<String, String> = emptyMap()
            override fun put(host: String, pin: String): Unit = throw IOException("disk write failed")
            override fun remove(host: String) = Unit
            override fun clear() = Unit
        })

        assertThrows(CertificatePinPersistenceException::class.java) { call(httpsClient()) }

        assertEquals(0, server.requestCount)
        assertNull(pinStore.getPin(server.url("/").host))
    }

    @Test
    fun `pin removed during first-contact race blocks request`() {
        server.enqueue(MockResponse())
        pinStore = mockk()
        every { pinStore.getPin(any()) } returns null
        every { pinStore.setPinIfAbsent(any(), any()) } returns false

        assertThrows(CertificatePinPersistenceException::class.java) { call(httpsClient()) }

        assertEquals(0, server.requestCount)
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

        // THE security contract, and the part the throw alone does not prove:
        // the call aborts BEFORE the request is written, so the Authorization
        // header never reaches a man-in-the-middle. Asserting only that it
        // throws would stay green if the request were ever written before the
        // network-interceptor chain runs.
        assertEquals("request must never reach the server on a pin mismatch", 0, server.requestCount)

        val recorded = observed.peek(host)
        assertNotNull("mismatch must be recorded for the re-trust dialog", recorded)
        assertEquals(host, recorded!!.host)
    }

    @Test
    fun `pending TLS recovery leaves explicitly allowed cleartext transport unchanged`() {
        val cleartext = MockWebServer()
        cleartext.enqueue(MockResponse().setResponseCode(200))
        val tokenManager = mockk<com.paperless.scanner.data.datastore.TokenManager>()
        every { tokenManager.getPinRecoveryPhase() } returns "reset_pending"
        val coordinator = PinRecoveryCoordinator(tokenManager, pinStore, observed)
        val client = OkHttpClient.Builder()
            .addNetworkInterceptor(CertificatePinningInterceptor(pinStore, observed, coordinator))
            .build()
        try {
            client.newCall(Request.Builder().url(cleartext.url("/api/")).build()).execute().close()
            assertEquals(1, cleartext.requestCount)
        } finally {
            cleartext.shutdown()
        }
    }

    @Test
    fun `cleartext connection without handshake is not pinned`() {
        val plain = MockWebServer()
        try {
            plain.start()
            plain.enqueue(MockResponse())
            val host = plain.url("/").host

            val client = OkHttpClient.Builder()
                .addNetworkInterceptor(CertificatePinningInterceptor(pinStore, observed, mockk(relaxed = true)))
                .build()
            client.newCall(Request.Builder().url(plain.url("/")).build()).execute().close()

            assertNull("cleartext hosts must not be pinned", pinStore.getPin(host))
        } finally {
            plain.shutdown()
        }
    }

    @Test
    fun `https without a peer certificate fails before sending a request`() {
        val chain = mockk<Interceptor.Chain>()
        val connection = mockk<Connection>()
        every { chain.request() } returns Request.Builder().url("https://example.test/").build()
        every { chain.connection() } returns connection
        every { connection.handshake() } returns null
        every { connection.socket() } returns Socket()

        assertThrows(IOException::class.java) {
            CertificatePinningInterceptor(pinStore, observed, mockk(relaxed = true)).intercept(chain)
        }
        verify(exactly = 0) { chain.proceed(any()) }
    }
}
