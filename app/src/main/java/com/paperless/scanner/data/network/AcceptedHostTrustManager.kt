package com.paperless.scanner.data.network

import android.util.Log
import com.paperless.scanner.data.datastore.TokenManager
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLSession
import javax.net.ssl.X509TrustManager

/**
 * Custom TrustManager that accepts self-signed certificates for whitelisted hosts.
 * Uses TokenManager to check if a host has been explicitly accepted by the user.
 */
class AcceptedHostTrustManager(
    private val tokenManager: TokenManager,
    private val defaultTrustManager: X509TrustManager
) : X509TrustManager {

    companion object {
        private const val TAG = "AcceptedHostTrustManager"
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        // We don't handle client certificates
        defaultTrustManager.checkClientTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        try {
            // Try normal SSL verification first
            defaultTrustManager.checkServerTrusted(chain, authType)
        } catch (e: Exception) {
            // SSL verification failed - check if this host is in accepted list
            val serverHost = chain?.firstOrNull()?.subjectDN?.name?.let { dn ->
                // Extract CN from DN (e.g., "CN=paperless.local, O=..." -> "paperless.local")
                val cnMatch = Regex("CN=([^,]+)").find(dn)
                cnMatch?.groupValues?.getOrNull(1)?.trim()
            }

            if (serverHost != null && tokenManager.isHostAcceptedForSsl(serverHost)) {
                Log.d(TAG, "Accepting self-signed certificate for whitelisted host: $serverHost")
                // Host is whitelisted - accept the certificate
                return
            }

            // Host not whitelisted - throw original exception
            throw e
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> {
        return defaultTrustManager.acceptedIssuers
    }
}

/**
 * Custom HostnameVerifier that accepts any hostname for whitelisted hosts.
 */
class AcceptedHostnameVerifier(
    private val tokenManager: TokenManager
) : HostnameVerifier {

    companion object {
        private const val TAG = "AcceptedHostnameVerifier"
    }

    override fun verify(hostname: String, session: SSLSession): Boolean {
        // Check if this host is accepted
        if (tokenManager.isHostAcceptedForSsl(hostname)) {
            Log.d(TAG, "Accepting hostname for whitelisted host: $hostname")
            return true
        }

        // Not whitelisted - perform standard verification
        return javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier()
            .verify(hostname, session)
    }
}
