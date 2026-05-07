package com.paperless.scanner.data.datastore

import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerUrlHolderTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `current returns primed value from initial DataStore read`() {
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.serverUrl } returns flowOf("https://example.com/")

        val holder = ServerUrlHolder(tokenManager, scope)

        // Trailing slash trimmed, value primed at construction time.
        assertEquals("https://example.com", holder.current())
    }

    @Test
    fun `current returns null when DataStore has no server URL`() {
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.serverUrl } returns flowOf(null)

        val holder = ServerUrlHolder(tokenManager, scope)

        assertNull(holder.current())
    }

    @Test
    fun `current updates on Flow emission after construction`() = runTest {
        // StateFlow with seed value so the init runBlocking returns immediately
        // and the Flow stays open for subsequent emissions.
        val flow = MutableStateFlow<String?>("https://first.example.com/")
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.serverUrl } returns flow.asStateFlow()

        val holder = ServerUrlHolder(tokenManager, scope)
        assertEquals("https://first.example.com", holder.current())

        // Login flips to a new URL — the holder's collector picks it up.
        flow.value = "https://second.example.com/"
        // Yield a couple of dispatcher ticks to give the launched collector
        // a chance to process the new emission.
        repeat(20) { kotlinx.coroutines.yield() }
        // Fall back to a tiny real-time sleep if the test dispatcher is too
        // fast for the application-scope coroutine.
        runBlocking { kotlinx.coroutines.delay(50) }

        assertEquals("https://second.example.com", holder.current())
    }

    @Test
    fun `current updates to null on logout emission`() = runTest {
        val flow = MutableStateFlow<String?>("https://example.com/")
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.serverUrl } returns flow.asStateFlow()

        val holder = ServerUrlHolder(tokenManager, scope)
        assertEquals("https://example.com", holder.current())

        flow.value = null
        repeat(20) { kotlinx.coroutines.yield() }
        runBlocking { kotlinx.coroutines.delay(50) }

        assertNull(holder.current())
    }

    @Test
    fun `current strips trailing slash`() {
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.serverUrl } returns flowOf("https://example.com/")

        val holder = ServerUrlHolder(tokenManager, scope)
        assertEquals("https://example.com", holder.current())
    }

    @Test
    fun `current preserves URL without trailing slash`() {
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.serverUrl } returns flowOf("https://example.com")

        val holder = ServerUrlHolder(tokenManager, scope)
        assertEquals("https://example.com", holder.current())
    }

    @Test
    fun `current is safe to call from multiple threads`() {
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.serverUrl } returns flowOf("https://example.com/")

        val holder = ServerUrlHolder(tokenManager, scope)

        // Smoke: many concurrent reads return the same atomic value.
        val results = (1..100).map { holder.current() }.toSet()
        assertEquals(setOf("https://example.com"), results)
    }

    @Suppress("unused")
    private fun staticFlow(value: String?): Flow<String?> = flowOf(value)
}
