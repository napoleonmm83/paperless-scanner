package com.paperless.scanner.data.network

import okhttp3.CertificatePinner
import java.security.cert.X509Certificate

/**
 * Computes certificate identity values used by the TOFU pinning feature (Issue #36).
 *
 * Pinning is done on the **Subject Public Key Info (SPKI)** SHA-256, not the full
 * certificate, so that a legitimate renewal that reuses the same key pair does NOT
 * trip the pin-change dialog. A renewal with a fresh key pair changes the SPKI and
 * is therefore surfaced to the user — which is the intended trust-on-change behavior.
 */
object CertificateFingerprint {

    /**
     * OkHttp-format SPKI pin: `sha256/<base64>`. Identical to what
     * [okhttp3.CertificatePinner] would enforce, so the value is stable and
     * comparable across connections.
     */
    fun spkiPin(certificate: X509Certificate): String =
        CertificatePinner.pin(certificate)
}
