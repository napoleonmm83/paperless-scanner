package com.paperless.scanner.data.repository

import androidx.test.filters.LargeTest
import com.google.gson.Gson
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.models.CreateTagRequest
import com.paperless.scanner.data.api.models.Tag
import com.paperless.scanner.data.api.models.TagsResponse
import com.paperless.scanner.data.database.dao.CachedDocumentDao
import com.paperless.scanner.data.database.dao.CachedTagDao
import com.paperless.scanner.data.database.dao.PendingChangeDao
import com.paperless.scanner.data.database.entities.CachedDocument
import com.paperless.scanner.data.database.entities.CachedTag
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.data.service.DocumentSerializer
import com.paperless.scanner.testing.BaseRoomRepositoryTest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import retrofit2.Response
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Repository tests for [TagRepository].
 *
 * Uses a real in-memory Room database (see [BaseRoomRepositoryTest]) so DAO
 * behavior — onConflict REPLACE, soft delete, ordering, Flow emissions — is
 * verified for real instead of mocked away. Only the network/system boundary
 * collaborators (`PaperlessApi`, `NetworkMonitor`) are mocked.
 *
 * Marked `@LargeTest` because the suite exercises a real Room schema, which
 * is heavier than pure unit tests (per Issue #137 acceptance criteria).
 */
@LargeTest
class TagRepositoryTest : BaseRoomRepositoryTest() {

    private lateinit var api: PaperlessApi
    private lateinit var cachedTagDao: CachedTagDao
    private lateinit var cachedDocumentDao: CachedDocumentDao
    private lateinit var pendingChangeDao: PendingChangeDao
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var gson: Gson
    private lateinit var tagRepository: TagRepository

    @Before
    fun setup() {
        api = mockk()
        networkMonitor = mockk(relaxed = true)
        cachedTagDao = database.cachedTagDao()
        cachedDocumentDao = database.cachedDocumentDao()
        pendingChangeDao = database.pendingChangeDao()
        gson = Gson()
        tagRepository = TagRepository(
            api,
            cachedTagDao,
            cachedDocumentDao,
            pendingChangeDao,
            networkMonitor,
            gson,
            DocumentSerializer(gson)
        )
    }

    @Test
    fun `getTags success returns list of tags and persists them to cache`() = runTest {
        every { networkMonitor.checkOnlineStatus() } returns true
        val expectedTags = listOf(
            Tag(id = 1, name = "Invoice", color = "#ff0000"),
            Tag(id = 2, name = "Receipt", color = "#00ff00"),
            Tag(id = 3, name = "Contract", color = "#0000ff")
        )
        coEvery { api.getTags(page = 1, pageSize = 100) } returns TagsResponse(
            count = 3,
            results = expectedTags
        )

        val result = tagRepository.getTags(forceRefresh = true)

        assertTrue(result.isSuccess)
        assertEquals(3, result.getOrNull()?.size)
        coVerify(exactly = 1) { api.getTags(page = 1, pageSize = 100) }
        // Real DB verification: tags actually written and queryable
        val cached = cachedTagDao.getAllTags().sortedBy { it.id }
        assertEquals(listOf(1, 2, 3), cached.map { it.id })
        assertEquals("Invoice", cached[0].name)
    }

    @Test
    fun `getTags with empty list returns empty result and leaves cache empty`() = runTest {
        every { networkMonitor.checkOnlineStatus() } returns true
        coEvery { api.getTags(page = 1, pageSize = 100) } returns TagsResponse(
            count = 0,
            results = emptyList()
        )

        val result = tagRepository.getTags(forceRefresh = true)

        assertTrue(result.isSuccess)
        assertEquals(emptyList<com.paperless.scanner.domain.model.Tag>(), result.getOrNull())
        assertEquals(0, cachedTagDao.getAllTags().size)
    }

    @Test
    fun `getTags network error returns failure`() = runTest {
        every { networkMonitor.checkOnlineStatus() } returns true
        coEvery { api.getTags(page = 1, pageSize = 100) } throws IOException("Network error")

        val result = tagRepository.getTags(forceRefresh = true)

        assertTrue(result.isFailure)
        // PaperlessException wraps the IOException
        assertTrue(result.exceptionOrNull()?.message?.contains("Network error") == true)
    }

    @Test
    fun `getTags returns cached data when offline`() = runTest {
        every { networkMonitor.checkOnlineStatus() } returns false
        cachedTagDao.insert(
            CachedTag(
                id = 42,
                name = "Cached",
                color = null,
                match = null,
                matchingAlgorithm = null,
                isInboxTag = false
            )
        )

        val result = tagRepository.getTags(forceRefresh = true)

        assertTrue(result.isSuccess)
        assertEquals(listOf(42), result.getOrNull()?.map { it.id })
        coVerify(exactly = 0) { api.getTags(any(), any()) }
    }

    @Test
    fun `createTag success returns new tag and inserts into cache`() = runTest {
        val newTag = Tag(id = 10, name = "NewTag", color = "#abcdef")
        coEvery { api.createTag(any()) } returns newTag

        val result = tagRepository.createTag("NewTag", "#abcdef")

        assertTrue(result.isSuccess)
        assertEquals("NewTag", result.getOrNull()?.name)
        assertEquals("#abcdef", result.getOrNull()?.color)
        coVerify { api.createTag(CreateTagRequest(name = "NewTag", color = "#abcdef")) }
        // Real DB verification
        val cached = cachedTagDao.getTag(10)
        assertEquals("NewTag", cached?.name)
        assertEquals("#abcdef", cached?.color)
    }

    @Test
    fun `createTag without color sends null color`() = runTest {
        val newTag = Tag(id = 11, name = "NoColorTag", color = null)
        coEvery { api.createTag(any()) } returns newTag

        val result = tagRepository.createTag("NoColorTag")

        assertTrue(result.isSuccess)
        coVerify { api.createTag(CreateTagRequest(name = "NoColorTag", color = null)) }
        assertEquals(null, cachedTagDao.getTag(11)?.color)
    }

    @Test
    fun `createTag network error returns failure and leaves cache empty`() = runTest {
        coEvery { api.createTag(any()) } throws IOException("Connection refused")

        val result = tagRepository.createTag("FailTag", "#000000")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Connection refused") == true)
        assertEquals(0, cachedTagDao.getAllTags().size)
    }

    @Test
    fun `createTag with duplicate name returns api error`() = runTest {
        coEvery { api.createTag(any()) } throws RuntimeException("Tag already exists")

        val result = tagRepository.createTag("ExistingTag")

        assertTrue(result.isFailure)
        assertEquals("Tag already exists", result.exceptionOrNull()?.message)
    }

    @Test
    fun `deleteTag cascade strips tag id from cached docs and a malformed row does not block others`() = runTest {
        // #61: cascade tag removal deserializes doc.tags via DocumentSerializer; a malformed
        // row must be skipped gracefully without aborting the rest of the cascade (PR #292).
        cachedDocumentDao.insert(cachedDoc(id = 1, tags = "[5,7]"))          // contains target tag
        cachedDocumentDao.insert(cachedDoc(id = 2, tags = "not-valid-json")) // malformed tags JSON
        cachedDocumentDao.insert(cachedDoc(id = 3, tags = "[8,9]"))          // unrelated tags
        coEvery { api.deleteTag(5) } returns Response.success(Unit)

        val result = tagRepository.deleteTag(5)

        assertTrue(result.isSuccess)
        assertEquals("[7]", cachedDocumentDao.getDocument(1)?.tags)            // target stripped
        assertEquals("not-valid-json", cachedDocumentDao.getDocument(2)?.tags) // malformed untouched, did not block
        assertEquals("[8,9]", cachedDocumentDao.getDocument(3)?.tags)          // unrelated unchanged
    }

    private fun cachedDoc(id: Int, tags: String) = CachedDocument(
        id = id,
        title = "Doc $id",
        content = null,
        created = "2026-01-01T00:00:00Z",
        modified = "2026-01-01T00:00:00Z",
        added = "2026-01-01T00:00:00Z",
        archiveSerialNumber = null,
        originalFileName = null,
        correspondent = null,
        documentType = null,
        storagePath = null,
        tags = tags,
        customFields = null
    )
}
