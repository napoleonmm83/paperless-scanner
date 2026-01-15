package com.paperless.scanner.utils

import kotlinx.coroutines.delay
import kotlin.math.pow

/**
 * Utility for retry logic with exponential backoff.
 *
 * Usage:
 * ```
 * val result = retryWithExponentialBackoff(
 *     maxAttempts = 3,
 *     initialDelay = 2000L,
 *     onRetry = { attempt -> Log.d("Retry", "Attempt $attempt") }
 * ) {
 *     api.uploadDocument()
 * }
 * ```
 */
object RetryUtil {
    /**
     * Executes a block with retry logic and exponential backoff.
     *
     * @param maxAttempts Maximum number of attempts (default: 3)
     * @param initialDelay Initial delay in milliseconds (default: 2000ms = 2s)
     * @param maxDelay Maximum delay between retries (default: 16000ms = 16s)
     * @param factor Exponential backoff factor (default: 2.0)
     * @param shouldRetry Determines if error is retryable (default: checks for network/timeout errors)
     * @param onRetry Callback when retry is attempted
     * @param block The operation to execute
     * @return Result of the operation
     */
    suspend fun <T> retryWithExponentialBackoff(
        maxAttempts: Int = 3,
        initialDelay: Long = 2000L,
        maxDelay: Long = 16000L,
        factor: Double = 2.0,
        shouldRetry: (Throwable) -> Boolean = ::isRetryableError,
        onRetry: (suspend (attempt: Int, delay: Long, error: Throwable) -> Unit)? = null,
        block: suspend () -> T
    ): Result<T> {
        var currentDelay = initialDelay
        var lastException: Throwable? = null

        repeat(maxAttempts) { attempt ->
            try {
                return Result.success(block())
            } catch (e: Exception) {
                lastException = e

                // Check if we should retry this error
                if (!shouldRetry(e)) {
                    return Result.failure(e)
                }

                // Check if we have more attempts
                if (attempt < maxAttempts - 1) {
                    onRetry?.invoke(attempt + 1, currentDelay, e)
                    delay(currentDelay)

                    // Calculate next delay with exponential backoff
                    currentDelay = (currentDelay * factor).toLong().coerceAtMost(maxDelay)
                }
            }
        }

        // All attempts failed
        return Result.failure(lastException ?: Exception("All retry attempts failed"))
    }

    /**
     * Determines if an error is retryable (temporary network/server issues).
     * Returns true for:
     * - Network errors (IOException, SocketTimeoutException, etc.)
     * - Server errors (500, 502, 503, 504)
     *
     * Returns false for:
     * - Auth errors (401, 403)
     * - Client errors (400, 404, etc.)
     * - Content errors (invalid data)
     */
    fun isRetryableError(error: Throwable): Boolean {
        return when {
            // Network errors - always retryable
            error is java.net.SocketTimeoutException -> true
            error is java.net.ConnectException -> true
            error is java.net.UnknownHostException -> true
            error is java.io.IOException -> {
                val message = error.message?.lowercase() ?: ""
                // Retry on network errors, but not on "file not found" etc.
                message.contains("network") ||
                message.contains("timeout") ||
                message.contains("connection") ||
                message.contains("unreachable")
            }

            // HTTP errors - check status code
            error is retrofit2.HttpException -> {
                when (error.code()) {
                    // Server errors (5xx) - retryable
                    in 500..599 -> true

                    // Client errors (4xx) - NOT retryable (except 408, 429)
                    408 -> true  // Request Timeout
                    429 -> true  // Too Many Requests
                    in 400..499 -> false

                    // Other codes - not retryable
                    else -> false
                }
            }

            // Paperless-specific errors
            error is com.paperless.scanner.data.api.PaperlessException -> {
                when (error) {
                    is com.paperless.scanner.data.api.PaperlessException.NetworkError -> true
                    is com.paperless.scanner.data.api.PaperlessException.ServerError -> {
                        error.code >= 500  // Only retry 5xx server errors
                    }
                    else -> false
                }
            }

            // All other errors - not retryable
            else -> false
        }
    }
}
