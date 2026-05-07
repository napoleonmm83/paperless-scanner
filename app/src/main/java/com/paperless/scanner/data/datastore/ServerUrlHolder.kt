package com.paperless.scanner.data.datastore

import com.paperless.scanner.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thread-safe, non-blocking server-URL cache for the OkHttp request hot path.
 *
 * Resolves the long-standing F-097 issue: [com.paperless.scanner.data.api.DynamicBaseUrlInterceptor]
 * previously called `runBlocking { tokenManager.serverUrl.first() }` on every
 * intercepted request, which serialized concurrent requests under the
 * DataStore lock even though the read itself is fast after the first warm
 * cache hit.
 *
 * This holder primes its [AtomicReference] once at DI graph construction
 * (the only `runBlocking` left, on the init path — F-006 permits this) and
 * then keeps the value fresh by collecting the underlying [TokenManager.serverUrl]
 * [kotlinx.coroutines.flow.Flow] on the application-wide scope. Logout,
 * login, and server-switch flows all flow through `tokenManager.saveCredentials`
 * / `tokenManager.clearCredentials`, both of which write to the same
 * DataStore key — the Flow re-emits and the cache updates automatically, so
 * call sites do not need to invalidate the cache manually.
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
        // Prime once on the DI init path so the very first request after app
        // start does not race the Flow collector below. This runBlocking runs
        // when the singleton is constructed, NOT per request — F-006 explicitly
        // permits runBlocking on init/migration paths.
        cache.set(runBlocking { tokenManager.serverUrl.first() }?.trimEnd('/'))

        // Keep the cache fresh on every subsequent emission (login, logout,
        // server-switch). Runs on the application-wide SupervisorJob scope
        // for the full app lifetime.
        scope.launch {
            tokenManager.serverUrl.collect { url ->
                cache.set(url?.trimEnd('/'))
            }
        }
    }

    /**
     * Latest known server URL with trailing slash stripped, or `null` if no
     * credentials are stored. Pure atomic read — safe to call from any thread,
     * including the OkHttp dispatcher.
     */
    fun current(): String? = cache.get()
}
