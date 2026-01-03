package com.paperless.scanner.data.api

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import java.util.logging.Logger
import kotlin.math.pow

class RetryInterceptor(
    private val maxRetries: Int = 3,
    private val initialDelayMs: Long = 1000L,
    private val maxDelayMs: Long = 10000L
) : Interceptor {

    companion object {
        private val logger = Logger.getLogger(RetryInterceptor::class.java.name)
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var lastException: IOException? = null
        var response: Response? = null

        for (attempt in 0..maxRetries) {
            try {
                // Close previous response if exists
                response?.close()

                response = chain.proceed(request)

                // If successful or client error (4xx), don't retry
                if (response.isSuccessful || response.code in 400..499) {
                    return response
                }

                // Server error (5xx) - might be temporary, retry
                if (response.code in 500..599 && attempt < maxRetries) {
                    logger.log(Level.WARNING, "Server error ${response.code}, attempt ${attempt + 1}/$maxRetries")
                    response.close()
                    delay(attempt)
                    continue
                }

                return response

            } catch (e: IOException) {
                lastException = e
                logger.log(Level.WARNING, "Network error on attempt ${attempt + 1}/$maxRetries: ${e.message}")

                if (attempt < maxRetries) {
                    delay(attempt)
                } else {
                    throw e
                }
            }
        }

        // Should not reach here, but just in case
        throw lastException ?: IOException("Unknown error after $maxRetries retries")
    }

    private fun delay(attempt: Int) {
        val delayMs = (initialDelayMs * 2.0.pow(attempt.toDouble())).toLong()
            .coerceAtMost(maxDelayMs)
        logger.log(Level.FINE, "Waiting ${delayMs}ms before retry")
        try {
            TimeUnit.MILLISECONDS.sleep(delayMs)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IOException("Retry interrupted", e)
        }
    }
}
