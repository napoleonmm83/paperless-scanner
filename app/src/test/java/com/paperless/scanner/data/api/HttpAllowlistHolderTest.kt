package com.paperless.scanner.data.api

import com.paperless.scanner.data.datastore.TokenManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HttpAllowlistHolderTest {

    /**
     * Tests use [UnconfinedTestDispatcher] so the launched collector inside
     * [HttpAllowlistHolder.init] runs synchronously inline — assertions made
     * immediately after the constructor see the cache populated. Tests that
     * drive a [MutableStateFlow] cancel the scope explicitly so the collector
     * does not leak across test methods. Pattern mirrors `ServerUrlHolderTest`.
     */

    @Test
    fun `snapshot returns empty set when Flow emits empty list`() = runTest {
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.acceptedHttpHostsFlow } returns flowOf(emptyList())
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

        val holder = HttpAllowlistHolder(tokenManager, scope)

        assertEquals(emptySet<String>(), holder.snapshot())
    }

    @Test
    fun `snapshot returns hosts from initial Flow emission`() = runTest {
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.acceptedHttpHostsFlow } returns
            flowOf(listOf("paperless.lan", "192.168.1.42"))
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

        val holder = HttpAllowlistHolder(tokenManager, scope)

        assertEquals(setOf("paperless.lan", "192.168.1.42"), holder.snapshot())
    }

    @Test
    fun `snapshot lowercases hosts`() = runTest {
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.acceptedHttpHostsFlow } returns
            flowOf(listOf("Paperless.LAN", "MyServer.Local"))
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

        val holder = HttpAllowlistHolder(tokenManager, scope)

        assertEquals(setOf("paperless.lan", "myserver.local"), holder.snapshot())
    }

    @Test
    fun `snapshot updates on Flow emission after construction`() = runTest {
        val flow = MutableStateFlow(listOf("first.lan"))
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.acceptedHttpHostsFlow } returns flow.asStateFlow()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

        try {
            val holder = HttpAllowlistHolder(tokenManager, scope)
            assertEquals(setOf("first.lan"), holder.snapshot())

            flow.value = listOf("first.lan", "second.lan")

            assertEquals(setOf("first.lan", "second.lan"), holder.snapshot())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `snapshot becomes empty when allowlist is cleared`() = runTest {
        val flow = MutableStateFlow(listOf("paperless.lan"))
        val tokenManager = mockk<TokenManager>()
        every { tokenManager.acceptedHttpHostsFlow } returns flow.asStateFlow()
        val scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

        try {
            val holder = HttpAllowlistHolder(tokenManager, scope)
            assertEquals(setOf("paperless.lan"), holder.snapshot())

            flow.value = emptyList()

            assertTrue(holder.snapshot().isEmpty())
        } finally {
            scope.cancel()
        }
    }
}
