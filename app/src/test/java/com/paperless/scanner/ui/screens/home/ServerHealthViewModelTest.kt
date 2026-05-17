package com.paperless.scanner.ui.screens.home

import app.cash.turbine.test
import com.paperless.scanner.data.analytics.AnalyticsEvent
import kotlinx.coroutines.launch
import com.paperless.scanner.data.analytics.AnalyticsService
import com.paperless.scanner.data.health.ServerHealthMonitor
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.data.repository.SyncHistoryRepository
import com.paperless.scanner.data.repository.UploadQueueRepository
import com.paperless.scanner.data.sync.SyncManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
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
class ServerHealthViewModelTest {

    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var serverHealthMonitor: ServerHealthMonitor
    private lateinit var uploadQueueRepository: UploadQueueRepository
    private lateinit var syncHistoryRepository: SyncHistoryRepository
    private lateinit var syncManager: SyncManager
    private lateinit var analyticsService: AnalyticsService

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        networkMonitor = mockk(relaxed = true)
        serverHealthMonitor = mockk(relaxed = true)
        uploadQueueRepository = mockk(relaxed = true)
        syncHistoryRepository = mockk(relaxed = true)
        syncManager = mockk(relaxed = true)
        analyticsService = mockk(relaxed = true)

        every { networkMonitor.isOnline } returns MutableStateFlow(true)
        every { serverHealthMonitor.isServerReachable } returns MutableStateFlow(true)
        every { uploadQueueRepository.pendingCount } returns MutableStateFlow(0)
        every { syncManager.pendingChangesCount } returns MutableStateFlow(0)
        every { syncHistoryRepository.observeFailedCount() } returns MutableStateFlow(0)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): ServerHealthViewModel = ServerHealthViewModel(
        networkMonitor = networkMonitor,
        serverHealthMonitor = serverHealthMonitor,
        uploadQueueRepository = uploadQueueRepository,
        syncHistoryRepository = syncHistoryRepository,
        syncManager = syncManager,
        analyticsService = analyticsService,
    )

    @Test
    fun `isOnline reflects networkMonitor StateFlow`() = runTest {
        val online = MutableStateFlow(true)
        every { networkMonitor.isOnline } returns online

        val vm = createViewModel()

        assertEquals(true, vm.isOnline.value)
        online.value = false
        advanceUntilIdle()
        assertEquals(false, vm.isOnline.value)
    }

    @Test
    fun `isServerReachable reflects serverHealthMonitor StateFlow`() = runTest {
        val reachable = MutableStateFlow(false)
        every { serverHealthMonitor.isServerReachable } returns reachable

        val vm = createViewModel()

        assertEquals(false, vm.isServerReachable.value)
        reachable.value = true
        advanceUntilIdle()
        assertEquals(true, vm.isServerReachable.value)
    }

    @Test
    fun `pendingChangesCount sums uploadQueue and syncManager counts`() = runTest {
        val queue = MutableStateFlow(2)
        val pending = MutableStateFlow(3)
        every { uploadQueueRepository.pendingCount } returns queue
        every { syncManager.pendingChangesCount } returns pending

        val vm = createViewModel()
        // Subscribe so WhileSubscribed(5000) kicks the combine in.
        backgroundScope.launch { vm.pendingChangesCount.collect { } }
        advanceUntilIdle()
        assertEquals(5, vm.pendingChangesCount.value)

        queue.value = 0
        advanceUntilIdle()
        assertEquals(3, vm.pendingChangesCount.value)

        pending.value = 0
        advanceUntilIdle()
        assertEquals(0, vm.pendingChangesCount.value)
    }

    @Test
    fun `activeUploadsCount in uiState tracks uploadQueue pendingCount`() = runTest {
        val queue = MutableStateFlow(0)
        every { uploadQueueRepository.pendingCount } returns queue

        val vm = createViewModel()
        advanceUntilIdle()
        assertEquals(0, vm.uiState.value.activeUploadsCount)

        queue.value = 7
        advanceUntilIdle()
        assertEquals(7, vm.uiState.value.activeUploadsCount)
    }

    @Test
    fun `failedSyncCount in uiState tracks syncHistoryRepository observeFailedCount`() = runTest {
        val failed = MutableStateFlow(0)
        every { syncHistoryRepository.observeFailedCount() } returns failed

        val vm = createViewModel()
        advanceUntilIdle()
        assertEquals(0, vm.uiState.value.failedSyncCount)

        failed.value = 4
        advanceUntilIdle()
        assertEquals(4, vm.uiState.value.failedSyncCount)
    }

    @Test
    fun `observeActiveUploadsCount records LoadFailed on upstream error`() = runTest {
        every { uploadQueueRepository.pendingCount } returns flow {
            throw RuntimeException("upload queue boom")
        }

        val vm = createViewModel()
        advanceUntilIdle()

        val err = vm.error.value
        assertNotNull(err)
        assertTrue(err is ServerHealthError.LoadFailed)
        assertEquals("activeUploads", (err as ServerHealthError.LoadFailed).source)
    }

    @Test
    fun `observeFailedSyncCount records LoadFailed on upstream error`() = runTest {
        every { syncHistoryRepository.observeFailedCount() } returns flow {
            throw RuntimeException("sync history boom")
        }

        val vm = createViewModel()
        advanceUntilIdle()

        val err = vm.error.value
        assertNotNull(err)
        assertTrue(err is ServerHealthError.LoadFailed)
        assertEquals("failedSync", (err as ServerHealthError.LoadFailed).source)
    }

    @Test
    fun `clearError resets error to null`() = runTest {
        every { syncHistoryRepository.observeFailedCount() } returns flow {
            throw RuntimeException("boom")
        }
        val vm = createViewModel()
        advanceUntilIdle()
        assertNotNull(vm.error.value)

        vm.clearError()
        assertNull(vm.error.value)
    }

    @Test
    fun `onlineTransition emits exactly once on offline-to-online`() = runTest {
        val online = MutableStateFlow(true)
        every { networkMonitor.isOnline } returns online

        val vm = createViewModel()

        vm.onlineTransition.test {
            // First emission true -> no transition (wasOffline starts false)
            advanceUntilIdle()
            expectNoEvents()

            // Go offline, then back online: must emit once.
            online.value = false
            advanceUntilIdle()
            expectNoEvents()

            online.value = true
            advanceUntilIdle()
            awaitItem()

            // Staying online must not re-emit.
            online.value = true
            advanceUntilIdle()
            expectNoEvents()

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onlineTransition does not emit when starting offline`() = runTest {
        val online = MutableStateFlow(false)
        every { networkMonitor.isOnline } returns online

        val vm = createViewModel()
        vm.onlineTransition.test {
            advanceUntilIdle()
            // Initial offline emission -> no transition
            expectNoEvents()

            online.value = true
            advanceUntilIdle()
            // First offline -> online transition fires
            awaitItem()

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `network status changes tracked in analytics`() = runTest {
        val online = MutableStateFlow(true)
        every { networkMonitor.isOnline } returns online

        createViewModel()
        advanceUntilIdle()

        verify { analyticsService.trackEvent(AnalyticsEvent.NetworkStatusChanged(isOnline = true)) }

        online.value = false
        advanceUntilIdle()

        verify { analyticsService.trackEvent(AnalyticsEvent.NetworkStatusChanged(isOnline = false)) }
        verify { analyticsService.trackEvent(AnalyticsEvent.OfflineModeUsed) }
    }
}
