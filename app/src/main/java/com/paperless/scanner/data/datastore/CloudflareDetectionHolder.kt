package com.paperless.scanner.data.datastore

import com.paperless.scanner.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thread-safe, non-blocking Cloudflare-detection cache for the OkHttp request
 * hot path.
 *
 * Resolves issue #122 (F-095): the adaptive write-timeout interceptor needs
 * to know whether the configured server sits behind Cloudflare to decide the
 * upper bound for write timeouts. Reading
 * [TokenManager.isServerUsingCloudflareSync] would re-introduce the same
 * DataStore `runBlocking` cost that PR #197 removed for the server URL —
 * every interceptor invocation would block on the DataStore lock.
 *
 * Mirrors the [ServerUrlHolder] pattern: a single coroutine launched on the
 * application-wide scope collects [TokenManager.serverUsesCloudflare] and
 * atomically updates the cache on every emission. The interceptor reads via
 * [current], a single atomic load — no blocking, no lock, no contention.
 *
 * **Initial-emission window:** between singleton construction and the first
 * Flow emission, [current] returns `null`. Callers should treat `null` as
 * "not detected yet" — equivalent to `false` for cap decisions — so an
 * upload during this brief window simply runs with the un-capped timeout.
 */
@Singleton
class CloudflareDetectionHolder @Inject constructor(
    tokenManager: TokenManager,
    @ApplicationScope scope: CoroutineScope,
) {
    private val cache = AtomicReference<Boolean?>(null)

    init {
        scope.launch {
            tokenManager.serverUsesCloudflare.collect { detected ->
                cache.set(detected)
            }
        }
    }

    /**
     * Latest known Cloudflare detection state, or `null` if the launched
     * collector has not yet processed the first Flow emission. Pure atomic
     * read — safe to call from any thread, including OkHttp's dispatcher.
     */
    fun current(): Boolean? = cache.get()
}
