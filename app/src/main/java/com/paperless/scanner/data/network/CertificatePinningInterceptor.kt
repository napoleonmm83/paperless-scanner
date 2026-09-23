package com.paperless.scanner.data.network

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.security.cert.X509Certificate
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLSocket

class CertificatePinPersistenceException(val host: String, cause: IOException) : IOException(cause)

/**
 * Trust-on-first-use (TOFU) certificate pinning for user-provided Paperless servers
 * (Issue #36).
 *
 * Registered as an OkHttp **network** interceptor on every client that carries the
 * auth token or document content (Paperless-ngx default, auth/discovery, Coil
 * thumbnails). A network interceptor runs AFTER the TLS handshake (so the peer
 * certificate is available from the established TLS socket) but BEFORE
 * `chain.proceed()` writes the request — so a mismatch aborts the call before the
 * `Authorization` header is ever transmitted to a man-in-the-middle.
 *
 * Behavior per connection:
 * - **Cleartext / no handshake** → pass through (pinning only applies to TLS;
 *   cleartext is gated separately by [com.paperless.scanner.data.api.HttpAllowlistInterceptor]).
 * - **TLS but empty OkHttp handshake peer list** → read the presented certificate
 *   from the established SSL socket instead. This occurs for an accepted
 *   self-signed host even when the raw TLS session has its certificate. If
 *   neither source has a peer certificate, abort before sending the request.
 * - **No pin yet** → TOFU: capture the presented SPKI pin and pass through. First
 *   contact relies on TLS validation by the CA store or on explicit host acceptance.
 * - **Pin matches** → pass through.
 * - **Pin differs** → record the mismatch in [ObservedCertHolder] and throw
 *   [CertificatePinMismatchException]; the user must explicitly re-trust.
 */
@Singleton
class CertificatePinningInterceptor @Inject constructor(
    private val pinStore: CertificatePinStore,
    private val observedCertHolder: ObservedCertHolder,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val connection = chain.connection()
        val peerCertificate = connection?.handshake()?.peerCertificates?.firstOrNull()
            ?: (connection?.socket() as? SSLSocket)?.session?.peerCertificates?.firstOrNull()
        val leaf = peerCertificate as? X509Certificate

        if (request.url.scheme == "https" && leaf == null) {
            throw IOException("TLS peer certificate unavailable for ${request.url.host}")
        }

        if (leaf != null) {
            val host = request.url.host
            val presentedPin = CertificateFingerprint.spkiPin(leaf)
            val storedPin = pinStore.getPin(host)
            when {
                storedPin == null -> {
                    // TOFU capture. If a concurrent first request already captured a
                    // pin (setPinIfAbsent returns false), enforce that winner against
                    // this connection's cert instead of blindly proceeding — otherwise
                    // a request could go out under a cert that differs from the pin
                    // that just won the race.
                    val captured = try {
                        pinStore.setPinIfAbsent(host, presentedPin)
                    } catch (e: IOException) {
                        throw CertificatePinPersistenceException(host, e)
                    }
                    if (!captured) {
                        val winner = pinStore.getPin(host)
                        if (winner == null) throw CertificatePinPersistenceException(host, IOException())
                        if (winner != presentedPin) {
                            observedCertHolder.record(
                                ObservedCertHolder.Mismatch(host, winner, presentedPin)
                            )
                            throw CertificatePinMismatchException(host, winner, presentedPin)
                        }
                    }
                }
                storedPin == presentedPin -> observedCertHolder.consume(host)
                else -> {
                    observedCertHolder.record(
                        ObservedCertHolder.Mismatch(host, storedPin, presentedPin)
                    )
                    throw CertificatePinMismatchException(host, storedPin, presentedPin)
                }
            }
        }

        return chain.proceed(request)
    }
}
