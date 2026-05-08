package com.paperless.scanner.data.repository

import androidx.test.filters.LargeTest
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.models.Correspondent
import com.paperless.scanner.data.api.models.CorrespondentsResponse
import com.paperless.scanner.data.api.models.CreateCorrespondentRequest
import com.paperless.scanner.data.api.models.UpdateCorrespondentRequest
import com.paperless.scanner.data.database.dao.CachedCorrespondentDao
import com.paperless.scanner.data.database.dao.PendingChangeDao
import com.paperless.scanner.data.database.entities.CachedCorrespondent
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.testing.BaseRoomRepositoryTest
import app.cash.turbine.test
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
 * Repository tests for [CorrespondentRepository].
 *
 * Uses a real in-memory Room database (see [BaseRoomRepositoryTest]) so DAO
 * behavior — observeCorrespondents Flow, soft delete, REPLACE conflict
 * strategy — is verified against the actual schema. Only `PaperlessApi` and
 * `NetworkMonitor` are mocked.
 *
 * Marked `@LargeTest` because the suite exercises a real Room schema, which
 * is heavier than pure unit tests (per Issue #137 acceptance criteria).
 */
@LargeTest
class CorrespondentRepositoryTest : BaseRoomRepositoryTest() {

    private lateinit var api: PaperlessApi
    private lateinit var cachedCorrespondentDao: CachedCorrespondentDao
    private lateinit var pendingChangeDao: PendingChangeDao
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var correspondentRepository: CorrespondentRepository

    @Before
    fun setup() {
        api = mockk()
        networkMonitor = mockk(relaxed = true)
        cachedCorrespondentDao = database.cachedCorrespondentDao()
        pendingChangeDao = database.pendingChangeDao()
        correspondentRepository = CorrespondentRepository(
            api,
            cachedCorrespondentDao,
            pendingChangeDao,
            networkMonitor
        )
    }

    private fun cached(id: Int, name: String, documentCount: Int? = 0) = CachedCorrespondent(
        id = id,
        name = name,
        match = null,
        matchingAlgorithm = null,
        documentCount = documentCount
    )

    // ---------------- Flow Reactivity ----------------

    @Test
    fun `observeCorrespondents emits cached entries from real DAO`() = runTest {
        cachedCorrespondentDao.insert(cached(id = 1, name = "Telekom", documentCount = 5))

        correspondentRepository.observeCorrespondents().test {
            val emitted = awaitItem()
            assertEquals(1, emitted.size)
            assertEquals("Telekom", emitted[0].name)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---------------- Get (Offline-First) ----------------

    @Test
    fun `getCorrespondents returns cached data when offline`() = runTest {
        every { networkMonitor.checkOnlineStatus() } returns false
        cachedCorrespondentDao.insert(cached(id = 1, name = "Telekom", documentCount = 3))

        val result = correspondentRepository.getCorrespondents(forceRefresh = true)

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull()?.size)
        assertEquals("Telekom", result.getOrNull()?.get(0)?.name)
        coVerify(exactly = 0) { api.getCorrespondents(any(), any()) }
    }

    @Test
    fun `getCorrespondents with forceRefresh fetches from API when online and persists cache`() =
        runTest {
            val apiCorrespondents = listOf(
                Correspondent(id = 1, name = "Telekom"),
                Correspondent(id = 2, name = "Vodafone")
            )
            every { networkMonitor.checkOnlineStatus() } returns true
            coEvery { api.getCorrespondents(page = 1, pageSize = 100) } returns
                CorrespondentsResponse(count = 2, results = apiCorrespondents)

            val result = correspondentRepository.getCorrespondents(forceRefresh = true)

            assertTrue(result.isSuccess)
            assertEquals(2, result.getOrNull()?.size)
            coVerify(exactly = 1) { api.getCorrespondents(page = 1, pageSize = 100) }
            // Real DB verification: cache was populated
            val persisted = cachedCorrespondentDao.getAllCorrespondents().sortedBy { it.id }
            assertEquals(listOf(1, 2), persisted.map { it.id })
            assertEquals("Vodafone", persisted[1].name)
        }

    @Test
    fun `getCorrespondents without forceRefresh returns cache first`() = runTest {
        cachedCorrespondentDao.insert(cached(id = 1, name = "Cached", documentCount = 1))

        val result = correspondentRepository.getCorrespondents(forceRefresh = false)

        assertTrue(result.isSuccess)
        assertEquals("Cached", result.getOrNull()?.get(0)?.name)
        coVerify(exactly = 0) { api.getCorrespondents(any(), any()) }
    }

    @Test
    fun `getCorrespondents returns empty list when offline and cache empty`() = runTest {
        every { networkMonitor.checkOnlineStatus() } returns false

        val result = correspondentRepository.getCorrespondents(forceRefresh = true)

        assertTrue(result.isSuccess)
        assertEquals(emptyList<com.paperless.scanner.domain.model.Correspondent>(), result.getOrNull())
    }

    @Test
    fun `getCorrespondents network error returns failure`() = runTest {
        every { networkMonitor.checkOnlineStatus() } returns true
        coEvery { api.getCorrespondents(page = 1, pageSize = 100) } throws IOException("Network error")

        val result = correspondentRepository.getCorrespondents(forceRefresh = true)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Network error") == true)
    }

    // ---------------- Create ----------------

    @Test
    fun `createCorrespondent success returns new correspondent and inserts into cache`() = runTest {
        val newCorrespondent = Correspondent(id = 10, name = "NewCorp")
        coEvery { api.createCorrespondent(any()) } returns newCorrespondent

        val result = correspondentRepository.createCorrespondent("NewCorp")

        assertTrue(result.isSuccess)
        assertEquals("NewCorp", result.getOrNull()?.name)
        coVerify { api.createCorrespondent(CreateCorrespondentRequest(name = "NewCorp")) }
        assertEquals("NewCorp", cachedCorrespondentDao.getCorrespondent(10)?.name)
    }

    @Test
    fun `createCorrespondent network error returns failure and leaves cache empty`() = runTest {
        coEvery { api.createCorrespondent(any()) } throws IOException("Connection refused")

        val result = correspondentRepository.createCorrespondent("FailCorp")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Connection refused") == true)
        assertEquals(0, cachedCorrespondentDao.getAllCorrespondents().size)
    }

    // ---------------- Update ----------------

    @Test
    fun `updateCorrespondent success returns updated correspondent and replaces cache row`() =
        runTest {
            cachedCorrespondentDao.insert(cached(id = 1, name = "Old"))
            val updatedCorrespondent = Correspondent(id = 1, name = "UpdatedName")
            coEvery { api.updateCorrespondent(1, any()) } returns updatedCorrespondent

            val result = correspondentRepository.updateCorrespondent(1, "UpdatedName")

            assertTrue(result.isSuccess)
            assertEquals("UpdatedName", result.getOrNull()?.name)
            coVerify {
                api.updateCorrespondent(1, UpdateCorrespondentRequest(name = "UpdatedName"))
            }
            assertEquals("UpdatedName", cachedCorrespondentDao.getCorrespondent(1)?.name)
        }

    @Test
    fun `updateCorrespondent network error returns failure`() = runTest {
        coEvery { api.updateCorrespondent(any(), any()) } throws IOException("Timeout")

        val result = correspondentRepository.updateCorrespondent(1, "FailName")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Timeout") == true)
    }

    // ---------------- Delete ----------------

    @Test
    fun `deleteCorrespondent success soft-deletes the cached row`() = runTest {
        cachedCorrespondentDao.insert(cached(id = 1, name = "ToDelete"))
        val successResponse = retrofit2.Response.success(Unit)
        coEvery { api.deleteCorrespondent(1) } returns successResponse

        val result = correspondentRepository.deleteCorrespondent(1)

        assertTrue(result.isSuccess)
        coVerify(exactly = 1) { api.deleteCorrespondent(1) }
        // Soft delete: getAllCorrespondents filters isDeleted=0 so the row is hidden
        assertEquals(0, cachedCorrespondentDao.getAllCorrespondents().size)
        // But the row physically remains (id still in getAllIds)
        assertTrue(cachedCorrespondentDao.getAllIds().contains(1))
    }

    @Test
    fun `deleteCorrespondent network error returns failure and leaves cache untouched`() = runTest {
        cachedCorrespondentDao.insert(cached(id = 1, name = "Survivor"))
        coEvery { api.deleteCorrespondent(any()) } throws IOException("Server error")

        val result = correspondentRepository.deleteCorrespondent(1)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Server error") == true)
        assertEquals("Survivor", cachedCorrespondentDao.getCorrespondent(1)?.name)
    }
}
