package com.paperless.scanner.data.repository

import androidx.test.filters.LargeTest
import app.cash.turbine.test
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.models.DocumentsResponse
import com.paperless.scanner.data.database.dao.CachedDocumentDao
import com.paperless.scanner.data.database.entities.CachedDocument
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.domain.model.DocumentFilter
import com.paperless.scanner.testing.BaseRoomRepositoryTest
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Repository tests for [DocumentCountRepository].
 *
 * Uses a real in-memory Room database (see [BaseRoomRepositoryTest]) so the
 * count queries — including the RawQuery `getCountWithFilter` and the
 * untagged-count Flow — execute against the actual schema. Documents are
 * inserted via the real [CachedDocumentDao] and counts asserted on what
 * Room actually returns.
 */
@LargeTest
class DocumentCountRepositoryTest : BaseRoomRepositoryTest() {

    private lateinit var api: PaperlessApi
    private lateinit var dao: CachedDocumentDao
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var repo: DocumentCountRepository

    @Before
    fun setup() {
        api = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)
        dao = database.cachedDocumentDao()
        repo = DocumentCountRepository(api, dao, networkMonitor)
    }

    private fun doc(
        id: Int,
        tags: String = "[]",
        isDeleted: Boolean = false,
    ): CachedDocument = CachedDocument(
        id = id,
        title = "Doc $id",
        content = null,
        created = "2026-01-01",
        modified = "2026-01-01",
        added = "2026-01-01",
        archiveSerialNumber = null,
        originalFileName = null,
        correspondent = null,
        documentType = null,
        storagePath = null,
        tags = tags,
        customFields = null,
        isDeleted = isDeleted,
    )

    @Test
    fun `observeCountWithFilter reacts to inserts via real DAO RawQuery`() = runTest {
        repo.observeCountWithFilter(searchQuery = null, filter = DocumentFilter.empty()).test {
            // Initial: empty cache → 0.
            assertEquals(0, awaitItem())

            // Mutate after subscription; the RawQuery must invalidate and re-emit.
            dao.insertAll(listOf(doc(id = 1), doc(id = 2), doc(id = 3)))

            assertEquals(3, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeUntaggedDocumentsCount reacts to inserts and ignores tagged or deleted rows`() =
        runTest {
            repo.observeUntaggedDocumentsCount().test {
                // Initial: empty cache → 0.
                assertEquals(0, awaitItem())

                // 2 untagged ("[]"), 1 tagged ("[5]"), 1 deleted (must be excluded).
                dao.insertAll(
                    listOf(
                        doc(id = 1, tags = "[]"),
                        doc(id = 2, tags = "[]"),
                        doc(id = 3, tags = "[5]"),
                        doc(id = 4, tags = "[]", isDeleted = true),
                    )
                )

                assertEquals(2, awaitItem())
                cancelAndIgnoreRemainingEvents()
            }
        }

    @Test
    fun `getDocumentCount with forceRefresh true and online fetches from API`() = runTest {
        every { networkMonitor.checkOnlineStatus() } returns true
        coEvery { api.getDocuments(page = 1, pageSize = 1) } returns
            DocumentsResponse(count = 42, next = null, previous = null, results = emptyList())

        val result = repo.getDocumentCount(forceRefresh = true)

        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `getDocumentCount without forceRefresh and cache positive returns cached value`() = runTest {
        // Insert 11 documents into real DB — getCount() returns the actual count.
        dao.insertAll((1..11).map { doc(id = it) })

        val result = repo.getDocumentCount(forceRefresh = false)

        assertEquals(11, result.getOrNull())
    }

    @Test
    fun `getDocumentCount offline with empty cache returns success of 0`() = runTest {
        every { networkMonitor.checkOnlineStatus() } returns false

        val result = repo.getDocumentCount(forceRefresh = false)

        assertEquals(0, result.getOrNull())
    }

    @Test
    fun `getDocumentCount with forceRefresh true and offline falls back to cache`() = runTest {
        every { networkMonitor.checkOnlineStatus() } returns false
        dao.insertAll((1..5).map { doc(id = it) })

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
