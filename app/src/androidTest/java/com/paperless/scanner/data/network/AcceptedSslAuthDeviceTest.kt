package com.paperless.scanner.data.network

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.paperless.scanner.data.api.HttpAllowlistHolder
import com.paperless.scanner.data.api.HttpAllowlistInterceptor
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.di.AppModule
import io.mockk.every
import io.mockk.mockk
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Exercises the production auth-client TLS wiring on Android without touching app storage. */
@RunWith(AndroidJUnit4::class)
class AcceptedSslAuthDeviceTest {

    private class MemoryPinStorage : CertPinStorage {
        private val pins = mutableMapOf<String, String>()

        override fun loadAll(): Map<String, String> = pins.toMap()
        override fun put(host: String, pin: String) { pins[host] = pin }
        override fun remove(host: String) { pins.remove(host) }
        override fun clear() { pins.clear() }
    }

    @Test
    fun acceptedSelfSignedHostPinsAndRejectsChangedCertificateBeforeRequest() {
        val host = "localhost"
        val accepted = AtomicBoolean(false)
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.isHostAcceptedForSsl(any()) } answers {
            accepted.get() && firstArg<String>() == host
        }
        val pinStore = CertificatePinStore(MemoryPinStorage())
        val observed = ObservedCertHolder()
        val client = AppModule.provideAuthOkHttpClient(
            tokenManager = tokenManager,
            httpAllowlistInterceptor = HttpAllowlistInterceptor(mockk<HttpAllowlistHolder>()),
            certificatePinningInterceptor = CertificatePinningInterceptor(pinStore, observed),
        ).newBuilder().callTimeout(10, TimeUnit.SECONDS).build()

        fun certificate() = HeldCertificate.Builder()
            .commonName(host)
            .addSubjectAlternativeName(host)
            .build()

        val originalCertificate = certificate()
        val originalServer = MockWebServer()
        originalServer.useHttps(
            HandshakeCertificates.Builder().heldCertificate(originalCertificate).build().sslSocketFactory(),
            false,
        )
        try {
            assertEquals(host, originalServer.url("/").host)
            val request = Request.Builder().url(originalServer.url("/api/")).build()
            val unacceptedFailure = runCatching { client.newCall(request).execute().close() }.exceptionOrNull()
            assertTrue("unaccepted self-signed host must fail", unacceptedFailure is IOException)
            assertEquals("unaccepted request must not reach the server", 0, originalServer.requestCount)
            assertNull(pinStore.getPin(host))

            accepted.set(true)
            originalServer.enqueue(MockResponse().setResponseCode(200))
            client.newCall(request).execute().use { response -> assertEquals(200, response.code) }
            assertEquals(1, originalServer.requestCount)
            assertEquals(CertificateFingerprint.spkiPin(originalCertificate.certificate), pinStore.getPin(host))

            val changedCertificate = certificate()
            val changedServer = MockWebServer()
            changedServer.useHttps(
                HandshakeCertificates.Builder().heldCertificate(changedCertificate).build().sslSocketFactory(),
                false,
            )
            try {
                assertEquals(host, changedServer.url("/").host)
                changedServer.enqueue(MockResponse().setResponseCode(200))
                val changedRequest = Request.Builder()
                    .url(changedServer.url("/api/"))
                    .header("Authorization", "Bearer local-test-token")
                    .build()
                val failure = runCatching { client.newCall(changedRequest).execute().close() }.exceptionOrNull()
                val mismatch = (failure as? CertificatePinMismatchException)
                    ?: generateSequence(failure?.cause) { it.cause }
                        .filterIsInstance<CertificatePinMismatchException>()
                        .firstOrNull()
                assertNotNull("changed certificate must raise a pin mismatch", mismatch)
                assertEquals(0, changedServer.requestCount)
                assertEquals(CertificateFingerprint.spkiPin(changedCertificate.certificate), mismatch!!.actualPin)
                assertNotNull("mismatch must be available to the re-trust dialog", observed.peek(host))
            } finally {
                changedServer.shutdown()
            }
        } finally {
            originalServer.shutdown()
        }
    }
}
