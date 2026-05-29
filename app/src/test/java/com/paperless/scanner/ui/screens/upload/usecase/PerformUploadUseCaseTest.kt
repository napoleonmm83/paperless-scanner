package com.paperless.scanner.ui.screens.upload.usecase

import android.content.Context
import android.net.Uri
import android.util.Log
import com.paperless.scanner.R
import com.paperless.scanner.data.analytics.AnalyticsService
import com.paperless.scanner.data.health.ServerHealthMonitor
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.data.repository.UploadQueueRepository
import com.paperless.scanner.util.CoroutineDispatchers
import com.paperless.scanner.util.FileUtils
import com.paperless.scanner.utils.StorageUtil
import com.paperless.scanner.worker.UploadWorkManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PerformUploadUseCaseTest {

    private lateinit var context: Context
    private lateinit var uploadQueueRepository: UploadQueueRepository
    private lateinit var uploadWorkManager: UploadWorkManager
    private lateinit var analyticsService: AnalyticsService
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var serverHealthMonitor: ServerHealthMonitor
    private lateinit var useCase: PerformUploadUseCase

    private val dispatchers = UnconfinedTestDispatcher().let {
        CoroutineDispatchers(io = it, default = it, main = it)
    }

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.i(any(), any()) } returns 0

        mockkObject(FileUtils)
        every { FileUtils.isLocalFileUri(any()) } returns true
        every { FileUtils.copyToLocalStorage(any(), any()) } answers { secondArg() }
        every { FileUtils.fileExists(any()) } returns true

        mockkObject(StorageUtil)
        every { StorageUtil.checkStorageForUpload(any(), any()) } returns StorageUtil.StorageCheckResult(
            hasEnoughSpace = true,
            availableBytes = 1_000_000_000L,
            requiredBytes = 0L,
            message = "OK"
        )
        every { StorageUtil.validateFileSize(any(), any()) } returns Result.success(1_000_000L)

        context = mockk(relaxed = true)
        every { context.getString(R.string.error_adding_to_queue) } returns "Error adding to queue"
        every { context.getString(R.string.error_not_enough_storage) } returns "Not enough storage"

        uploadQueueRepository = mockk(relaxed = true)
        uploadWorkManager = mockk(relaxed = true)
        analyticsService = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)
        serverHealthMonitor = mockk(relaxed = true)
        every { networkMonitor.isWifiConnected } returns MutableStateFlow(true)
        every { networkMonitor.isOnline } returns MutableStateFlow(true)
        every { serverHealthMonitor.isServerReachable } returns MutableStateFlow(true)

        useCase = PerformUploadUseCase(
            context = context,
            uploadQueueRepository = uploadQueueRepository,
            uploadWorkManager = uploadWorkManager,
            analyticsService = analyticsService,
            networkMonitor = networkMonitor,
            serverHealthMonitor = serverHealthMonitor,
            dispatchers = dispatchers,
        )
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
        unmockkObject(FileUtils)
        unmockkObject(StorageUtil)
    }

    @Test
    fun `uploadSingle success emits Queuing then Queued and queues + schedules`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        coEvery {
            uploadQueueRepository.queueUpload(any(), any(), any(), any(), any(), any())
        } returns 1L

        val results = useCase.uploadSingle(uri = uri, title = "Doc").toList()

        assertEquals(listOf(UploadResult.Queuing, UploadResult.Queued), results)
        coVerify { uploadQueueRepository.queueUpload(uri = uri, title = "Doc", tagIds = emptyList(), documentTypeId = null, correspondentId = null) }
        coVerify { uploadWorkManager.scheduleImmediateUpload() }
    }

    @Test
    fun `uploadSingle queue failure emits Queuing then Failed`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        coEvery {
            uploadQueueRepository.queueUpload(any(), any(), any(), any(), any(), any())
        } throws Exception("boom")

        val results = useCase.uploadSingle(uri = uri).toList()

        assertEquals(2, results.size)
        assertEquals(UploadResult.Queuing, results[0])
        val failed = results[1]
        assertTrue(failed is UploadResult.Failed)
        assertEquals("Error adding to queue", (failed as UploadResult.Failed).userMessage)
        assertEquals("boom", failed.technicalDetails)
    }

    @Test
    fun `uploadSingle storage failure emits only Failed without Queuing`() = runTest {
        val uri = mockk<Uri>(relaxed = true)
        every { StorageUtil.checkStorageForUpload(any(), any()) } returns StorageUtil.StorageCheckResult(
            hasEnoughSpace = false,
            availableBytes = 0L,
            requiredBytes = 1_000L,
            message = "disk full"
        )

        val results = useCase.uploadSingle(uri = uri).toList()

        assertEquals(1, results.size)
        val failed = results[0]
        assertTrue(failed is UploadResult.Failed)
        assertEquals("Not enough storage", (failed as UploadResult.Failed).userMessage)
        assertEquals("disk full", failed.technicalDetails)
        coVerify(exactly = 0) { uploadQueueRepository.queueUpload(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `connectivity flows delegate to the monitors`() {
        val wifi = MutableStateFlow(false)
        val online = MutableStateFlow(true)
        val reachable = MutableStateFlow(false)
        every { networkMonitor.isWifiConnected } returns wifi
        every { networkMonitor.isOnline } returns online
        every { serverHealthMonitor.isServerReachable } returns reachable

        val freshUseCase = PerformUploadUseCase(
            context = context,
            uploadQueueRepository = uploadQueueRepository,
            uploadWorkManager = uploadWorkManager,
            analyticsService = analyticsService,
            networkMonitor = networkMonitor,
            serverHealthMonitor = serverHealthMonitor,
            dispatchers = dispatchers,
        )

        assertSame(wifi, freshUseCase.isWifiConnected)
        assertSame(online, freshUseCase.isOnline)
        assertSame(reachable, freshUseCase.isServerReachable)
    }
}
