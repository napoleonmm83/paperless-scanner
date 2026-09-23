package com.paperless.scanner.data.network

import com.paperless.scanner.data.api.HttpAllowlistHolder
import com.paperless.scanner.data.api.HttpAllowlistInterceptor
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.data.datastore.TokenStorage
import com.paperless.scanner.di.AppModule
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Cache
import okhttp3.OkHttpClient
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
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class AcceptedSslAuthClientIntegrationTest {
    private fun cachedAuthClient(phase: String, cache: Cache, host: String): OkHttpClient {
        val manager = mockk<TokenManager>(relaxed = true)
        every { manager.getPinRecoveryPhase() } returns phase
        every { manager.isHostAcceptedForSsl(any()) } returns true
        val allowlist = mockk<HttpAllowlistHolder>()
        every { allowlist.snapshot() } returns setOf(host)
        val pins = CertificatePinStore(MemoryPinStorage())
        val observed = ObservedCertHolder()
        return AppModule.provideAuthOkHttpClient(
            manager,
            HttpAllowlistInterceptor(allowlist),
            CertificatePinningInterceptor(pins, observed, PinRecoveryCoordinator(manager, pins, observed)),
        ).newBuilder().cache(cache).build()
    }

    @Test
    fun `cached response cannot bypass pending pin recovery`() {
        val cache = Cache(Files.createTempDirectory("pin-recovery-pending-cache").toFile(), 1024L * 1024L)
        val requestedHost = InetAddress.getByName("localhost").canonicalHostName
        val certificate = HeldCertificate.Builder().commonName(requestedHost)
            .addSubjectAlternativeName(requestedHost).addSubjectAlternativeName("localhost").build()
        val certificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val server = MockWebServer().apply { useHttps(certificates.sslSocketFactory(), false) }
        try {
            server.enqueue(MockResponse().setBody("cached").addHeader("Cache-Control", "max-age=600"))
            val request = Request.Builder().url(server.url("/api/")).build()
            cachedAuthClient("none", cache, request.url.host).newCall(request).execute().close()
            assertEquals(1, server.requestCount)

            assertThrows(CertificatePinRecoveryRequiredException::class.java) {
                cachedAuthClient("reset_pending", cache, request.url.host).newCall(request).execute().close()
            }
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
            cache.delete()
        }
    }

    @Test
    fun `manual enrollment bypasses old HTTPS cache to demand fingerprint`() {
        val cache = Cache(Files.createTempDirectory("pin-recovery-manual-cache").toFile(), 1024L * 1024L)
        val requestedHost = InetAddress.getByName("localhost").canonicalHostName
        val certificate = HeldCertificate.Builder().commonName(requestedHost)
            .addSubjectAlternativeName(requestedHost).addSubjectAlternativeName("localhost").build()
        val certificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val server = MockWebServer().apply { useHttps(certificates.sslSocketFactory(), false) }
        try {
            server.enqueue(MockResponse().setBody("cached").addHeader("Cache-Control", "max-age=600"))
            val request = Request.Builder().url(server.url("/api/")).build()
            cachedAuthClient("none", cache, request.url.host).newCall(request).execute().close()
            assertEquals(1, server.requestCount)

            assertThrows(CertificateFirstTrustRequiredException::class.java) {
                cachedAuthClient("manual_enrollment", cache, request.url.host).newCall(request).execute().close()
            }
            assertEquals(1, server.requestCount)
        } finally {
            server.shutdown()
            cache.delete()
        }
    }

    @Test
    fun `auth client never redirects credentials to another origin`() = runTest {
        val requestedHost = InetAddress.getByName("localhost").canonicalHostName
        val certificate = HeldCertificate.Builder().commonName(requestedHost)
            .addSubjectAlternativeName(requestedHost).addSubjectAlternativeName("localhost").build()
        val certificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val source = MockWebServer().apply { useHttps(certificates.sslSocketFactory(), false) }
        val destination = MockWebServer().apply { useHttps(certificates.sslSocketFactory(), false) }
        val pinStore = CertificatePinStore(MemoryPinStorage())
        val observed = ObservedCertHolder()
        val client = AppModule.provideAuthOkHttpClient(
            tokenManager,
            HttpAllowlistInterceptor(mockk<HttpAllowlistHolder>(relaxed = true)),
            CertificatePinningInterceptor(pinStore, observed, PinRecoveryCoordinator(tokenManager, pinStore, observed)),
        )
        try {
            tokenManager.acceptSslForHost(source.url("/").host)
            source.enqueue(MockResponse().setResponseCode(307).addHeader("Location", destination.url("/leak")))
            destination.enqueue(MockResponse().setResponseCode(200))
            val request = Request.Builder().url(source.url("/token"))
                .post("username=alice&password=secret".toRequestBody()).build()
            client.newCall(request).execute().use { response -> assertEquals(307, response.code) }
            assertEquals(1, source.requestCount)
            assertEquals(0, destination.requestCount)
        } finally {
            source.shutdown()
            destination.shutdown()
            tokenManager.clearCredentials()
        }
    }

    @Test
    fun `auth client never follows TLS redirect to cleartext`() = runTest {
        val requestedHost = InetAddress.getByName("localhost").canonicalHostName
        val certificate = HeldCertificate.Builder().commonName(requestedHost)
            .addSubjectAlternativeName(requestedHost).addSubjectAlternativeName("localhost").build()
        val certificates = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val https = MockWebServer().apply { useHttps(certificates.sslSocketFactory(), false) }
        val http = MockWebServer()
        val pinStore = CertificatePinStore(MemoryPinStorage())
        val observed = ObservedCertHolder()
        val client = AppModule.provideAuthOkHttpClient(
            tokenManager,
            HttpAllowlistInterceptor(mockk<HttpAllowlistHolder>(relaxed = true)),
            CertificatePinningInterceptor(pinStore, observed, PinRecoveryCoordinator(tokenManager, pinStore, observed)),
        )
        try {
            tokenManager.acceptSslForHost(https.url("/").host)
            https.enqueue(MockResponse().setResponseCode(307).addHeader("Location", http.url("/leak")))
            http.enqueue(MockResponse().setResponseCode(200))
            val request = Request.Builder().url(https.url("/upload")).post("sensitive-document".toRequestBody()).build()
            client.newCall(request).execute().use { response -> assertEquals(307, response.code) }
            assertEquals(1, https.requestCount)
            assertEquals(0, http.requestCount)
        } finally {
            https.shutdown()
            http.shutdown()
            tokenManager.clearCredentials()
        }
    }

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
        runTest { tokenManager.clearCredentials() }
    }

    @Test
    fun `accepted self signed host captures its pin and blocks a changed certificate`() = runTest {
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
            certificatePinningInterceptor = CertificatePinningInterceptor(pinStore, observed, PinRecoveryCoordinator(tokenManager, pinStore, observed)),
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
