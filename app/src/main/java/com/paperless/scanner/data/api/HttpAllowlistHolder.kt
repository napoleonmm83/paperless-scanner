package com.paperless.scanner.data.api

import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thread-safe, non-blocking allowlist cache for cleartext-HTTP hosts the user
 * has explicitly accepted via the in-app insecure-connection warning.
 *
 * Mirrors [com.paperless.scanner.data.datastore.ServerUrlHolder]: a single
 * collector on the application-wide scope keeps an [AtomicReference] in sync
 * with [TokenManager.acceptedHttpHostsFlow], so the hot path inside
 * [HttpAllowlistInterceptor] is a single lock-free atomic load — no
 * DataStore read, no `runBlocking`, no contention.
 *
 * **Initial-emission window:** between singleton construction and the first
 * Flow emission landing on the launched coroutine, [snapshot] returns the
 * empty set. Any cleartext request to a non-loopback host issued in this
 * brief window is denied (fail-closed). DataStore's in-memory snapshot
 * makes this window microseconds long in practice.
 */
@Singleton
class HttpAllowlistHolder @Inject constructor(
    tokenManager: TokenManager,
    @ApplicationScope scope: CoroutineScope,
) {
    private val cache = AtomicReference<Set<String>>(emptySet())

    init {
        scope.launch {
            tokenManager.acceptedHttpHostsFlow.collect { hosts ->
                cache.set(hosts.map { it.lowercase() }.toSet())
            }
        }
    }

    /**
     * Latest accepted HTTP hosts (lowercased), or an empty set if the launched
     * collector has not yet processed the first Flow emission. Pure atomic
     * read — safe to call from any thread, including the OkHttp dispatcher.
     */
    fun snapshot(): Set<String> = cache.get()
}
