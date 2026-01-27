package com.paperless.scanner.data.repository

import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.models.Correspondent
import com.paperless.scanner.data.api.models.CorrespondentsResponse
import com.paperless.scanner.data.api.models.CreateCorrespondentRequest
import com.paperless.scanner.data.api.models.UpdateCorrespondentRequest
import com.paperless.scanner.data.database.dao.CachedCorrespondentDao
import com.paperless.scanner.data.database.dao.PendingChangeDao
import com.paperless.scanner.data.database.entities.CachedCorrespondent
import com.paperless.scanner.data.network.NetworkMonitor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

class CorrespondentRepositoryTest {

    private lateinit var api: PaperlessApi
    private lateinit var cachedCorrespondentDao: CachedCorrespondentDao
    private lateinit var pendingChangeDao: PendingChangeDao
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var correspondentRepository: CorrespondentRepository

    @Before
    fun setup() {
        api = mockk()
        cachedCorrespondentDao = mockk(relaxed = true)
        pendingChangeDao = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)
        correspondentRepository = CorrespondentRepository(
            api,
            cachedCorrespondentDao,
            pendingChangeDao,
            networkMonitor
        )
    }

    // Flow Reactivity Tests
    @Test
    fun `observeCorrespondents returns Flow from DAO`() = runTest {
        val cachedCorrespondents = listOf(
            CachedCorrespondent(
                id = 1,
                name = "Telekom",
                match = null,
                matchingAlgorithm = null,
                documentCount = 5
            )
        )
        every { cachedCorrespondentDao.observeCorrespondents() } returns flowOf(cachedCorrespondents)

        val result = correspondentRepository.observeCorrespondents().first()

        assertEquals(1, result.size)
        assertEquals("Telekom", result[0].name)
    }

    // Get Tests (Offline-First)
    @Test
    fun `getCorrespondents returns cached data when offline`() = runTest {
        val cachedCorrespondents = listOf(
            CachedCorrespondent(
                id = 1,
                name = "Telekom",
                match = null,
                matchingAlgorithm = null,
                documentCount = 3
            )
        )
        every { networkMonitor.checkOnlineStatus() } returns false
        coEvery { cachedCorrespondentDao.getAllCorrespondents() } returns cachedCorrespondents

        val result = correspondentRepository.getCorrespondents(forceRefresh = true)

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull()?.size)
        assertEquals("Telekom", result.getOrNull()?.get(0)?.name)
        coVerify(exactly = 0) { api.getCorrespondents(any(), any()) }
    }

    @Test
    fun `getCorrespondents with forceRefresh fetches from API when online`() = runTest {
        val apiCorrespondents = listOf(
            Correspondent(id = 1, name = "Telekom"),
            Correspondent(id = 2, name = "Vodafone")
        )
        every { networkMonitor.checkOnlineStatus() } returns true
        coEvery { api.getCorrespondents(page = 1, pageSize = 100) } returns CorrespondentsResponse(
            count = 2,
            results = apiCorrespondents
        )

        val result = correspondentRepository.getCorrespondents(forceRefresh = true)

        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrNull()?.size)
        coVerify(exactly = 1) { api.getCorrespondents(page = 1, pageSize = 100) }
        coVerify(exactly = 1) { cachedCorrespondentDao.insertAll(any()) }
    }

    @Test
    fun `getCorrespondents without forceRefresh returns cache first`() = runTest {
        val cachedCorrespondents = listOf(
            CachedCorrespondent(
                id = 1,
                name = "Cached",
                match = null,
                matchingAlgorithm = null,
                documentCount = 1
            )
        )
        coEvery { cachedCorrespondentDao.getAllCorrespondents() } returns cachedCorrespondents

        val result = correspondentRepository.getCorrespondents(forceRefresh = false)

        assertTrue(result.isSuccess)
        assertEquals("Cached", result.getOrNull()?.get(0)?.name)
        coVerify(exactly = 0) { api.getCorrespondents(any(), any()) }
    }

    @Test
    fun `getCorrespondents returns empty list when offline and cache empty`() = runTest {
        every { networkMonitor.checkOnlineStatus() } returns false
        coEvery { cachedCorrespondentDao.getAllCorrespondents() } returns emptyList()

        val result = correspondentRepository.getCorrespondents(forceRefresh = true)

        assertTrue(result.isSuccess)
        assertEquals(emptyList<com.paperless.scanner.domain.model.Correspondent>(), result.getOrNull())
    }

    @Test
    fun `getCorrespondents network error returns failure`() = runTest {
        every { networkMonitor.checkOnlineStatus() } returns true
        coEvery { cachedCorrespondentDao.getAllCorrespondents() } returns emptyList()
        coEvery { api.getCorrespondents(page = 1, pageSize = 100) } throws IOException("Network error")

        val result = correspondentRepository.getCorrespondents(forceRefresh = true)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Network error") == true)
    }

    // Create Tests (Cache Update for Flow Trigger)
    @Test
    fun `createCorrespondent success returns new correspondent and updates cache`() = runTest {
        val newCorrespondent = Correspondent(id = 10, name = "NewCorp")
        coEvery { api.createCorrespondent(any()) } returns newCorrespondent

        val result = correspondentRepository.createCorrespondent("NewCorp")

        assertTrue(result.isSuccess)
        assertEquals("NewCorp", result.getOrNull()?.name)
        coVerify { api.createCorrespondent(CreateCorrespondentRequest(name = "NewCorp")) }
        coVerify(exactly = 1) { cachedCorrespondentDao.insert(any()) }
    }

    @Test
    fun `createCorrespondent network error returns failure`() = runTest {
        coEvery { api.createCorrespondent(any()) } throws IOException("Connection refused")

        val result = correspondentRepository.createCorrespondent("FailCorp")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Connection refused") == true)
    }

    // Update Tests (Cache Update for Flow Trigger)
    @Test
    fun `updateCorrespondent success returns updated correspondent and updates cache`() = runTest {
        val updatedCorrespondent = Correspondent(id = 1, name = "UpdatedName")
        coEvery { api.updateCorrespondent(1, any()) } returns updatedCorrespondent

        val result = correspondentRepository.updateCorrespondent(1, "UpdatedName")

        assertTrue(result.isSuccess)
        assertEquals("UpdatedName", result.getOrNull()?.name)
        coVerify { api.updateCorrespondent(1, UpdateCorrespondentRequest(name = "UpdatedName")) }
        coVerify(exactly = 1) { cachedCorrespondentDao.insert(any()) }
    }

    @Test
    fun `updateCorrespondent network error returns failure`() = runTest {
        coEvery { api.updateCorrespondent(any(), any()) } throws IOException("Timeout")

        val result = correspondentRepository.updateCorrespondent(1, "FailName")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Timeout") == true)
    }

    // Delete Tests (Cache Removal for Flow Trigger)
    @Test
    fun `deleteCorrespondent success removes from cache`() = runTest {
        val mockResponse = mockk<retrofit2.Response<Unit>>(relaxed = true)
        coEvery { api.deleteCorrespondent(1) } returns mockResponse

        val result = correspondentRepository.deleteCorrespondent(1)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { api.deleteCorrespondent(1) }
        coVerify(exactly = 1) { cachedCorrespondentDao.softDelete(1) }
    }

    @Test
    fun `deleteCorrespondent network error returns failure`() = runTest {
        coEvery { api.deleteCorrespondent(any()) } throws IOException("Server error")

        val result = correspondentRepository.deleteCorrespondent(1)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Server error") == true)
    }
}
