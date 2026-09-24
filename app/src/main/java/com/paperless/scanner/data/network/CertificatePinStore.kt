package com.paperless.scanner.data.network

import java.util.Locale
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Single source of truth for trust-on-first-use (TOFU) certificate pins (Issue #36).
 *
 * Holds an in-memory `host -> SPKI pin` cache so the hot path inside
 * [CertificatePinningInterceptor] is a lock-free map lookup on the OkHttp dispatcher
 * thread — no disk read, no `runBlocking`. Mirrors the holder pattern used by
 * [com.paperless.scanner.data.api.HttpAllowlistHolder] /
 * [com.paperless.scanner.data.datastore.ServerUrlHolder]. Writes are mirrored to the
 * encrypted [CertPinStorage] for persistence across restarts.
 *
 * Host keys are normalized to lowercase so `getPin`/`setPinIfAbsent`/`replacePin`
 * agree regardless of how the URL was cased.
 */
@Singleton
class CertificatePinStore @Inject constructor(
    private val storage: CertPinStorage
) {
    private val cache = ConcurrentHashMap<String, String>()
    private val _recoveryRequired = MutableStateFlow(false)
    val recoveryRequired: StateFlow<Boolean> = _recoveryRequired

    init {
        cache.putAll(storage.loadAll())
        _recoveryRequired.value = storage.hasLoadFailure()
    }

    /** Latest pin for [host], or null if none captured yet. */
    fun getPin(host: String): String? = cache[host.lowercase(Locale.ROOT)]

    /**
     * TOFU capture: store [pin] only if no pin exists for [host] yet.
     * @return true if a new pin was captured, false if one already existed.
     */
    @Synchronized
    fun setPinIfAbsent(host: String, pin: String): Boolean {
        ensureReadable()
        val key = host.lowercase(Locale.ROOT)
        if (cache.containsKey(key)) return false
        storage.put(key, pin)
        cache[key] = pin
        return true
    }

    /**
     * Re-trust: overwrite an existing pin after the user explicitly approved a
     * certificate change in the blocking dialog.
     */
    @Synchronized
    fun replacePin(host: String, pin: String) {
        ensureReadable()
        val key = host.lowercase(Locale.ROOT)
        storage.put(key, pin)
        cache[key] = pin
    }

    /** Forget the pin for [host] (e.g. when the user removes the accepted host). */
    @Synchronized
    fun removePin(host: String) {
        ensureReadable()
        val key = host.lowercase(Locale.ROOT)
        cache.remove(key)
        storage.remove(key)
    }

    /** Drop all pins. Not called on logout — pins intentionally survive logout. */
    @Synchronized
    fun clear() {
        ensureReadable()
        cache.clear()
        storage.clear()
    }

    /** Reset only after a separate, durable recovery gate has been armed. */
    @Synchronized
    fun resetCorruptedStore() {
        storage.resetCorruptedStore()
        cache.clear()
        _recoveryRequired.value = false
    }

    private fun ensureReadable() {
        if (_recoveryRequired.value) throw IOException("Certificate pins unavailable; recovery required")
    }
}
