package com.paperless.scanner.data.api

import android.util.Log
import com.paperless.scanner.data.api.models.PaginatedResponse
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [fetchAllPages] (Issue #126).
 *
 * Pure JVM: [android.util.Log] is statically mocked because the safety-cap path
 * logs a warning. A tiny in-memory [FakePage] stands in for the real `*Response`
 * DTOs so the pagination logic is exercised without any Android/HTTP stack.
 */
class ApiPaginationTest {

    private data class FakePage<T>(
        override val next: String?,
        override val results: List<T>
    ) : PaginatedResponse<T>

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.w(any<String>(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `fetchAllPages fetches a single page when next is null`() = runTest {
        var calls = 0
        val result = fetchAllPages { page ->
            calls++
            FakePage(next = null, results = listOf("a$page", "b$page"))
        }

        assertEquals(listOf("a1", "b1"), result)
        assertEquals(1, calls)
    }

    @Test
    fun `fetchAllPages walks every page until next is null and concatenates results in order`() =
        runTest {
            val pages = listOf(
                FakePage(next = "https://x/?page=2", results = listOf(1, 2, 3)),
                FakePage(next = "https://x/?page=3", results = listOf(4, 5)),
                FakePage(next = null, results = listOf(6))
            )
            val requested = mutableListOf<Int>()

            val result = fetchAllPages { page ->
                requested += page
                pages[page - 1]
            }

            assertEquals(listOf(1, 2, 3, 4, 5, 6), result)
            assertEquals(listOf(1, 2, 3), requested)
        }

    @Test
    fun `fetchAllPages stops at the maxPages cap when the server never clears next`() = runTest {
        var calls = 0
        val result = fetchAllPages(maxPages = 3) { page ->
            calls++
            FakePage(next = "https://x/?page=${page + 1}", results = listOf(page))
        }

        // Capped: only 3 pages fetched, results gathered so far returned (never unbounded).
        assertEquals(3, calls)
        assertEquals(listOf(1, 2, 3), result)
    }

    @Test
    fun `fetchAllPages fails at the cap when failOnCap is set`() = runTest {
        // Callers that write the walk through to a cache must not persist a
        // truncated list as the complete truth — a missing in-flight task would
        // be indistinguishable from "the server has none".
        var calls = 0
        var thrown: Throwable? = null

        try {
            fetchAllPages(maxPages = 3, failOnCap = true) { page ->
                calls++
                FakePage(next = "https://x/?page=${page + 1}", results = listOf(page))
            }
        } catch (e: IllegalStateException) {
            thrown = e
        }

        assertNotNull("the capped walk must fail instead of returning a partial list", thrown)
        // Still bounded by the cap — failing must not mean walking forever.
        assertEquals(3, calls)
    }

    @Test
    fun `fetchAllPages with failOnCap returns normally when the walk completes`() = runTest {
        // The flag must only bite on truncation, never on an ordinary short walk.
        val result = fetchAllPages(maxPages = 3, failOnCap = true) { page ->
            FakePage(next = if (page < 2) "https://x/?page=2" else null, results = listOf(page))
        }

        assertEquals(listOf(1, 2), result)
    }

    @Test
    fun `fetchAllPages treats a blank next as the last page`() = runTest {
        var calls = 0
        val result = fetchAllPages { page ->
            calls++
            FakePage(next = "", results = listOf(page))
        }

        assertEquals(1, calls)
        assertEquals(listOf(1), result)
    }
}
