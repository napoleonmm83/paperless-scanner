package com.paperless.scanner.data.repository

import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.database.dao.CachedDocumentDao
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.data.api.models.DocumentsResponse
import com.paperless.scanner.domain.model.DocumentFilter
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DocumentCountRepositoryTest {

    private lateinit var api: PaperlessApi
    private lateinit var dao: CachedDocumentDao
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var repo: DocumentCountRepository

    @Before
    fun setup() {
        api = mockk(relaxed = true)
        dao = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)
        repo = DocumentCountRepository(api, dao, networkMonitor)
    }

    @Test
    fun `observeCountWithFilter delegates to dao getCountWithFilter`() = runTest {
        every { dao.getCountWithFilter(any()) } returns flowOf(7)
        val result = repo.observeCountWithFilter(searchQuery = "tax", filter = DocumentFilter.empty()).first()
        assertEquals(7, result)
    }

    @Test
    fun `observeUntaggedDocumentsCount delegates to dao observeUntaggedCount`() = runTest {
        every { dao.observeUntaggedCount() } returns flowOf(3)
        val result = repo.observeUntaggedDocumentsCount().first()
        assertEquals(3, result)
    }

    @Test
    fun `getDocumentCount with forceRefresh true and online fetches from API`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        coEvery { api.getDocuments(page = 1, pageSize = 1) } returns
            DocumentsResponse(count = 42, next = null, previous = null, results = emptyList())
        val result = repo.getDocumentCount(forceRefresh = true)
        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `getDocumentCount without forceRefresh and cache positive returns cached value`() = runTest {
        coEvery { dao.getCount() } returns 11
        val result = repo.getDocumentCount(forceRefresh = false)
        assertEquals(11, result.getOrNull())
    }

    @Test
    fun `getDocumentCount offline with empty cache returns success of 0`() = runTest {
        coEvery { dao.getCount() } returns 0
        coEvery { networkMonitor.checkOnlineStatus() } returns false
        val result = repo.getDocumentCount(forceRefresh = false)
        assertEquals(0, result.getOrNull())
    }

    @Test
    fun `getDocumentCount with forceRefresh true and offline falls back to cache`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns false
        coEvery { dao.getCount() } returns 5
        val result = repo.getDocumentCount(forceRefresh = true)
        assertEquals(5, result.getOrNull())
    }

    @Test
    fun `getUntaggedCount returns count from API`() = runTest {
        coEvery { api.getDocuments(page = 1, pageSize = 1, tagsIsNull = true) } returns
            DocumentsResponse(count = 9, next = null, previous = null, results = emptyList())
        val result = repo.getUntaggedCount()
        assertEquals(9, result.getOrNull())
    }

    @Test
    fun `getUntaggedCount returns failure when API throws`() = runTest {
        coEvery { api.getDocuments(page = 1, pageSize = 1, tagsIsNull = true) } throws RuntimeException("boom")
        val result = repo.getUntaggedCount()
        assertTrue(result.isFailure)
        assertTrue(
            "expected PaperlessException, got ${result.exceptionOrNull()}",
            result.exceptionOrNull() is com.paperless.scanner.data.api.PaperlessException
        )
    }
}
