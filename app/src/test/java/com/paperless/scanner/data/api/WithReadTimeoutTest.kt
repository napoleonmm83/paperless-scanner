package com.paperless.scanner.data.api

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Unit tests for [withReadTimeout] (#82) — a coroutine-level total timeout around a single
 * network read that surfaces a stall as a network failure without swallowing cancellation.
 */
class WithReadTimeoutTest {

    @Test
    fun `returns the block result when it completes within the timeout`() = runTest {
        val result = withReadTimeout(timeoutMs = 10_000) { "ok" }
        assertEquals("ok", result)
    }

    @Test
    fun `converts a read that exceeds the timeout into an IOException`() = runTest {
        val thrown = runCatchingThrowable {
            withReadTimeout(timeoutMs = 50) {
                delay(10_000)
                "never"
            }
        }
        assertTrue("expected IOException, got $thrown", thrown is IOException)
        assertFalse("a timeout must not surface as raw CancellationException", thrown is CancellationException)
    }

    @Test
    fun `propagates a genuine CancellationException unchanged (does not convert to IOException)`() = runTest {
        val thrown = runCatchingThrowable {
            withReadTimeout(timeoutMs = 10_000) {
                throw CancellationException("outer scope cancelled")
            }
        }
        assertTrue("expected CancellationException, got $thrown", thrown is CancellationException)
        assertFalse("genuine cancellation must not be converted to IOException", thrown is IOException)
    }

    @Test
    fun `propagates an outer withTimeout cancellation instead of converting it to IOException`() = runTest {
        val thrown = runCatchingThrowable {
            // A caller wraps the read in its OWN, shorter timeout. The outer timeout fires
            // first; that cancellation must surface as cancellation, not a masked IOException
            // (otherwise the surrounding fetchAllPages/withRetry would retry a cancel).
            withTimeout(50) {
                withReadTimeout(timeoutMs = 10_000) {
                    delay(10_000)
                    "never"
                }
            }
        }
        assertTrue("expected the outer cancellation to propagate, got $thrown", thrown is CancellationException)
        assertFalse("an outer cancellation must not be converted to IOException", thrown is IOException)
    }

    private inline fun runCatchingThrowable(block: () -> Unit): Throwable? =
        try {
            block()
            null
        } catch (e: Throwable) {
            e
        }
}
