package com.paperless.scanner.data.network

/**
 * Persistence abstraction for certificate pins (Issue #36).
 *
 * Mirrors the [com.paperless.scanner.data.datastore.TokenStorage] /
 * [com.paperless.scanner.data.datastore.SecureTokenStorage] split: the interface
 * keeps [CertificatePinStore]'s TOFU/replace logic unit-testable with an in-memory
 * fake, while the production [EncryptedCertPinStorage] is a thin, trusted delegate
 * over EncryptedSharedPreferences.
 *
 * Implementations map host (lowercased by [CertificatePinStore]) -> SPKI pin string.
 */
interface CertPinStorage {
    fun loadAll(): Map<String, String>
    fun put(host: String, pin: String)
    fun remove(host: String)
    fun clear()
}
