package com.paperless.scanner.data.repository

import androidx.test.filters.LargeTest
import app.cash.turbine.test
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.models.CreateDocumentTypeRequest
import com.paperless.scanner.data.api.models.DocumentType
import com.paperless.scanner.data.api.models.DocumentTypesResponse
import com.paperless.scanner.data.api.models.UpdateDocumentTypeRequest
import com.paperless.scanner.data.database.dao.CachedDocumentTypeDao
import com.paperless.scanner.data.database.dao.PendingChangeDao
import com.paperless.scanner.data.database.entities.CachedDocumentType
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.testing.BaseRoomRepositoryTest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Repository tests for [DocumentTypeRepository].
 *
 * Uses a real in-memory Room database (see [BaseRoomRepositoryTest]) so DAO
 * behavior — observeDocumentTypes Flow, soft delete, REPLACE conflict
 * strategy — is verified against the actual schema. Only `PaperlessApi` and
 * `NetworkMonitor` are mocked.
 *
 * Marked `@LargeTest` because the suite exercises a real Room schema, which
 * is heavier than pure unit tests (per Issue #137 acceptance criteria).
 */
@LargeTest
class DocumentTypeRepositoryTest : BaseRoomRepositoryTest() {

    private lateinit var api: PaperlessApi
    private lateinit var cachedDocumentTypeDao: CachedDocumentTypeDao
    private lateinit var pendingChangeDao: PendingChangeDao
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var documentTypeRepository: DocumentTypeRepository

    @Before
    fun setup() {
        api = mockk()
        networkMonitor = mockk(relaxed = true)
        cachedDocumentTypeDao = database.cachedDocumentTypeDao()
        pendingChangeDao = database.pendingChangeDao()
        documentTypeRepository = DocumentTypeRepository(
            api,
            cachedDocumentTypeDao,
            pendingChangeDao,
            networkMonitor
        )
    }

    private fun cached(id: Int, name: String, documentCount: Int? = 0) = CachedDocumentType(
        id = id,
        name = name,
        match = null,
        matchingAlgorithm = null,
        documentCount = documentCount
    )

    // ---------------- Flow Reactivity ----------------

    @Test
    fun `observeDocumentTypes emits cached entries from real DAO`() = runTest {
        cachedDocumentTypeDao.insert(cached(id = 1, name = "Rechnung", documentCount = 10))

        documentTypeRepository.observeDocumentTypes().test {
            val emitted = awaitItem()
            assertEquals(1, emitted.size)
            assertEquals("Rechnung", emitted[0].name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---------------- Get (Offline-First) ----------------

    @Test
    fun `getDocumentTypes returns cached data when offline`() = runTest {
        every { networkMonitor.checkOnlineStatus() } returns false
        cachedDocumentTypeDao.insert(cached(id = 1, name = "Vertrag", documentCount = 5))

        val result = documentTypeRepository.getDocumentTypes(forceRefresh = true)

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull()?.size)
        assertEquals("Vertrag", result.getOrNull()?.get(0)?.name)
        coVerify(exactly = 0) { api.getDocumentTypes(any(), any()) }
    }

    @Test
    fun `getDocumentTypes with forceRefresh fetches from API when online and persists cache`() =
        runTest {
            val apiTypes = listOf(
                DocumentType(id = 1, name = "Rechnung"),
                DocumentType(id = 2, name = "Brief")
            )
            every { networkMonitor.checkOnlineStatus() } returns true
            coEvery { api.getDocumentTypes(page = 1, pageSize = 100) } returns
                DocumentTypesResponse(count = 2, results = apiTypes)

            val result = documentTypeRepository.getDocumentTypes(forceRefresh = true)

            assertTrue(result.isSuccess)
            assertEquals(2, result.getOrNull()?.size)
            coVerify(exactly = 1) { api.getDocumentTypes(page = 1, pageSize = 100) }
            val persisted = cachedDocumentTypeDao.getAllDocumentTypes().sortedBy { it.id }
            assertEquals(listOf(1, 2), persisted.map { it.id })
            assertEquals("Brief", persisted[1].name)
        }

    @Test
    fun `getDocumentTypes without forceRefresh returns cache first`() = runTest {
        cachedDocumentTypeDao.insert(cached(id = 1, name = "Cached", documentCount = 2))

        val result = documentTypeRepository.getDocumentTypes(forceRefresh = false)

        assertTrue(result.isSuccess)
        assertEquals("Cached", result.getOrNull()?.get(0)?.name)
        coVerify(exactly = 0) { api.getDocumentTypes(any(), any()) }
    }

    @Test
    fun `getDocumentTypes returns empty list when offline and cache empty`() = runTest {
        every { networkMonitor.checkOnlineStatus() } returns false

        val result = documentTypeRepository.getDocumentTypes(forceRefresh = true)

        assertTrue(result.isSuccess)
        assertEquals(emptyList<com.paperless.scanner.domain.model.DocumentType>(), result.getOrNull())
    }

    @Test
    fun `getDocumentTypes network error returns failure`() = runTest {
        every { networkMonitor.checkOnlineStatus() } returns true
        coEvery { api.getDocumentTypes(page = 1, pageSize = 100) } throws IOException("Network error")

        val result = documentTypeRepository.getDocumentTypes(forceRefresh = true)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Network error") == true)
    }

    // ---------------- Create ----------------

    @Test
    fun `createDocumentType success returns new type and inserts into cache`() = runTest {
        val newType = DocumentType(id = 10, name = "Quittung")
        coEvery { api.createDocumentType(any()) } returns newType

        val result = documentTypeRepository.createDocumentType("Quittung")

        assertTrue(result.isSuccess)
        assertEquals("Quittung", result.getOrNull()?.name)
        coVerify { api.createDocumentType(CreateDocumentTypeRequest(name = "Quittung")) }
        assertEquals("Quittung", cachedDocumentTypeDao.getDocumentType(10)?.name)
    }

    @Test
    fun `createDocumentType network error returns failure and leaves cache empty`() = runTest {
        coEvery { api.createDocumentType(any()) } throws IOException("Connection refused")

        val result = documentTypeRepository.createDocumentType("FailType")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Connection refused") == true)
        assertEquals(0, cachedDocumentTypeDao.getAllDocumentTypes().size)
    }

    // ---------------- Update ----------------

    @Test
    fun `updateDocumentType success returns updated type and replaces cache row`() = runTest {
        cachedDocumentTypeDao.insert(cached(id = 1, name = "Old"))
        val updatedType = DocumentType(id = 1, name = "UpdatedType")
        coEvery { api.updateDocumentType(1, any()) } returns updatedType

        val result = documentTypeRepository.updateDocumentType(1, "UpdatedType")

        assertTrue(result.isSuccess)
        assertEquals("UpdatedType", result.getOrNull()?.name)
        coVerify { api.updateDocumentType(1, UpdateDocumentTypeRequest(name = "UpdatedType")) }
        assertEquals("UpdatedType", cachedDocumentTypeDao.getDocumentType(1)?.name)
    }

    @Test
    fun `updateDocumentType network error returns failure`() = runTest {
        coEvery { api.updateDocumentType(any(), any()) } throws IOException("Timeout")

        val result = documentTypeRepository.updateDocumentType(1, "FailName")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Timeout") == true)
    }

    // ---------------- Delete ----------------

    @Test
    fun `deleteDocumentType success soft-deletes the cached row`() = runTest {
        cachedDocumentTypeDao.insert(cached(id = 1, name = "ToDelete"))
        val successResponse = retrofit2.Response.success(Unit)
        coEvery { api.deleteDocumentType(1) } returns successResponse

        val result = documentTypeRepository.deleteDocumentType(1)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { api.deleteDocumentType(1) }
        assertEquals(0, cachedDocumentTypeDao.getAllDocumentTypes().size)
        assertTrue(cachedDocumentTypeDao.getAllIds().contains(1))
    }

    @Test
    fun `deleteDocumentType network error returns failure and leaves cache untouched`() = runTest {
        cachedDocumentTypeDao.insert(cached(id = 1, name = "Survivor"))
        coEvery { api.deleteDocumentType(any()) } throws IOException("Server error")

        val result = documentTypeRepository.deleteDocumentType(1)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Server error") == true)
        assertEquals("Survivor", cachedDocumentTypeDao.getDocumentType(1)?.name)
    }
}
