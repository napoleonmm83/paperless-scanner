package com.paperless.scanner.ui.screens.home

import app.cash.turbine.test
import com.paperless.scanner.data.analytics.AnalyticsEvent
import com.paperless.scanner.testing.fakes.FakeAnalyticsService
import com.paperless.scanner.testing.fakes.FakeNetworkMonitor
import com.paperless.scanner.testing.fakes.FakeServerHealthMonitor
import com.paperless.scanner.testing.fakes.FakeSyncHistoryRepository
import com.paperless.scanner.testing.fakes.FakeSyncManager
import com.paperless.scanner.testing.fakes.FakeUploadQueueRepository
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

/**
 * #239 (plan-03): migrated from mockk(relaxed = true) to the typed fakes in
 * testing/fakes/ — relaxed mocks silently accept any call and emit nothing by
 * default, which let contract drift pass unnoticed. The fakes implement the
 * #321 *Contract interfaces, so a contract change breaks these tests at compile
 * time instead.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ServerHealthViewModelTest {

    private lateinit var networkMonitor: FakeNetworkMonitor
    private lateinit var serverHealthMonitor: FakeServerHealthMonitor
    private lateinit var uploadQueueRepository: FakeUploadQueueRepository
    private lateinit var syncHistoryRepository: FakeSyncHistoryRepository
    private lateinit var syncManager: FakeSyncManager
    private lateinit var analyticsService: FakeAnalyticsService

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        networkMonitor = FakeNetworkMonitor(initiallyOnline = true)
        serverHealthMonitor = FakeServerHealthMonitor(initiallyReachable = true)
        uploadQueueRepository = FakeUploadQueueRepository()
        syncHistoryRepository = FakeSyncHistoryRepository()
        syncManager = FakeSyncManager()
        analyticsService = FakeAnalyticsService()
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
        val vm = createViewModel()

        vm.isOnline.test {
            assertEquals(true, awaitItem())
            networkMonitor.online.value = false
            assertEquals(false, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `isServerReachable reflects serverHealthMonitor StateFlow`() = runTest {
        serverHealthMonitor.reachable.value = false

        val vm = createViewModel()

        vm.isServerReachable.test {
            assertEquals(false, awaitItem())
            serverHealthMonitor.reachable.value = true
            assertEquals(true, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `pendingChangesCount sums uploadQueue and syncManager counts`() = runTest {
        val queue = MutableStateFlow(2)
        uploadQueueRepository.pendingCountFlow = queue
        syncManager.pendingChanges.value = 3

        val vm = createViewModel()
        // Turbine's collector is the WhileSubscribed(5000) subscriber that kicks the combine in.
        vm.pendingChangesCount.test {
            assertEquals(0, awaitItem()) // stateIn initial value before the combine emits
            assertEquals(5, awaitItem())

            queue.value = 0
            assertEquals(3, awaitItem())

            syncManager.pendingChanges.value = 0
            assertEquals(0, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `activeUploadsCount in uiState tracks uploadQueue pendingCount`() = runTest {
        val queue = MutableStateFlow(0)
        uploadQueueRepository.pendingCountFlow = queue

        val vm = createViewModel()
        advanceUntilIdle()

        vm.uiState.test {
            assertEquals(0, awaitItem().activeUploadsCount)

            queue.value = 7
            assertEquals(7, awaitItem().activeUploadsCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `failedSyncCount in uiState tracks syncHistoryRepository observeFailedCount`() = runTest {
        val failed = MutableStateFlow(0)
        syncHistoryRepository.failedCountFlow = failed

        val vm = createViewModel()
        advanceUntilIdle()

        vm.uiState.test {
            assertEquals(0, awaitItem().failedSyncCount)

            failed.value = 4
            assertEquals(4, awaitItem().failedSyncCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `observeActiveUploadsCount records LoadFailed on upstream error`() = runTest {
        uploadQueueRepository.pendingCountFlow = flow {
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
        syncHistoryRepository.failedCountFlow = flow {
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
        syncHistoryRepository.failedCountFlow = flow {
            throw RuntimeException("boom")
        }
        val vm = createViewModel()
        advanceUntilIdle()

        vm.error.test {
            assertNotNull(awaitItem())

            vm.clearError()
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onlineTransition emits exactly once on offline-to-online`() = runTest {
        val vm = createViewModel()

        vm.onlineTransition.test {
            // First emission true -> no transition (wasOffline starts false)
            advanceUntilIdle()
            expectNoEvents()

            // Go offline, then back online: must emit once.
            networkMonitor.online.value = false
            advanceUntilIdle()
            expectNoEvents()

            networkMonitor.online.value = true
            advanceUntilIdle()
            awaitItem()

            // Staying online must not re-emit.
            networkMonitor.online.value = true
            advanceUntilIdle()
            expectNoEvents()

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `onlineTransition does not emit when starting offline`() = runTest {
        networkMonitor.online.value = false

        val vm = createViewModel()
        vm.onlineTransition.test {
            advanceUntilIdle()
            // Initial offline emission -> no transition
            expectNoEvents()

            networkMonitor.online.value = true
            advanceUntilIdle()
            // First offline -> online transition fires
            awaitItem()

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `network status changes tracked in analytics`() = runTest {
        createViewModel()
        advanceUntilIdle()

        assertTrue(
            "expected NetworkStatusChanged(online) in ${analyticsService.trackedEvents}",
            analyticsService.trackedEvents.contains(AnalyticsEvent.NetworkStatusChanged(isOnline = true)),
        )

        networkMonitor.online.value = false
        advanceUntilIdle()

        assertTrue(
            "expected NetworkStatusChanged(offline) in ${analyticsService.trackedEvents}",
            analyticsService.trackedEvents.contains(AnalyticsEvent.NetworkStatusChanged(isOnline = false)),
        )
        assertTrue(
            "expected OfflineModeUsed in ${analyticsService.trackedEvents}",
            analyticsService.trackedEvents.contains(AnalyticsEvent.OfflineModeUsed),
        )
    }
}
