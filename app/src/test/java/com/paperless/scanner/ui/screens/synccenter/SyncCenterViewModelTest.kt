package com.paperless.scanner.ui.screens.synccenter

import android.content.Context
import com.paperless.scanner.data.database.PendingUpload
import com.paperless.scanner.data.database.UploadStatus
import com.paperless.scanner.data.database.dao.PendingChangeDao
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.data.health.DeviceConditionsMonitor
import com.paperless.scanner.data.repository.SyncHistoryRepository
import com.paperless.scanner.data.repository.UploadQueueRepository
import app.cash.turbine.test
import com.paperless.scanner.testing.fakes.FakeNetworkMonitor
import com.paperless.scanner.testing.fakes.FakeServerHealthMonitor
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Verifies the SyncCenter wiring: the condition flows are mapped to the correct
 * [computeWaitReason] arguments (the priority logic itself is covered by [SyncWaitReasonTest]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncCenterViewModelTest {

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setup() = Dispatchers.setMain(testDispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun pendingUpload(uploadStatus: UploadStatus = UploadStatus.PENDING): PendingUpload =
        mockk { every { status } returns uploadStatus }

    private fun createViewModel(
        uploads: List<PendingUpload> = listOf(pendingUpload()),
        online: Boolean = true,
        unmetered: Boolean = true,
        serverReachable: Boolean = true,
        unmeteredOnly: Boolean = false,
        batteryLow: Boolean = false,
        storageLow: Boolean = false,
    ): SyncCenterViewModel {
        val uploadRepo = mockk<UploadQueueRepository>(relaxed = true) {
            every { allPendingUploads } returns flowOf(uploads)
        }
        val changeDao = mockk<PendingChangeDao>(relaxed = true) {
            every { observePendingChanges() } returns flowOf(emptyList())
        }
        val historyRepo = mockk<SyncHistoryRepository>(relaxed = true) {
            every { observeRecentHistory(50) } returns flowOf(emptyList())
            every { observeFailed() } returns flowOf(emptyList())
        }
        val network = FakeNetworkMonitor(initiallyOnline = online).apply { this.unmetered.value = unmetered }
        val server = FakeServerHealthMonitor(initiallyReachable = serverReachable)
        val token = mockk<TokenManager> { every { uploadUnmeteredOnly } returns flowOf(unmeteredOnly) }
        val device = mockk<DeviceConditionsMonitor> {
            every { isBatteryLow } returns flowOf(batteryLow)
            every { isStorageLow } returns flowOf(storageLow)
        }
        return SyncCenterViewModel(
            context = mockk<Context>(relaxed = true),
            uploadQueueRepository = uploadRepo,
            pendingChangeDao = changeDao,
            syncHistoryRepository = historyRepo,
            networkMonitor = network,
            serverHealthMonitor = server,
            tokenManager = token,
            deviceConditionsMonitor = device,
        )
    }

    @Test
    fun `active upload with no blockers reports UPLOADING`() = runTest {
        createViewModel().uiState.test {
            assertEquals(SyncWaitReason.UPLOADING, expectMostRecentItem().waitReason)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `offline reports OFFLINE`() = runTest {
        createViewModel(online = false).uiState.test {
            assertEquals(SyncWaitReason.OFFLINE, expectMostRecentItem().waitReason)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `server unreachable reports SERVER_UNREACHABLE`() = runTest {
        createViewModel(serverReachable = false).uiState.test {
            assertEquals(SyncWaitReason.SERVER_UNREACHABLE, expectMostRecentItem().waitReason)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `unmetered-only on a metered network reports WAITING_FOR_WIFI`() = runTest {
        createViewModel(unmeteredOnly = true, unmetered = false).uiState.test {
            assertEquals(SyncWaitReason.WAITING_FOR_WIFI, expectMostRecentItem().waitReason)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `battery low reports BATTERY_LOW`() = runTest {
        createViewModel(batteryLow = true).uiState.test {
            assertEquals(SyncWaitReason.BATTERY_LOW, expectMostRecentItem().waitReason)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `no pending work reports NONE`() = runTest {
        createViewModel(uploads = emptyList(), online = false).uiState.test {
            assertEquals(SyncWaitReason.NONE, expectMostRecentItem().waitReason)
            cancelAndIgnoreRemainingEvents()
        }
    }
}
