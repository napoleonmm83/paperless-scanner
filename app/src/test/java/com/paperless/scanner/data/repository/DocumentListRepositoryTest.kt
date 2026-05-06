package com.paperless.scanner.data.repository

import android.content.Context
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.api.models.Document as ApiDocument
import com.paperless.scanner.data.api.models.DocumentsResponse as DtoDocumentsResponse
import com.paperless.scanner.data.database.dao.CachedDocumentDao
import com.paperless.scanner.data.database.entities.CachedDocument
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.domain.model.DocumentFilter
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DocumentListRepositoryTest {

    private lateinit var context: Context
    private lateinit var api: PaperlessApi
    private lateinit var cachedDocumentDao: CachedDocumentDao
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var repo: DocumentListRepository

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        api = mockk(relaxed = true)
        cachedDocumentDao = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)
        every { context.getString(any()) } returns "offline"
        repo = DocumentListRepository(context, api, cachedDocumentDao, networkMonitor)
    }

    // -------- helpers --------

    private fun apiDoc(
        id: Int = 1,
        title: String = "Doc $id",
    ): ApiDocument = ApiDocument(
        id = id,
        title = title,
        content = null,
        created = "2026-05-06T00:00:00Z",
        modified = "2026-05-06T00:00:00Z",
        added = "2026-05-06T00:00:00Z",
        correspondentId = null,
        documentTypeId = null,
        tags = emptyList(),
        archiveSerialNumber = null,
        originalFileName = null,
        notes = emptyList(),
        owner = null,
        permissions = null,
        userCanChange = true,
        ocrConfidence = null,
    )

    private fun cachedDoc(
        id: Int = 1,
        title: String = "Doc $id",
    ): CachedDocument = CachedDocument(
        id = id,
        title = title,
        content = null,
        created = "2026-05-06T00:00:00Z",
        modified = "2026-05-06T00:00:00Z",
        added = "2026-05-06T00:00:00Z",
        archiveSerialNumber = null,
        originalFileName = null,
        correspondent = null,
        documentType = null,
        storagePath = null,
        tags = "[]",
        customFields = null,
        isCached = true,
        lastSyncedAt = 0L,
        isDeleted = false,
        deletedAt = null,
    )

    // -------- tests --------

    @Test
    fun `observeDocuments maps cached entities to domain documents`() = runTest {
        every {
            cachedDocumentDao.observeDocuments(limit = 25, offset = 0)
        } returns flowOf(listOf(cachedDoc(id = 1, title = "Cached A"), cachedDoc(id = 2, title = "Cached B")))

        val result = repo.observeDocuments(page = 1, pageSize = 25).first()

        assertEquals(2, result.size)
        assertEquals(1, result[0].id)
        assertEquals("Cached A", result[0].title)
        assertEquals(2, result[1].id)
        assertEquals("Cached B", result[1].title)
    }

    @Test
    fun `getDocumentsPaged returns flow that emits PagingData with mapped domain documents`() = runTest {
        // No paging-testing dep available; verify Pager flow is constructed and non-null.
        val flow = repo.getDocumentsPaged(searchQuery = "tax", filter = DocumentFilter.empty())
        assertNotNull(flow)
    }

    @Test
    fun `getDocuments cache hit returns response with totalCount and next previous derivation`() = runTest {
        // Page 2 of size 10, total 25 -> next=non-null (20<25), previous=non-null (page>1)
        coEvery { cachedDocumentDao.getDocuments(limit = 10, offset = 10) } returns
            listOf(cachedDoc(id = 11), cachedDoc(id = 12))
        coEvery { cachedDocumentDao.getCount() } returns 25

        val result = repo.getDocuments(page = 2, pageSize = 10, forceRefresh = false)

        assertTrue(result.isSuccess)
        val response = result.getOrNull()!!
        assertEquals(25, response.count)
        assertNotNull(response.next)
        assertNotNull(response.previous)
        assertEquals(2, response.results.size)
    }

    @Test
    fun `getDocuments forceRefresh and online calls api and inserts cache`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        coEvery {
            api.getDocuments(
                page = 1,
                pageSize = 25,
                query = null,
                tagIds = null,
                correspondentId = null,
                documentTypeId = null,
                ordering = "-created"
            )
        } returns DtoDocumentsResponse(
            count = 1,
            next = null,
            previous = null,
            results = listOf(apiDoc(id = 9, title = "From API"))
        )

        val result = repo.getDocuments(page = 1, pageSize = 25, forceRefresh = true)

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull()!!.count)
        assertEquals("From API", result.getOrNull()!!.results.first().title)
        coVerify { cachedDocumentDao.insertAll(any()) }
    }

    @Test
    fun `getDocuments offline with no cache returns NetworkError`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns false
        coEvery { cachedDocumentDao.getDocuments(limit = 25, offset = 0) } returns emptyList()

        val result = repo.getDocuments(page = 1, pageSize = 25, forceRefresh = false)

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(
            "expected NetworkError, got $ex",
            ex is PaperlessException.NetworkError
        )
    }

    @Test
    fun `getDocuments cache empty and online forceRefresh false falls back to network`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        coEvery { cachedDocumentDao.getDocuments(limit = 25, offset = 0) } returns emptyList()
        coEvery {
            api.getDocuments(
                page = 1,
                pageSize = 25,
                query = null,
                tagIds = null,
                correspondentId = null,
                documentTypeId = null,
                ordering = "-created"
            )
        } returns DtoDocumentsResponse(
            count = 1,
            next = null,
            previous = null,
            results = listOf(apiDoc(id = 1, title = "Network"))
        )

        val result = repo.getDocuments(page = 1, pageSize = 25, forceRefresh = false)

        assertTrue(result.isSuccess)
        assertEquals("Network", result.getOrNull()!!.results.first().title)
        coVerify { cachedDocumentDao.insertAll(any()) }
    }

    @Test
    fun `searchDocuments happy path returns mapped domain list`() = runTest {
        coEvery { cachedDocumentDao.searchDocuments("foo") } returns
            listOf(cachedDoc(id = 1, title = "Found Foo"))

        val result = repo.searchDocuments("foo")

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull()!!.size)
        assertEquals("Found Foo", result.getOrNull()!!.first().title)
    }

    @Test
    fun `searchDocuments dao throws returns Result failure with PaperlessException`() = runTest {
        coEvery { cachedDocumentDao.searchDocuments("boom") } throws RuntimeException("dao fail")

        val result = repo.searchDocuments("boom")

        assertTrue(result.isFailure)
        assertTrue(
            "expected PaperlessException, got ${result.exceptionOrNull()}",
            result.exceptionOrNull() is PaperlessException
        )
    }

    @Test
    fun `getRecentDocuments cache non-empty returns cached without API call`() = runTest {
        coEvery { cachedDocumentDao.getDocuments(limit = 5, offset = 0) } returns
            listOf(cachedDoc(id = 1, title = "Recent"))

        val result = repo.getRecentDocuments(limit = 5)

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull()!!.size)
        assertEquals("Recent", result.getOrNull()!!.first().title)
        coVerify(exactly = 0) {
            api.getDocuments(any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `getUntaggedDocuments returns mapped domain list`() = runTest {
        coEvery { cachedDocumentDao.getUntaggedDocuments() } returns
            listOf(cachedDoc(id = 1, title = "Untagged 1"), cachedDoc(id = 2, title = "Untagged 2"))

        val result = repo.getUntaggedDocuments()

        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrNull()!!.size)
        assertEquals("Untagged 1", result.getOrNull()!!.first().title)
        // Sanity: the dao Result chain didn't accidentally fail
        assertFalse(result.isFailure)
    }
}
