package com.paperless.scanner.ui.screens.home

import app.cash.turbine.test
import com.paperless.scanner.domain.model.DocumentsResponse
import com.paperless.scanner.data.repository.DocumentCountRepository
import com.paperless.scanner.data.repository.DocumentListRepository
import com.paperless.scanner.data.repository.TrashRepository
import com.paperless.scanner.data.repository.UploadQueueRepository
import com.paperless.scanner.data.sync.SyncManager
import com.paperless.scanner.data.analytics.AnalyticsService
import kotlinx.coroutines.flow.flow
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private lateinit var documentListRepository: DocumentListRepository
    private lateinit var documentCountRepository: DocumentCountRepository
    private lateinit var trashRepository: TrashRepository
    private lateinit var uploadQueueRepository: UploadQueueRepository
    private lateinit var syncManager: SyncManager
    private lateinit var analyticsService: AnalyticsService

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        documentListRepository = mockk(relaxed = true)
        documentCountRepository = mockk(relaxed = true)
        trashRepository = mockk(relaxed = true)
        uploadQueueRepository = mockk(relaxed = true)
        syncManager = mockk(relaxed = true)
        analyticsService = mockk(relaxed = true)

        every { uploadQueueRepository.pendingCount } returns MutableStateFlow(0)
        every { syncManager.pendingChangesCount } returns MutableStateFlow(0)
        every { documentCountRepository.observeUntaggedDocumentsCount() } returns MutableStateFlow(0)
        every { trashRepository.observeTrashedDocumentsCount() } returns MutableStateFlow(0)
        every { trashRepository.observeOldestDeletedTimestamp() } returns MutableStateFlow(null)
        coEvery { documentCountRepository.getDocumentCount(any()) } returns Result.success(0)
        coEvery { documentListRepository.getDocuments(any(), any(), any(), any(), any(), any(), any(), any()) } returns
                Result.success(DocumentsResponse(count = 0, results = emptyList()))
        coEvery { documentCountRepository.getUntaggedCount() } returns Result.success(0)
        coEvery { trashRepository.getTrashDocuments(any(), any()) } returns
                Result.success(DocumentsResponse(count = 0, results = emptyList(), next = null))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): HomeViewModel = HomeViewModel(
        documentListRepository = documentListRepository,
        documentCountRepository = documentCountRepository,
        trashRepository = trashRepository,
        uploadQueueRepository = uploadQueueRepository,
        syncManager = syncManager,
        analyticsService = analyticsService,
    )

    @Test
    fun `initial uiState has correct defaults`() = runTest {
        val viewModel = createViewModel()

        val initialState = viewModel.uiState.value
        assertTrue(initialState.isLoading)
        assertNull(viewModel.errorState.value)
    }

    @Test
    fun `loadDashboardData completes without errors`() = runTest {
        val viewModel = createViewModel()
        runCurrent()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(0, state.stats.totalDocuments)
    }

    @Test
    fun `untagged count flow emissions are reflected in uiState`() = runTest {
        val untaggedFlow = MutableStateFlow(0)
        every { documentCountRepository.observeUntaggedDocumentsCount() } returns untaggedFlow

        val vm = createViewModel()

        vm.uiState.test {
            // Drain any state changes from init() until the count baseline is 0.
            var state = awaitItem()
            while (state.untaggedCount != 0 || state.isLoading) {
                state = awaitItem()
            }

            untaggedFlow.value = 7
            assertEquals(7, awaitItem().untaggedCount)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `deleted count flow emissions are reflected in uiState`() = runTest {
        val trashCountFlow = MutableStateFlow(0)
        every { trashRepository.observeTrashedDocumentsCount() } returns trashCountFlow

        val vm = createViewModel()

        vm.uiState.test {
            var state = awaitItem()
            while (state.deletedCount != 0 || state.isLoading) {
                state = awaitItem()
            }

            trashCountFlow.value = 3
            assertEquals(3, awaitItem().deletedCount)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeUntaggedCount sets LoadFailed on errorState when observer throws`() = runTest {
        every { documentCountRepository.observeUntaggedDocumentsCount() } returns
                flow { throw RuntimeException("untagged count DB error") }

        val vm = createViewModel()
        advanceUntilIdle()

        val error = vm.errorState.value
        assertNotNull(error)
        assertTrue(error is HomeError.LoadFailed)
        if (error is HomeError.LoadFailed) {
            assertEquals("untaggedCount", error.source)
        }
    }

    @Test
    fun `observeDeletedCount sets LoadFailed on errorState when observer throws`() = runTest {
        every { trashRepository.observeTrashedDocumentsCount() } returns
                flow { throw RuntimeException("trash count DB error") }

        val vm = createViewModel()
        advanceUntilIdle()

        val error = vm.errorState.value
        assertNotNull(error)
        assertTrue(error is HomeError.LoadFailed)
        if (error is HomeError.LoadFailed) {
            assertEquals("deletedCount", error.source)
        }
    }

    @Test
    fun `clearHomeError resets errorState to null`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        vm.clearHomeError()
        assertNull(vm.errorState.value)
    }
}
