package com.paperless.scanner.data.repository

import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.models.CreateTagRequest
import com.paperless.scanner.data.api.models.Tag
import com.paperless.scanner.data.api.models.TagsResponse
import com.paperless.scanner.data.database.dao.CachedDocumentDao
import com.paperless.scanner.data.database.dao.CachedTagDao
import com.paperless.scanner.data.database.dao.PendingChangeDao
import com.paperless.scanner.data.network.NetworkMonitor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import java.io.IOException

class TagRepositoryTest {

    private lateinit var api: PaperlessApi
    private lateinit var cachedTagDao: CachedTagDao
    private lateinit var cachedDocumentDao: CachedDocumentDao
    private lateinit var pendingChangeDao: PendingChangeDao
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var tagRepository: TagRepository

    @Before
    fun setup() {
        api = mockk()
        cachedTagDao = mockk(relaxed = true)
        cachedDocumentDao = mockk(relaxed = true)
        pendingChangeDao = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)
        tagRepository = TagRepository(
            api,
            cachedTagDao,
            cachedDocumentDao,
            pendingChangeDao,
            networkMonitor
        )
    }

    @Test
    fun `getTags success returns list of tags`() = runTest {
        // Mock: Online, cache empty, force refresh
        every { networkMonitor.checkOnlineStatus() } returns true
        coEvery { cachedTagDao.getAllTags() } returns emptyList()

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
    }

    @Test
    fun `getTags with empty list returns empty result`() = runTest {
        every { networkMonitor.checkOnlineStatus() } returns true
        coEvery { cachedTagDao.getAllTags() } returns emptyList()
        coEvery { api.getTags(page = 1, pageSize = 100) } returns TagsResponse(count = 0, results = emptyList())

        val result = tagRepository.getTags(forceRefresh = true)

        assertTrue(result.isSuccess)
        assertEquals(emptyList<com.paperless.scanner.domain.model.Tag>(), result.getOrNull())
    }

    @Test
    fun `getTags network error returns failure`() = runTest {
        every { networkMonitor.checkOnlineStatus() } returns true
        coEvery { cachedTagDao.getAllTags() } returns emptyList()
        coEvery { api.getTags(page = 1, pageSize = 100) } throws IOException("Network error")

        val result = tagRepository.getTags(forceRefresh = true)

        assertTrue(result.isFailure)
        // PaperlessException wraps the IOException
        assertTrue(result.exceptionOrNull()?.message?.contains("Network error") == true)
    }

    @Test
    fun `createTag success returns new tag`() = runTest {
        val newTag = Tag(id = 10, name = "NewTag", color = "#abcdef")
        coEvery { api.createTag(any()) } returns newTag

        val result = tagRepository.createTag("NewTag", "#abcdef")

        assertTrue(result.isSuccess)
        assertEquals("NewTag", result.getOrNull()?.name)
        assertEquals("#abcdef", result.getOrNull()?.color)
        coVerify { api.createTag(CreateTagRequest(name = "NewTag", color = "#abcdef")) }
    }

    @Test
    fun `createTag without color sends null color`() = runTest {
        val newTag = Tag(id = 11, name = "NoColorTag", color = null)
        coEvery { api.createTag(any()) } returns newTag

        val result = tagRepository.createTag("NoColorTag")

        assertTrue(result.isSuccess)
        coVerify { api.createTag(CreateTagRequest(name = "NoColorTag", color = null)) }
    }

    @Test
    fun `createTag network error returns failure`() = runTest {
        coEvery { api.createTag(any()) } throws IOException("Connection refused")

        val result = tagRepository.createTag("FailTag", "#000000")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Connection refused") == true)
    }

    @Test
    fun `createTag with duplicate name returns api error`() = runTest {
        coEvery { api.createTag(any()) } throws RuntimeException("Tag already exists")

        val result = tagRepository.createTag("ExistingTag")

        assertTrue(result.isFailure)
        assertEquals("Tag already exists", result.exceptionOrNull()?.message)
    }
}
