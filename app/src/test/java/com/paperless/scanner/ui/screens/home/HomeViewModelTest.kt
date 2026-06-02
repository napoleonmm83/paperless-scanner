package com.paperless.scanner.ui.screens.home

import app.cash.turbine.test
import com.paperless.scanner.domain.model.DocumentsResponse
import com.paperless.scanner.data.repository.DocumentCountRepository
import com.paperless.scanner.data.repository.DocumentListRepository
import com.paperless.scanner.data.repository.UploadQueueRepository
import com.paperless.scanner.data.sync.SyncManager
import com.paperless.scanner.data.analytics.AnalyticsService
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
    private lateinit var uploadQueueRepository: UploadQueueRepository
    private lateinit var syncManager: SyncManager
    private lateinit var analyticsService: AnalyticsService

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        documentListRepository = mockk(relaxed = true)
        documentCountRepository = mockk(relaxed = true)
        uploadQueueRepository = mockk(relaxed = true)
        syncManager = mockk(relaxed = true)
        analyticsService = mockk(relaxed = true)

        every { uploadQueueRepository.pendingCount } returns MutableStateFlow(0)
        every { syncManager.pendingChangesCount } returns MutableStateFlow(0)
        coEvery { documentCountRepository.getDocumentCount(any()) } returns Result.success(0)
        coEvery { documentListRepository.getDocuments(any(), any(), any(), any(), any(), any(), any(), any()) } returns
                Result.success(DocumentsResponse(count = 0, results = emptyList()))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): HomeViewModel = HomeViewModel(
        documentListRepository = documentListRepository,
        documentCountRepository = documentCountRepository,
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
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(0, state.stats.totalDocuments)
        assertNotNull(state.lastSyncedAt)
    }

    @Test
    fun `pendingChanges flow emissions propagate into stats pendingUploads`() = runTest {
        val uploadCount = MutableStateFlow(0)
        every { uploadQueueRepository.pendingCount } returns uploadCount

        val vm = createViewModel()
        advanceUntilIdle()

        vm.uiState.test {
            val baseline = expectMostRecentItem()
            assertEquals(false, baseline.isLoading)
            assertEquals(0, baseline.stats.pendingUploads)

            uploadCount.value = 4
            assertEquals(4, awaitItem().stats.pendingUploads)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observePendingUploads sets LoadFailed on errorState when upstream throws`() = runTest {
        every { uploadQueueRepository.pendingCount } returns
                flow { throw RuntimeException("upload queue DB error") }

        val vm = createViewModel()
        advanceUntilIdle()

        val error = vm.errorState.value
        assertNotNull(error)
        assertTrue(error is HomeError.LoadFailed)
        if (error is HomeError.LoadFailed) {
            assertEquals("pendingUploads", error.source)
        }
    }

    @Test
    fun `clearHomeError resets errorState to null`() = runTest {
        every { uploadQueueRepository.pendingCount } returns
                flow { throw RuntimeException("seeded error") }

        val vm = createViewModel()
        advanceUntilIdle()
        assertNotNull(vm.errorState.value)

        vm.clearHomeError()
        assertNull(vm.errorState.value)
    }

    @Test
    fun `refreshDashboardIfNeeded returns false within debounce window after init`() = runTest {
        val vm = createViewModel()
        advanceUntilIdle()

        // Initial loadDashboardData() set lastRefreshTimestamp = wall-clock
        // now. A second call within 30s of that must be debounced.
        assertFalse(vm.refreshDashboardIfNeeded())
    }

    @Test
    fun `onNetworkReconnected coalesces rapid flapping into a single refresh`() = runTest {
        // #238/#295: rapid offline<->online flapping must not stack refreshes. The guard
        // only inspects reconnectRefreshJob (init's loadDashboardData does NOT set it), so
        // the first onNetworkReconnected() launches one job; every trigger fired while that
        // job is still active is dropped. The gate keeps each launched job in flight across
        // the whole burst, so getDocumentCount runs once for init + once for the burst = 2.
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { documentCountRepository.getDocumentCount(any()) } coAnswers {
            gate.await()
            Result.success(0)
        }

        val vm = createViewModel()
        runCurrent() // init's loadDashboardData() job starts and parks on the gate

        repeat(10) { vm.onNetworkReconnected() }
        runCurrent() // first reconnect launches + parks; triggers 2..10 dropped by the guard

        gate.complete(Unit)
        advanceUntilIdle()

        // 1 fetch from init + 1 from the entire 10-trigger reconnect burst.
        coVerify(exactly = 2) { documentCountRepository.getDocumentCount(any()) }
    }
}
