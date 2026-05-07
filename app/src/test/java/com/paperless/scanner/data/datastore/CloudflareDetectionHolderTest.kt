package com.paperless.scanner.data.datastore

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CloudflareDetectionHolderTest {

    /**
     * Tests use [UnconfinedTestDispatcher] so the launched collector inside
     * [CloudflareDetectionHolder.init] runs synchronously inline — assertions
     * made immediately after the constructor see the cache populated. For
     * tests that drive a [MutableStateFlow] (collector stays open across
     * emissions), the per-test [CoroutineScope] is cancelled explicitly via
     * try/finally so the collector does not leak across test methods.
     */

    @Test
    fun `current returns primed value when initial Flow emission is true`() = runTest {
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.serverUsesCloudflare } returns flowOf(true)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

        val holder = CloudflareDetectionHolder(tokenManager, scope)

        assertEquals(true, holder.current())
    }

    @Test
    fun `current returns primed value when initial Flow emission is false`() = runTest {
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.serverUsesCloudflare } returns flowOf(false)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

        val holder = CloudflareDetectionHolder(tokenManager, scope)

        assertEquals(false, holder.current())
    }

    @Test
    fun `current updates from false to true on Flow emission`() = runTest {
        val flow = MutableStateFlow(false)
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.serverUsesCloudflare } returns flow.asStateFlow()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

        try {
            val holder = CloudflareDetectionHolder(tokenManager, scope)
            assertEquals(false, holder.current())

            flow.value = true

            assertEquals(true, holder.current())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `current updates from true to false on logout-like emission`() = runTest {
        val flow = MutableStateFlow(true)
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.serverUsesCloudflare } returns flow.asStateFlow()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

        try {
            val holder = CloudflareDetectionHolder(tokenManager, scope)
            assertEquals(true, holder.current())

            flow.value = false

            assertEquals(false, holder.current())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `current returns null before any Flow emission has been processed`() = runTest {
        // Use a Flow that never emits to simulate the initial-emission window.
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.serverUsesCloudflare } returns flow {
            // Never emit — the holder's launched collector suspends forever.
            awaitCancellation()
        }
        // UnconfinedTestDispatcher starts the launched coroutine inline so it
        // reaches its (suspended) collect{} before construction returns.
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

        try {
            val holder = CloudflareDetectionHolder(tokenManager, scope)
            // The coroutine has started but its collect{} suspended before
            // emitting; cache is still null.
            assertNull(holder.current())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `current is safe when read concurrently from many coroutines`() = runTest {
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.serverUsesCloudflare } returns flowOf(true)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

        val holder = CloudflareDetectionHolder(tokenManager, scope)

        // 100 parallel reads — AtomicReference.get() is lock-free, every
        // call must see the same primed value.
        val results = (1..100)
            .map { async { holder.current() } }
            .awaitAll()
            .toSet()
        assertEquals(setOf<Boolean?>(true), results)
    }
}
