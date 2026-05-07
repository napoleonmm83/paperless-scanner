package com.paperless.scanner.data.datastore

import com.paperless.scanner.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thread-safe, non-blocking server-URL cache for the OkHttp request hot path.
 *
 * Resolves the F-097 issue: [com.paperless.scanner.data.api.DynamicBaseUrlInterceptor]
 * previously called `runBlocking { tokenManager.serverUrl.first() }` on every
 * intercepted request, serializing concurrent requests under the DataStore
 * lock even though the read itself is fast after the first warm cache hit.
 *
 * The holder launches a single coroutine on the application-wide scope which
 * collects [TokenManager.serverUrl] and atomically updates the cache on every
 * emission. The collect runs on the injected scope's dispatcher (typically
 * [kotlinx.coroutines.Dispatchers.Default] from the `@ApplicationScope`
 * provider in `AppModule`), so construction never blocks the Hilt
 * dependency-graph thread. Login, logout, and server-switch flows already
 * write through `tokenManager.saveCredentials` / `tokenManager.clearCredentials`
 * → DataStore → Flow, so the holder updates automatically without any manual
 * invalidation wiring at call sites.
 *
 * **Initial-emission window:** between singleton construction and the first
 * Flow emission landing on the launched coroutine, [current] returns `null`
 * — a request issued in this brief window will fall through the interceptor
 * unchanged (same as a logged-out user). DataStore's in-memory snapshot
 * makes this window microseconds long in practice, and an unconfigured
 * placeholder request is benign.
 *
 * The interceptor reads via [current] which is a single atomic load — no
 * blocking, no lock, no contention, no allocation.
 */
@Singleton
class ServerUrlHolder @Inject constructor(
    tokenManager: TokenManager,
    @ApplicationScope scope: CoroutineScope,
) {
    private val cache = AtomicReference<String?>(null)

    init {
        // Single collector handles BOTH the initial prime (Flow emits its
        // current value immediately to new collectors) AND every subsequent
        // emission. Runs on the application-wide SupervisorJob scope for the
        // full app lifetime; never blocks the construction thread.
        scope.launch {
            tokenManager.serverUrl.collect { url ->
                cache.set(url?.trimEnd('/'))
            }
        }
    }

    /**
     * Latest known server URL with trailing slash stripped, or `null` if no
     * credentials are stored OR if the launched collector has not yet
     * processed the first Flow emission. Pure atomic read — safe to call
     * from any thread, including the OkHttp dispatcher.
     */
    fun current(): String? = cache.get()
}
