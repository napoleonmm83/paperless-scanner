package com.paperless.scanner.data.repository

import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.models.CreateDocumentTypeRequest
import com.paperless.scanner.data.api.models.DocumentType
import com.paperless.scanner.data.api.models.DocumentTypesResponse
import com.paperless.scanner.data.api.models.UpdateDocumentTypeRequest
import com.paperless.scanner.data.database.dao.CachedDocumentTypeDao
import com.paperless.scanner.data.database.dao.PendingChangeDao
import com.paperless.scanner.data.database.entities.CachedDocumentType
import com.paperless.scanner.data.network.NetworkMonitor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class DocumentTypeRepositoryTest {

    private lateinit var api: PaperlessApi
    private lateinit var cachedDocumentTypeDao: CachedDocumentTypeDao
    private lateinit var pendingChangeDao: PendingChangeDao
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var documentTypeRepository: DocumentTypeRepository

    @Before
    fun setup() {
        api = mockk()
        cachedDocumentTypeDao = mockk(relaxed = true)
        pendingChangeDao = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)
        documentTypeRepository = DocumentTypeRepository(
            api,
            cachedDocumentTypeDao,
            pendingChangeDao,
            networkMonitor
        )
    }

    // Flow Reactivity Tests
    @Test
    fun `observeDocumentTypes returns Flow from DAO`() = runTest {
        val cachedTypes = listOf(
            CachedDocumentType(
                id = 1,
                name = "Rechnung",
                match = null,
                matchingAlgorithm = null,
                documentCount = 10,
                isDeleted = false
            )
        )
        every { cachedDocumentTypeDao.observeDocumentTypes() } returns flowOf(cachedTypes)

        val result = documentTypeRepository.observeDocumentTypes().first()

        assertEquals(1, result.size)
        assertEquals("Rechnung", result[0].name)
    }

    // Get Tests (Offline-First)
    @Test
    fun `getDocumentTypes returns cached data when offline`() = runTest {
        val cachedTypes = listOf(
            CachedDocumentType(
                id = 1,
                name = "Vertrag",
                match = null,
                matchingAlgorithm = null,
                documentCount = 5,
                isDeleted = false
            )
        )
        every { networkMonitor.checkOnlineStatus() } returns false
        coEvery { cachedDocumentTypeDao.getAllDocumentTypes() } returns cachedTypes

        val result = documentTypeRepository.getDocumentTypes(forceRefresh = true)

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull()?.size)
        assertEquals("Vertrag", result.getOrNull()?.get(0)?.name)
        coVerify(exactly = 0) { api.getDocumentTypes(any(), any()) }
    }

    @Test
    fun `getDocumentTypes with forceRefresh fetches from API when online`() = runTest {
        val apiTypes = listOf(
            DocumentType(id = 1, name = "Rechnung"),
            DocumentType(id = 2, name = "Brief")
        )
        every { networkMonitor.checkOnlineStatus() } returns true
        coEvery { api.getDocumentTypes(page = 1, pageSize = 100) } returns DocumentTypesResponse(
            count = 2,
            results = apiTypes
        )

        val result = documentTypeRepository.getDocumentTypes(forceRefresh = true)

        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrNull()?.size)
        coVerify(exactly = 1) { api.getDocumentTypes(page = 1, pageSize = 100) }
        coVerify(exactly = 1) { cachedDocumentTypeDao.insertAll(any()) }
    }

    @Test
    fun `getDocumentTypes without forceRefresh returns cache first`() = runTest {
        val cachedTypes = listOf(
            CachedDocumentType(
                id = 1,
                name = "Cached",
                match = null,
                matchingAlgorithm = null,
                documentCount = 2,
                isDeleted = false
            )
        )
        coEvery { cachedDocumentTypeDao.getAllDocumentTypes() } returns cachedTypes

        val result = documentTypeRepository.getDocumentTypes(forceRefresh = false)

        assertTrue(result.isSuccess)
        assertEquals("Cached", result.getOrNull()?.get(0)?.name)
        coVerify(exactly = 0) { api.getDocumentTypes(any(), any()) }
    }

    @Test
    fun `getDocumentTypes returns empty list when offline and cache empty`() = runTest {
        every { networkMonitor.checkOnlineStatus() } returns false
        coEvery { cachedDocumentTypeDao.getAllDocumentTypes() } returns emptyList()

        val result = documentTypeRepository.getDocumentTypes(forceRefresh = true)

        assertTrue(result.isSuccess)
        assertEquals(emptyList<com.paperless.scanner.domain.model.DocumentType>(), result.getOrNull())
    }

    @Test
    fun `getDocumentTypes network error returns failure`() = runTest {
        every { networkMonitor.checkOnlineStatus() } returns true
        coEvery { cachedDocumentTypeDao.getAllDocumentTypes() } returns emptyList()
        coEvery { api.getDocumentTypes(page = 1, pageSize = 100) } throws IOException("Network error")

        val result = documentTypeRepository.getDocumentTypes(forceRefresh = true)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Network error") == true)
    }

    // Create Tests (Cache Update for Flow Trigger)
    @Test
    fun `createDocumentType success returns new type and updates cache`() = runTest {
        val newType = DocumentType(id = 10, name = "Quittung")
        coEvery { api.createDocumentType(any()) } returns newType

        val result = documentTypeRepository.createDocumentType("Quittung")

        assertTrue(result.isSuccess)
        assertEquals("Quittung", result.getOrNull()?.name)
        coVerify { api.createDocumentType(CreateDocumentTypeRequest(name = "Quittung")) }
        coVerify(exactly = 1) { cachedDocumentTypeDao.insert(any()) }
    }

    @Test
    fun `createDocumentType network error returns failure`() = runTest {
        coEvery { api.createDocumentType(any()) } throws IOException("Connection refused")

        val result = documentTypeRepository.createDocumentType("FailType")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Connection refused") == true)
    }

    // Update Tests (Cache Update for Flow Trigger)
    @Test
    fun `updateDocumentType success returns updated type and updates cache`() = runTest {
        val updatedType = DocumentType(id = 1, name = "UpdatedType")
        coEvery { api.updateDocumentType(1, any()) } returns updatedType

        val result = documentTypeRepository.updateDocumentType(1, "UpdatedType")

        assertTrue(result.isSuccess)
        assertEquals("UpdatedType", result.getOrNull()?.name)
        coVerify { api.updateDocumentType(1, UpdateDocumentTypeRequest(name = "UpdatedType")) }
        coVerify(exactly = 1) { cachedDocumentTypeDao.insert(any()) }
    }

    @Test
    fun `updateDocumentType network error returns failure`() = runTest {
        coEvery { api.updateDocumentType(any(), any()) } throws IOException("Timeout")

        val result = documentTypeRepository.updateDocumentType(1, "FailName")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Timeout") == true)
    }

    // Delete Tests (Cache Removal for Flow Trigger)
    @Test
    fun `deleteDocumentType success removes from cache`() = runTest {
        val mockResponse = mockk<retrofit2.Response<Unit>>(relaxed = true)
        coEvery { api.deleteDocumentType(1) } returns mockResponse

        val result = documentTypeRepository.deleteDocumentType(1)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { api.deleteDocumentType(1) }
        coVerify(exactly = 1) { cachedDocumentTypeDao.softDelete(1) }
    }

    @Test
    fun `deleteDocumentType network error returns failure`() = runTest {
        coEvery { api.deleteDocumentType(any()) } throws IOException("Server error")

        val result = documentTypeRepository.deleteDocumentType(1)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Server error") == true)
    }
}
