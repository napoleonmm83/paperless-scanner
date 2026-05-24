package com.paperless.scanner.util

import com.paperless.scanner.data.network.CertificatePinMismatchException
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
 * and [CertificatePinMismatchException] are rethrown immediately and never
 * counted as retryable failures — a changed pinned certificate (Issue #36)
 * cannot succeed on retry until the user makes a trust decision, and it extends
 * [IOException], so its catch MUST precede the generic IOException catch.
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
        } catch (e: CertificatePinMismatchException) {
            // Issue #36: a changed pinned cert never recovers via retry. Rethrow
            // before the IOException catch (it extends IOException) so the typed
            // mismatch reaches the caller without backoff churn.
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

/**
 * Retry variant for Retrofit endpoints declared with a `Response<T>` return
 * type. Plain [withRetry] only retries on thrown exceptions; a `Response<T>`
 * never throws on a 5xx — it returns a non-successful response — so the
 * default helper would never retry server errors for these endpoints. This
 * variant promotes 5xx to [HttpException] inside the retry boundary so server
 * errors are retried with the same backoff semantics as for unwrapped
 * suspend functions.
 *
 * 4xx responses are returned unchanged for the caller to handle.
 *
 * Use this only on endpoints whose retry is safe (idempotent: GET, DELETE,
 * PUT, and operations like acknowledge / restore / trash-bulk-action).
 * Non-idempotent POSTs (create*, addNote) must NOT be wrapped — see PR #196
 * for the duplicate-creation rationale.
 */
suspend fun <T> withResponseRetry(
    maxRetries: Int = NetworkConfig.MAX_RETRIES,
    initialDelayMs: Long = NetworkConfig.RETRY_DELAY_MS,
    maxDelayMs: Long = NetworkConfig.RETRY_MAX_DELAY_MS,
    block: suspend () -> retrofit2.Response<T>,
): retrofit2.Response<T> = withRetry(maxRetries, initialDelayMs, maxDelayMs) {
    val response = block()
    if (response.code() in 500..599) {
        throw HttpException(response)
    }
    response
}
