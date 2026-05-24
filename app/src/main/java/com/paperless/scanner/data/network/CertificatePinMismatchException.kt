package com.paperless.scanner.data.network

import java.io.IOException

/**
 * Thrown by [CertificatePinningInterceptor] when the certificate presented by a
 * host no longer matches the pin captured on first contact (Issue #36).
 *
 * Extends [IOException] because OkHttp interceptors may only throw [IOException];
 * this also lets the existing network-error pipeline propagate it. It MUST be
 * mapped to [com.paperless.scanner.data.api.PaperlessException.CertificatePinMismatch]
 * BEFORE any generic `IOException` branch (mirror of `CleartextNotAllowlistedException`).
 *
 * Carries only the host and the opaque SPKI pin strings — no token or document data.
 */
class CertificatePinMismatchException(
    val host: String,
    val expectedPin: String,
    val actualPin: String,
) : IOException("Certificate pin mismatch for host: $host")
