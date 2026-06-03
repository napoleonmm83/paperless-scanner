package com.paperless.scanner.data.network

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
 *
 * [latest] exposes the most recent unresolved mismatch reactively so an app-wide
 * surface can show the re-trust dialog when a cert changes DURING a session, not
 * just in the login/setup flow (Issue #249).
 */
@Singleton
class ObservedCertHolder @Inject constructor() {

    data class Mismatch(
        val host: String,
        val expectedPin: String,
        val actualPin: String,
    )

    private val pending = ConcurrentHashMap<String, Mismatch>()

    private val _latest = MutableStateFlow<Mismatch?>(null)

    /** The most recent unresolved mismatch, or null. Cleared on [consume]. */
    val latest: StateFlow<Mismatch?> = _latest.asStateFlow()

    fun record(mismatch: Mismatch) {
        pending[mismatch.host.lowercase()] = mismatch
        _latest.value = mismatch
    }

    /** Read without removing — for rendering the dialog. */
    fun peek(host: String): Mismatch? = pending[host.lowercase()]

    /** Read and remove — once the user has accepted/declined the change. */
    fun consume(host: String): Mismatch? {
        val key = host.lowercase()
        val removed = pending.remove(key)
        promoteLatestAfterRemoving(removed)
        return removed
    }

    /**
     * Atomic compare-and-remove for the app-wide re-trust path (#249): consume the
     * pending mismatch for [host] ONLY if its [Mismatch.actualPin] still equals
     * [actualPin] (the fingerprint the user approved in the dialog). If a concurrent
     * `record()` replaced it with a different certificate, this returns null and
     * leaves the newer mismatch in place — so the dialog keeps showing the new
     * fingerprint instead of trusting, or silently dropping, an unseen certificate.
     */
    fun consumeIfMatches(host: String, actualPin: String): Mismatch? {
        val key = host.lowercase()
        val current = pending[key] ?: return null
        if (current.actualPin != actualPin) return null
        // ConcurrentHashMap.remove(key, value) only removes if still mapped to this
        // exact entry, so a record() that raced in after the check is not lost.
        if (!pending.remove(key, current)) return null
        promoteLatestAfterRemoving(current)
        return current
    }

    /**
     * After [removed] has been taken out of [pending], update the reactive surface:
     * if it was the one being shown, promote another still-pending mismatch (a
     * different host can have its own unresolved change — e.g. the main server and
     * a Paperless-GPT host) so the app-wide dialog keeps surfacing pending re-trusts
     * instead of going dark until the next failing request re-records them (#249).
     * A concurrent record() for another host is left untouched (current != removed).
     */
    private fun promoteLatestAfterRemoving(removed: Mismatch?) {
        if (removed == null) return
        _latest.update { current ->
            if (current == removed) pending.values.firstOrNull() else current
        }
    }
}
