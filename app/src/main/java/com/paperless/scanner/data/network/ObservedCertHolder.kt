package com.paperless.scanner.data.network

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Transient record of the most recent certificate-pin mismatch seen per host
 * (Issue #36).
 *
 * When [CertificatePinningInterceptor] detects a changed certificate it records the
 * expected (pinned) and actual (presented) SPKI pins here before throwing. The login
 * layer reads this to populate the blocking re-trust dialog with both fingerprints,
 * and consumes the entry once the user has decided. Purely in-memory — a process
 * death simply means the next failing connection re-records it.
 */
@Singleton
class ObservedCertHolder @Inject constructor() {

    data class Mismatch(
        val host: String,
        val expectedPin: String,
        val actualPin: String,
    )

    private val pending = ConcurrentHashMap<String, Mismatch>()

    fun record(mismatch: Mismatch) {
        pending[mismatch.host.lowercase()] = mismatch
    }

    /** Read without removing — for rendering the dialog. */
    fun peek(host: String): Mismatch? = pending[host.lowercase()]

    /** Read and remove — once the user has accepted/declined the change. */
    fun consume(host: String): Mismatch? = pending.remove(host.lowercase())
}
