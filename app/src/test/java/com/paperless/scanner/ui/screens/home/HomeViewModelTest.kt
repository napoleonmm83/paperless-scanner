package com.paperless.scanner.ui.screens.home

import app.cash.turbine.test
import com.paperless.scanner.domain.model.DocumentsResponse
import com.paperless.scanner.data.repository.DocumentCountRepository
import com.paperless.scanner.data.repository.DocumentListRepository
import com.paperless.scanner.data.repository.UploadQueueRepository
import com.paperless.scanner.data.sync.SyncManager
import com.paperless.scanner.data.analytics.AnalyticsEvent
import com.paperless.scanner.data.analytics.AnalyticsService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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

    @Test
    fun `onPollingTick coalesces re-entrant ticks into a single refresh`() = runTest {
        // pollingRefreshJob guard: while a tick's refresh is in flight, further
        // ticks are dropped. getDocumentCount runs once for init + once for the
        // first tick; the second tick is coalesced away.
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        coEvery { documentCountRepository.getDocumentCount(any()) } coAnswers {
            gate.await()
            Result.success(0)
        }

        val vm = createViewModel()
        runCurrent() // init's loadDashboardData() parks on the gate

        vm.onPollingTick() // launches pollingRefreshJob, parks on the gate
        runCurrent()
        vm.onPollingTick() // dropped: pollingRefreshJob still active
        runCurrent()

        gate.complete(Unit)
        advanceUntilIdle()

        coVerify(exactly = 2) { documentCountRepository.getDocumentCount(any()) }
    }

    @Test
    fun `onPollingTick refreshes hero stats and applies live pendingUploads`() = runTest {
        every { uploadQueueRepository.pendingCount } returns MutableStateFlow(5)
        coEvery { documentCountRepository.getDocumentCount(any()) } returns Result.success(7)

        val vm = createViewModel()
        advanceUntilIdle() // init: totalDocuments=7, pendingUploads=5

        // A later tick must re-run loadStats and pick up the new server count;
        // totalDocuments is only ever written by loadStats, so a change here is
        // attributable to onPollingTick (not the observePendingUploads collector).
        coEvery { documentCountRepository.getDocumentCount(any()) } returns Result.success(9)
        vm.onPollingTick()
        advanceUntilIdle()

        val stats = vm.uiState.value.stats
        assertEquals(9, stats.totalDocuments)
        assertEquals(5, stats.pendingUploads)
    }

    @Test
    fun `refreshDashboard forces a server refresh and updates totals and lastSyncedAt`() = runTest {
        coEvery { documentCountRepository.getDocumentCount(any()) } returns Result.success(42)

        val vm = createViewModel()
        advanceUntilIdle()

        coEvery { documentCountRepository.getDocumentCount(any()) } returns Result.success(50)
        vm.refreshDashboard()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(50, state.stats.totalDocuments)
        assertNotNull(state.lastSyncedAt)
        // loadStats always forces a server read (forceRefresh = true).
        coVerify(atLeast = 2) { documentCountRepository.getDocumentCount(true) }
    }

    @Test
    fun `resetState restores HomeUiState defaults`() = runTest {
        coEvery { documentCountRepository.getDocumentCount(any()) } returns Result.success(10)

        val vm = createViewModel()
        advanceUntilIdle()
        assertEquals(10, vm.uiState.value.stats.totalDocuments)
        assertNotNull(vm.uiState.value.lastSyncedAt)

        vm.resetState()

        val state = vm.uiState.value
        assertTrue(state.isLoading)
        assertNull(state.lastSyncedAt)
        assertEquals(0, state.stats.totalDocuments)
        assertEquals(0, state.stats.thisMonth)
        assertEquals(0, state.stats.pendingUploads)
    }

    @Test
    fun `loadStats defaults totalDocuments to zero without surfacing an error`() = runTest {
        coEvery { documentCountRepository.getDocumentCount(any()) } returns
                Result.failure(RuntimeException("count endpoint down"))

        val vm = createViewModel()
        advanceUntilIdle()

        // Hero-stat failures are intentionally swallowed at WARNING (no snackbar).
        assertEquals(0, vm.uiState.value.stats.totalDocuments)
        assertNull(vm.errorState.value)
    }

    @Test
    fun `loadStats defaults thisMonth to zero without surfacing an error`() = runTest {
        coEvery { documentCountRepository.getDocumentCount(any()) } returns Result.success(5)
        coEvery {
            documentListRepository.getDocuments(any(), any(), any(), any(), any(), any(), any(), any())
        } returns Result.failure(RuntimeException("documents endpoint down"))

        val vm = createViewModel()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(5, state.stats.totalDocuments)
        assertEquals(0, state.stats.thisMonth)
        assertNull(vm.errorState.value)
    }

    @Test
    fun `loadStats populates totals and caps thisMonth at 30`() = runTest {
        coEvery { documentCountRepository.getDocumentCount(any()) } returns Result.success(123)
        coEvery {
            documentListRepository.getDocuments(any(), any(), any(), any(), any(), any(), any(), any())
        } returns Result.success(DocumentsResponse(count = 99, results = emptyList()))

        val vm = createViewModel()
        advanceUntilIdle()

        val stats = vm.uiState.value.stats
        assertEquals(123, stats.totalDocuments)
        assertEquals(30, stats.thisMonth) // minOf(count, 30)
    }

    @Test
    fun `init tracks AppOpened analytics event`() = runTest {
        createViewModel()
        runCurrent()

        verify { analyticsService.trackEvent(AnalyticsEvent.AppOpened) }
    }
}
