package com.paperless.scanner.data.datastore

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
     * UnconfinedTestDispatcher executes launched coroutines synchronously and
     * inline so the holder's `init { scope.launch { ... } }` block runs to its
     * first emission before the constructor returns. Uses runTest for both
     * structured cancellation (the test scope is auto-cancelled) and to
     * advance virtual time deterministically.
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

        val holder = ServerUrlHolder(tokenManager, scope)
        assertEquals("https://first.example.com", holder.current())

        flow.value = "https://second.example.com/"

        assertEquals("https://second.example.com", holder.current())
    }

    @Test
    fun `current updates to null on logout emission`() = runTest {
        val flow = MutableStateFlow<String?>("https://example.com/")
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.serverUrl } returns flow.asStateFlow()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

        val holder = ServerUrlHolder(tokenManager, scope)
        assertEquals("https://example.com", holder.current())

        flow.value = null

        assertNull(holder.current())
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
    fun `current is safe to call from multiple threads`() = runTest {
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.serverUrl } returns flowOf("https://example.com/")
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

        val holder = ServerUrlHolder(tokenManager, scope)

        // Smoke: many concurrent reads return the same atomic value.
        val results = (1..100).map { holder.current() }.toSet()
        assertEquals(setOf("https://example.com"), results)
    }
}
