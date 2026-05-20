package com.paperless.scanner.ui.screens.home

import app.cash.turbine.test
import com.paperless.scanner.data.repository.DocumentCountRepository
import com.paperless.scanner.data.repository.TrashRepository
import com.paperless.scanner.domain.model.Document
import com.paperless.scanner.domain.model.DocumentsResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TrashOverviewViewModelTest {

    private lateinit var documentCountRepository: DocumentCountRepository
    private lateinit var trashRepository: TrashRepository

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        documentCountRepository = mockk(relaxed = true)
        trashRepository = mockk(relaxed = true)

        every { documentCountRepository.observeUntaggedDocumentsCount() } returns MutableStateFlow(0)
        every { trashRepository.observeTrashedDocumentsCount() } returns MutableStateFlow(0)
        every { trashRepository.observeOldestDeletedTimestamp() } returns MutableStateFlow(null)
        coEvery { documentCountRepository.getUntaggedCount() } returns Result.success(0)
        coEvery { trashRepository.getTrashDocuments(any(), any()) } returns
                Result.success(DocumentsResponse(count = 0, results = emptyList(), next = null))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): TrashOverviewViewModel = TrashOverviewViewModel(
        documentCountRepository = documentCountRepository,
        trashRepository = trashRepository,
    )

    private fun trashedDoc(id: Int) = Document(
        id = id,
        title = "doc-$id",
        created = "2026-01-01",
        modified = "2026-01-01",
        added = "2026-01-01",
        tags = emptyList(),
    )

    @Test
    fun `initial uiState has zero counts and null timestamp`() = runTest {
        val vm = createViewModel()

        val initial = vm.uiState.value
        assertEquals(0, initial.untaggedCount)
        assertEquals(0, initial.deletedCount)
        assertNull(initial.oldestDeletedTimestamp)
        assertNull(vm.error.value)
    }

    @Test
    fun `untagged count flow emissions propagate into uiState`() = runTest {
        val untaggedFlow = MutableStateFlow(0)
        every { documentCountRepository.observeUntaggedDocumentsCount() } returns untaggedFlow

        val vm = createViewModel()

        vm.uiState.test {
            var state = awaitItem()
            while (state.untaggedCount != 0) {
                state = awaitItem()
            }

            untaggedFlow.value = 12
            assertEquals(12, awaitItem().untaggedCount)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deleted count flow emissions propagate into uiState`() = runTest {
        val trashCountFlow = MutableStateFlow(0)
        every { trashRepository.observeTrashedDocumentsCount() } returns trashCountFlow

        val vm = createViewModel()

        vm.uiState.test {
            var state = awaitItem()
            while (state.deletedCount != 0) {
                state = awaitItem()
            }

            trashCountFlow.value = 5
            assertEquals(5, awaitItem().deletedCount)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `oldest deleted timestamp flow emissions propagate into uiState`() = runTest {
        val timestampFlow = MutableStateFlow<Long?>(null)
        every { trashRepository.observeOldestDeletedTimestamp() } returns timestampFlow

        val vm = createViewModel()

        vm.uiState.test {
            var state = awaitItem()
            while (state.oldestDeletedTimestamp != null) {
                state = awaitItem()
            }

            val now = 1_700_000_000_000L
            timestampFlow.value = now
            assertEquals(now, awaitItem().oldestDeletedTimestamp)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `init triggers quick trash sync but not full pagination`() = runTest {
        createViewModel()
        advanceUntilIdle()

        // Init refresh is fullTrashSync=false → page=1 only, no further pages.
        coVerify(exactly = 1) { trashRepository.getTrashDocuments(page = 1, pageSize = 100) }
    }

    @Test
    fun `refreshTrashOverview with fullTrashSync false fetches only first page`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.refreshTrashOverview(fullTrashSync = false)
        advanceUntilIdle()

        // Init call + manual call = 2 page-1 fetches.
        coVerify(exactly = 2) { trashRepository.getTrashDocuments(page = 1, pageSize = 100) }
    }

    @Test
    fun `refreshTrashOverview with fullTrashSync true paginates and cleans up orphans`() = runTest {
        coEvery { trashRepository.getTrashDocuments(page = 1, pageSize = 100) } returns
                Result.success(
                    DocumentsResponse(
                        count = 2,
                        results = listOf(trashedDoc(1), trashedDoc(2)),
                        next = "https://srv/api/documents/?page=2",
                    ),
                )
        coEvery { trashRepository.getTrashDocuments(page = 2, pageSize = 100) } returns
                Result.success(
                    DocumentsResponse(
                        count = 1,
                        results = listOf(trashedDoc(3)),
                        next = null,
                    ),
                )

        val vm = createViewModel()
        advanceUntilIdle()

        vm.refreshTrashOverview(fullTrashSync = true)
        advanceUntilIdle()

        // Init pulled page-1 only. Full sync pulled page-1 + page-2 + cleanup.
        coVerify(atLeast = 1) { trashRepository.getTrashDocuments(page = 2, pageSize = 100) }
        coVerify(exactly = 1) { trashRepository.cleanupOrphanedTrashDocs(setOf(1, 2, 3)) }
    }

    @Test
    fun `full trash sync skips orphan cleanup when intermediate page fails`() = runTest {
        // Page 1 succeeds (yields server IDs 1 and 2), page 2 fails. The pre-
        // extraction code would have run cleanupOrphanedTrashDocs(setOf(1, 2))
        // and incorrectly marked any docs from page 2+ as orphans.
        coEvery { trashRepository.getTrashDocuments(page = 1, pageSize = 100) } returns
                Result.success(
                    DocumentsResponse(
                        count = 5,
                        results = listOf(trashedDoc(1), trashedDoc(2)),
                        next = "https://srv/api/documents/?page=2",
                    ),
                )
        coEvery { trashRepository.getTrashDocuments(page = 2, pageSize = 100) } returns
                Result.failure(RuntimeException("page 2 network timeout"))

        val vm = createViewModel()
        advanceUntilIdle()

        vm.refreshTrashOverview(fullTrashSync = true)
        advanceUntilIdle()

        // CR R2 fix: skipping cleanup when pagination is incomplete.
        coVerify(exactly = 0) { trashRepository.cleanupOrphanedTrashDocs(any()) }
    }

    @Test
    fun `full trash sync stops paginating on failure and skips orphan cleanup when empty`() = runTest {
        coEvery { trashRepository.getTrashDocuments(page = 1, pageSize = 100) } returns
                Result.failure(RuntimeException("network down"))

        val vm = createViewModel()
        advanceUntilIdle()

        vm.refreshTrashOverview(fullTrashSync = true)
        advanceUntilIdle()

        // No orphan-cleanup call when nothing came back from the server —
        // safer than wiping local trash on a single failed first page.
        coVerify(exactly = 0) { trashRepository.cleanupOrphanedTrashDocs(any()) }
    }

    @Test
    fun `observeUntaggedCount sets LoadFailed when observer throws`() = runTest {
        every { documentCountRepository.observeUntaggedDocumentsCount() } returns
                flow { throw RuntimeException("untagged count DB error") }

        val vm = createViewModel()
        advanceUntilIdle()

        val error = vm.error.value
        assertNotNull(error)
        assertTrue(error is TrashOverviewError.LoadFailed)
        if (error is TrashOverviewError.LoadFailed) {
            assertEquals("untaggedCount", error.source)
        }
    }

    @Test
    fun `observeDeletedCount sets LoadFailed when observer throws`() = runTest {
        every { trashRepository.observeTrashedDocumentsCount() } returns
                flow { throw RuntimeException("trash count DB error") }

        val vm = createViewModel()
        advanceUntilIdle()

        val error = vm.error.value
        assertNotNull(error)
        assertTrue(error is TrashOverviewError.LoadFailed)
        if (error is TrashOverviewError.LoadFailed) {
            assertEquals("deletedCount", error.source)
        }
    }

    @Test
    fun `observeOldestDeletedTimestamp sets LoadFailed when observer throws`() = runTest {
        every { trashRepository.observeOldestDeletedTimestamp() } returns
                flow { throw RuntimeException("timestamp DB error") }

        val vm = createViewModel()
        advanceUntilIdle()

        val error = vm.error.value
        assertNotNull(error)
        assertTrue(error is TrashOverviewError.LoadFailed)
        if (error is TrashOverviewError.LoadFailed) {
            assertEquals("deletedTimestamp", error.source)
        }
    }

    @Test
    fun `clearError resets error to null`() = runTest {
        every { documentCountRepository.observeUntaggedDocumentsCount() } returns
                flow { throw RuntimeException("boom") }

        val vm = createViewModel()
        advanceUntilIdle()
        assertNotNull(vm.error.value)

        vm.clearError()
        assertNull(vm.error.value)
    }
}
