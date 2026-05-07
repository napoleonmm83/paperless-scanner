package com.paperless.scanner.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import retrofit2.HttpException
import java.io.IOException
import kotlin.math.pow

/**
 * Retries [block] with exponential backoff on transient failures.
 *
 * Replaces the synchronous OkHttp interceptor that called Thread.sleep on the
 * dispatcher thread. Runs at the suspend boundary so concurrent requests are
 * unaffected during backoff windows.
 *
 * Retries on [IOException] and on [HttpException] with status code in 500..599.
 * Other [HttpException]s (e.g. 4xx) are not retried. [CancellationException]
 * is rethrown immediately and never counted as a retryable failure — the
 * inheritance-aware catch order matters here (see PR #195).
 */
suspend fun <T> withRetry(
    maxRetries: Int = NetworkConfig.MAX_RETRIES,
    initialDelayMs: Long = NetworkConfig.RETRY_DELAY_MS,
    maxDelayMs: Long = NetworkConfig.RETRY_MAX_DELAY_MS,
    block: suspend () -> T
): T {
    var attempt = 0
    while (true) {
        try {
            return block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: HttpException) {
            if (e.code() !in 500..599 || attempt >= maxRetries) throw e
        } catch (e: IOException) {
            if (attempt >= maxRetries) throw e
        }

        val delayMs = (initialDelayMs * 2.0.pow(attempt.toDouble())).toLong()
            .coerceAtMost(maxDelayMs)
        delay(delayMs)
        attempt++
    }
}
