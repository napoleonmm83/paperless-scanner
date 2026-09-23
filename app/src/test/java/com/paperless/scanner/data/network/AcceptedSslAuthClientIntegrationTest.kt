package com.paperless.scanner.data.network

import com.paperless.scanner.data.api.HttpAllowlistHolder
import com.paperless.scanner.data.api.HttpAllowlistInterceptor
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.data.datastore.TokenStorage
import com.paperless.scanner.di.AppModule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class AcceptedSslAuthClientIntegrationTest {

    private class MemoryPinStorage : CertPinStorage {
        private val pins = mutableMapOf<String, String>()

        override fun loadAll(): Map<String, String> = pins.toMap()
        override fun put(host: String, pin: String) { pins[host] = pin }
        override fun remove(host: String) { pins.remove(host) }
        override fun clear() { pins.clear() }
    }

    private lateinit var tokenManager: TokenManager

    @Before
    fun setUp() {
        val storage = mockk<TokenStorage>(relaxed = true)
        every { storage.isMigrationCompleted() } returns true
        every { storage.consumeRecoveredCryptoFailure() } returns null
        tokenManager = TokenManager(RuntimeEnvironment.getApplication(), storage)
        runBlocking { tokenManager.clearCredentials() }
    }

    @Test
    fun `accepted self signed host captures its pin and blocks a changed certificate`() = runBlocking {
        val requestedHost = InetAddress.getByName("localhost").canonicalHostName
        val certificate = HeldCertificate.Builder()
            .commonName(requestedHost)
            .addSubjectAlternativeName(requestedHost)
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(certificate)
            .build()
        val server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        val host = server.url("/").host
        val certificateCn = Regex("CN=([^,]+)")
            .find(certificate.certificate.subjectDN.name)?.groupValues?.get(1)
        assertEquals("the fixture CN must match the requested host", host, certificateCn)
        val pinStore = CertificatePinStore(MemoryPinStorage())
        val observed = ObservedCertHolder()
        val client = AppModule.provideAuthOkHttpClient(
            tokenManager = tokenManager,
            httpAllowlistInterceptor = HttpAllowlistInterceptor(mockk<HttpAllowlistHolder>(relaxed = true)),
            certificatePinningInterceptor = CertificatePinningInterceptor(pinStore, observed),
        )
        val rawPeerCertificateCount = AtomicInteger(-1)
        val diagnosticClient = client.newBuilder()
            .addNetworkInterceptor { chain ->
                val socket = chain.connection()?.socket() as? SSLSocket
                rawPeerCertificateCount.set(
                    runCatching { socket?.session?.peerCertificates?.size ?: -1 }.getOrDefault(-1)
                )
                chain.proceed(chain.request())
            }
            .build()
        val request = Request.Builder().url(server.url("/api/")).build()

        try {
            assertThrows(IOException::class.java) {
                client.newCall(request).execute().close()
            }
            assertEquals("unaccepted certificate must not send a request", 0, server.requestCount)
            assertNull(pinStore.getPin(host))

            tokenManager.acceptSslForHost(host)
            assertEquals(true, tokenManager.isHostAcceptedForSsl(host))
            server.enqueue(MockResponse().setResponseCode(200))
            val peerCertificateCount = diagnosticClient.newCall(request).execute().use { response ->
                assertEquals(200, response.code)
                response.handshake?.peerCertificates?.size
            }

            assertEquals("accepted certificate must reach the server", 1, server.requestCount)
            assertEquals("raw TLS session must expose the self-signed peer", 1, rawPeerCertificateCount.get())
            assertNotNull(
                "accepted certificate must be pinned (handshake peers: $peerCertificateCount)",
                pinStore.getPin(host),
            )
            assertEquals(CertificateFingerprint.spkiPin(certificate.certificate), pinStore.getPin(host))

            val changedCertificate = HeldCertificate.Builder()
                .commonName(requestedHost)
                .addSubjectAlternativeName(requestedHost)
                .addSubjectAlternativeName("localhost")
                .build()
            val changedServerCertificates = HandshakeCertificates.Builder()
                .heldCertificate(changedCertificate)
                .build()
            val changedServer = MockWebServer()
            changedServer.useHttps(changedServerCertificates.sslSocketFactory(), false)
            try {
                assertEquals(host, changedServer.url("/").host)
                changedServer.enqueue(MockResponse().setResponseCode(200))
                val changedRequest = Request.Builder()
                    .url(changedServer.url("/api/"))
                    .header("Authorization", "Bearer local-test-token")
                    .build()

                val failure = assertThrows(IOException::class.java) {
                    client.newCall(changedRequest).execute().close()
                }
                val mismatch = (failure as? CertificatePinMismatchException)
                    ?: generateSequence(failure.cause) { it.cause }
                        .filterIsInstance<CertificatePinMismatchException>()
                        .firstOrNull()
                assertNotNull("changed certificate must raise a pin mismatch", mismatch)
                assertEquals(host, mismatch!!.host)
                assertEquals(
                    CertificateFingerprint.spkiPin(changedCertificate.certificate),
                    mismatch.actualPin,
                )
                assertEquals("changed server must not receive a request", 0, changedServer.requestCount)
                assertNotNull("mismatch must be available to the re-trust dialog", observed.peek(host))
            } finally {
                changedServer.shutdown()
            }
        } finally {
            tokenManager.clearCredentials()
            server.shutdown()
        }
    }
}
