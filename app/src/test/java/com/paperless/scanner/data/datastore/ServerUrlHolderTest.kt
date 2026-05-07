package com.paperless.scanner.data.datastore

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ServerUrlHolderTest {

    /**
     * Tests use [UnconfinedTestDispatcher] so the launched collector inside
     * [ServerUrlHolder.init] runs synchronously inline — assertions made
     * immediately after the constructor see the cache populated. For tests
     * that drive a [MutableStateFlow] (collector stays open across emissions),
     * the per-test [CoroutineScope] is cancelled explicitly via try/finally
     * so the collector does not leak across test methods. Tests that use
     * [flowOf] don't need this since the flow completes itself and the
     * launched coroutine ends naturally.
     */

    @Test
    fun `current returns primed value from initial Flow emission`() = runTest {
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.serverUrl } returns flowOf("https://example.com/")
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

        val holder = ServerUrlHolder(tokenManager, scope)

        assertEquals("https://example.com", holder.current())
    }

    @Test
    fun `current returns null when DataStore Flow emits null`() = runTest {
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.serverUrl } returns flowOf(null)
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

        val holder = ServerUrlHolder(tokenManager, scope)

        assertNull(holder.current())
    }

    @Test
    fun `current updates on Flow emission after construction`() = runTest {
        val flow = MutableStateFlow<String?>("https://first.example.com/")
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.serverUrl } returns flow.asStateFlow()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

        try {
            val holder = ServerUrlHolder(tokenManager, scope)
            assertEquals("https://first.example.com", holder.current())

            flow.value = "https://second.example.com/"

            assertEquals("https://second.example.com", holder.current())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `current updates to null on logout emission`() = runTest {
        val flow = MutableStateFlow<String?>("https://example.com/")
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.serverUrl } returns flow.asStateFlow()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

        try {
            val holder = ServerUrlHolder(tokenManager, scope)
            assertEquals("https://example.com", holder.current())

            flow.value = null

            assertNull(holder.current())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `current strips trailing slash`() = runTest {
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.serverUrl } returns flowOf("https://example.com/")
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

        val holder = ServerUrlHolder(tokenManager, scope)

        assertEquals("https://example.com", holder.current())
    }

    @Test
    fun `current preserves URL without trailing slash`() = runTest {
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.serverUrl } returns flowOf("https://example.com")
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

        val holder = ServerUrlHolder(tokenManager, scope)

        assertEquals("https://example.com", holder.current())
    }

    @Test
    fun `current is safe when read concurrently from many coroutines`() = runTest {
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.serverUrl } returns flowOf("https://example.com/")
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

        val holder = ServerUrlHolder(tokenManager, scope)

        // Launch 100 concurrent reads. AtomicReference.get() is lock-free so
        // every call sees the same primed value; this exercises true
        // parallelism rather than the previous sequential `.map`.
        val results = (1..100)
            .map { async { holder.current() } }
            .awaitAll()
            .toSet()
        assertEquals(setOf("https://example.com"), results)
    }
}
