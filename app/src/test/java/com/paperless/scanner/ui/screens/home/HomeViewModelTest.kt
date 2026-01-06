package com.paperless.scanner.ui.screens.home

import app.cash.turbine.test
import com.paperless.scanner.domain.model.Document
import com.paperless.scanner.domain.model.DocumentsResponse
import com.paperless.scanner.domain.model.PaperlessTask
import com.paperless.scanner.domain.model.Tag
import com.paperless.scanner.data.repository.DocumentRepository
import com.paperless.scanner.data.repository.TagRepository
import com.paperless.scanner.data.repository.TaskRepository
import com.paperless.scanner.data.repository.UploadQueueRepository
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.data.sync.SyncManager
import io.mockk.coEvery
import io.mockk.coVerify
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModelTest {

    private lateinit var documentRepository: DocumentRepository
    private lateinit var tagRepository: TagRepository
    private lateinit var taskRepository: TaskRepository
    private lateinit var uploadQueueRepository: UploadQueueRepository
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var syncManager: SyncManager

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        documentRepository = mockk(relaxed = true)
        tagRepository = mockk(relaxed = true)
        taskRepository = mockk(relaxed = true)
        uploadQueueRepository = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)
        syncManager = mockk(relaxed = true)

        // Default mock responses
        every { networkMonitor.isOnline } returns MutableStateFlow(true)
        every { uploadQueueRepository.pendingCount } returns MutableStateFlow(0)
        every { syncManager.pendingChangesCount } returns MutableStateFlow(0)
        coEvery { tagRepository.getTags() } returns Result.success(emptyList())
        coEvery { documentRepository.getDocumentCount() } returns Result.success(0)
        coEvery { documentRepository.getDocuments(any(), any(), any(), any(), any(), any()) } returns
                Result.success(DocumentsResponse(count = 0, results = emptyList()))
        coEvery { documentRepository.getRecentDocuments(any()) } returns Result.success(emptyList())
        coEvery { taskRepository.getUnacknowledgedTasks() } returns Result.success(emptyList())
        coEvery { uploadQueueRepository.getPendingUploadCount() } returns 0
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): HomeViewModel {
        return HomeViewModel(
            documentRepository = documentRepository,
            tagRepository = tagRepository,
            taskRepository = taskRepository,
            uploadQueueRepository = uploadQueueRepository,
            networkMonitor = networkMonitor,
            syncManager = syncManager
        )
    }

    // ==================== Load Dashboard Data Tests ====================

    @Test
    fun `loadDashboardData sets isLoading true then false`() = runTest {
        val viewModel = createViewModel()
        runCurrent()

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isLoading)
        }
    }

    @Test
    fun `loadDashboardData loads document count correctly`() = runTest {
        coEvery { documentRepository.getDocumentCount() } returns Result.success(150)

        val viewModel = createViewModel()
        runCurrent()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(150, state.stats.totalDocuments)
        }
    }

    @Test
    fun `loadDashboardData loads pending uploads count`() = runTest {
        coEvery { uploadQueueRepository.getPendingUploadCount() } returns 5

        val viewModel = createViewModel()
        runCurrent()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(5, state.stats.pendingUploads)
        }
    }

    @Test
    fun `loadDashboardData loads recent documents`() = runTest {
        val now = LocalDateTime.now().minusMinutes(30)
        val dateString = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))

        val documents = listOf(
            Document(
                id = 1,
                title = "Test Document",
                created = dateString,
                modified = dateString,
                added = dateString,
                tags = emptyList()
            )
        )
        coEvery { documentRepository.getRecentDocuments(any()) } returns Result.success(documents)

        val viewModel = createViewModel()
        runCurrent()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(1, state.recentDocuments.size)
            assertEquals("Test Document", state.recentDocuments[0].title)
        }
    }

    @Test
    fun `loadDashboardData maps tag info to recent documents`() = runTest {
        val now = LocalDateTime.now()
        val dateString = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))

        val tags = listOf(Tag(id = 5, name = "Invoice", color = "#FF0000"))
        val documents = listOf(
            Document(
                id = 1,
                title = "Test",
                created = dateString,
                modified = dateString,
                added = dateString,
                tags = listOf(5)
            )
        )

        coEvery { tagRepository.getTags() } returns Result.success(tags)
        coEvery { documentRepository.getRecentDocuments(any()) } returns Result.success(documents)

        val viewModel = createViewModel()
        runCurrent()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("Invoice", state.recentDocuments[0].tagName)
        }
    }

    @Test
    fun `loadDashboardData counts untagged documents`() = runTest {
        val now = LocalDateTime.now()
        val dateString = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))

        val documents = listOf(
            Document(id = 1, title = "Tagged", created = dateString, modified = dateString, added = dateString, tags = listOf(1)),
            Document(id = 2, title = "Untagged1", created = dateString, modified = dateString, added = dateString, tags = emptyList()),
            Document(id = 3, title = "Untagged2", created = dateString, modified = dateString, added = dateString, tags = emptyList())
        )
        coEvery { documentRepository.getRecentDocuments(any()) } returns Result.success(documents)

        val viewModel = createViewModel()
        runCurrent()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(2, state.untaggedCount)
        }
    }

    // ==================== Processing Tasks Tests ====================

    @Test
    fun `loadDashboardData loads processing tasks`() = runTest {
        val now = LocalDateTime.now()
        val dateString = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))

        val tasks = listOf(
            PaperlessTask(
                id = 1,
                taskId = "task-123",
                taskFileName = "document.pdf",
                dateCreated = dateString,
                type = "file",
                status = PaperlessTask.STATUS_SUCCESS,
                acknowledged = false
            )
        )
        coEvery { taskRepository.getUnacknowledgedTasks() } returns Result.success(tasks)

        val viewModel = createViewModel()
        runCurrent()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(1, state.processingTasks.size)
            assertEquals("document.pdf", state.processingTasks[0].fileName)
            assertEquals(TaskStatus.SUCCESS, state.processingTasks[0].status)
        }
    }

    @Test
    fun `processing task status is mapped correctly for PENDING`() = runTest {
        val now = LocalDateTime.now()
        val dateString = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))

        val tasks = listOf(
            PaperlessTask(
                id = 1,
                taskId = "task-1",
                taskFileName = "pending.pdf",
                dateCreated = dateString,
                type = "file",
                status = PaperlessTask.STATUS_PENDING,
                acknowledged = false
            )
        )
        coEvery { taskRepository.getUnacknowledgedTasks() } returns Result.success(tasks)

        val viewModel = createViewModel()
        runCurrent()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(TaskStatus.PENDING, state.processingTasks[0].status)
        }
    }

    @Test
    fun `processing task status is mapped correctly for STARTED`() = runTest {
        val now = LocalDateTime.now()
        val dateString = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))

        val tasks = listOf(
            PaperlessTask(
                id = 1,
                taskId = "task-1",
                taskFileName = "processing.pdf",
                dateCreated = dateString,
                type = "file",
                status = PaperlessTask.STATUS_STARTED,
                acknowledged = false
            )
        )
        coEvery { taskRepository.getUnacknowledgedTasks() } returns Result.success(tasks)

        val viewModel = createViewModel()
        runCurrent()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(TaskStatus.PROCESSING, state.processingTasks[0].status)
        }
    }

    @Test
    fun `processing task status is mapped correctly for FAILURE`() = runTest {
        val now = LocalDateTime.now()
        val dateString = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))

        val tasks = listOf(
            PaperlessTask(
                id = 1,
                taskId = "task-1",
                taskFileName = "failed.pdf",
                dateCreated = dateString,
                type = "file",
                status = PaperlessTask.STATUS_FAILURE,
                acknowledged = false,
                result = "Parsing error"
            )
        )
        coEvery { taskRepository.getUnacknowledgedTasks() } returns Result.success(tasks)

        val viewModel = createViewModel()
        runCurrent()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(TaskStatus.FAILURE, state.processingTasks[0].status)
            assertEquals("Parsing error", state.processingTasks[0].resultMessage)
        }
    }

    @Test
    fun `unknown task file shows default name`() = runTest {
        val now = LocalDateTime.now()
        val dateString = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))

        val tasks = listOf(
            PaperlessTask(
                id = 1,
                taskId = "task-1",
                taskFileName = null,
                dateCreated = dateString,
                type = "file",
                status = PaperlessTask.STATUS_SUCCESS,
                acknowledged = false
            )
        )
        coEvery { taskRepository.getUnacknowledgedTasks() } returns Result.success(tasks)

        val viewModel = createViewModel()
        runCurrent()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals("Unbekanntes Dokument", state.processingTasks[0].fileName)
        }
    }

    // ==================== Acknowledge Task Tests ====================

    @Test
    fun `acknowledgeTask removes task from UI immediately`() = runTest {
        val now = LocalDateTime.now()
        val dateString = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))

        val tasks = listOf(
            PaperlessTask(id = 1, taskId = "task-1", taskFileName = "doc1.pdf", dateCreated = dateString, type = "file", status = PaperlessTask.STATUS_SUCCESS, acknowledged = false),
            PaperlessTask(id = 2, taskId = "task-2", taskFileName = "doc2.pdf", dateCreated = dateString, type = "file", status = PaperlessTask.STATUS_SUCCESS, acknowledged = false)
        )
        coEvery { taskRepository.getUnacknowledgedTasks() } returns Result.success(tasks)
        coEvery { taskRepository.acknowledgeTasks(any()) } returns Result.success(Unit)

        val viewModel = createViewModel()
        runCurrent()

        // Verify initial state has 2 tasks
        assertEquals(2, viewModel.uiState.value.processingTasks.size)

        // Acknowledge task 1
        viewModel.acknowledgeTask(1)

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(1, state.processingTasks.size)
            assertEquals(2, state.processingTasks[0].id)
        }
    }

    @Test
    fun `acknowledgeTask calls repository with correct task id`() = runTest {
        val now = LocalDateTime.now()
        val dateString = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))

        val tasks = listOf(
            PaperlessTask(id = 5, taskId = "task-5", taskFileName = "doc.pdf", dateCreated = dateString, type = "file", status = PaperlessTask.STATUS_SUCCESS, acknowledged = false)
        )
        coEvery { taskRepository.getUnacknowledgedTasks() } returns Result.success(tasks)
        coEvery { taskRepository.acknowledgeTasks(any()) } returns Result.success(Unit)

        val viewModel = createViewModel()
        runCurrent()

        viewModel.acknowledgeTask(5)
        runCurrent()

        coVerify { taskRepository.acknowledgeTasks(listOf(5)) }
    }

    // ==================== Refresh Tasks Tests ====================

    @Test
    fun `refreshTasks updates processing tasks`() = runTest {
        val now = LocalDateTime.now()
        val dateString = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))

        // Initial empty tasks
        coEvery { taskRepository.getUnacknowledgedTasks() } returns Result.success(emptyList())

        val viewModel = createViewModel()
        runCurrent()

        // Now mock new tasks
        val newTasks = listOf(
            PaperlessTask(id = 1, taskId = "new-task", taskFileName = "new.pdf", dateCreated = dateString, type = "file", status = PaperlessTask.STATUS_PENDING, acknowledged = false)
        )
        coEvery { taskRepository.getUnacknowledgedTasks() } returns Result.success(newTasks)

        viewModel.refreshTasks()
        runCurrent()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(1, state.processingTasks.size)
            assertEquals("new.pdf", state.processingTasks[0].fileName)
        }
    }

    // ==================== Time Formatting Tests ====================

    @Test
    fun `recent document shows time ago for minutes`() = runTest {
        val thirtyMinsAgo = LocalDateTime.now().minusMinutes(30)
        val dateString = thirtyMinsAgo.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))

        val documents = listOf(
            Document(id = 1, title = "Test", created = dateString, modified = dateString, added = dateString, tags = emptyList())
        )
        coEvery { documentRepository.getRecentDocuments(any()) } returns Result.success(documents)

        val viewModel = createViewModel()
        runCurrent()

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state.recentDocuments[0].timeAgo.contains("Min."))
        }
    }

    @Test
    fun `recent document shows time ago for hours`() = runTest {
        val fiveHoursAgo = LocalDateTime.now().minusHours(5)
        val dateString = fiveHoursAgo.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))

        val documents = listOf(
            Document(id = 1, title = "Test", created = dateString, modified = dateString, added = dateString, tags = emptyList())
        )
        coEvery { documentRepository.getRecentDocuments(any()) } returns Result.success(documents)

        val viewModel = createViewModel()
        runCurrent()

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state.recentDocuments[0].timeAgo.contains("Std."))
        }
    }

    @Test
    fun `recent document shows time ago for days`() = runTest {
        val threeDaysAgo = LocalDateTime.now().minusDays(3)
        val dateString = threeDaysAgo.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))

        val documents = listOf(
            Document(id = 1, title = "Test", created = dateString, modified = dateString, added = dateString, tags = emptyList())
        )
        coEvery { documentRepository.getRecentDocuments(any()) } returns Result.success(documents)

        val viewModel = createViewModel()
        runCurrent()

        viewModel.uiState.test {
            val state = awaitItem()
            assertTrue(state.recentDocuments[0].timeAgo.contains("Tag"))
        }
    }

    // ==================== Color Parsing Tests ====================

    @Test
    fun `tag color is parsed correctly from hex string`() = runTest {
        val now = LocalDateTime.now()
        val dateString = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))

        val tags = listOf(Tag(id = 1, name = "Red Tag", color = "#FF0000"))
        val documents = listOf(
            Document(id = 1, title = "Test", created = dateString, modified = dateString, added = dateString, tags = listOf(1))
        )

        coEvery { tagRepository.getTags() } returns Result.success(tags)
        coEvery { documentRepository.getRecentDocuments(any()) } returns Result.success(documents)

        val viewModel = createViewModel()
        runCurrent()

        viewModel.uiState.test {
            val state = awaitItem()
            // #FF0000 with alpha 0xFF becomes 0xFFFF0000
            val expectedColor = 0xFFFF0000L
            assertEquals(expectedColor, state.recentDocuments[0].tagColor)
        }
    }

    @Test
    fun `tag without color shows null tagColor`() = runTest {
        val now = LocalDateTime.now()
        val dateString = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))

        val tags = listOf(Tag(id = 1, name = "No Color Tag", color = null))
        val documents = listOf(
            Document(id = 1, title = "Test", created = dateString, modified = dateString, added = dateString, tags = listOf(1))
        )

        coEvery { tagRepository.getTags() } returns Result.success(tags)
        coEvery { documentRepository.getRecentDocuments(any()) } returns Result.success(documents)

        val viewModel = createViewModel()
        runCurrent()

        viewModel.uiState.test {
            val state = awaitItem()
            assertNull(state.recentDocuments[0].tagColor)
        }
    }

    // ==================== Initial State Tests ====================

    @Test
    fun `initial uiState has correct defaults`() = runTest {
        val viewModel = createViewModel()

        // Check initial state before loading completes
        val initialState = viewModel.uiState.value
        assertTrue(initialState.isLoading)
        assertNull(initialState.error)
    }

    // ==================== Error Handling Tests ====================

    @Test
    fun `loadDashboardData handles document repository failure gracefully`() = runTest {
        coEvery { documentRepository.getDocumentCount() } returns Result.failure(Exception("Network error"))
        coEvery { documentRepository.getRecentDocuments(any()) } returns Result.failure(Exception("Network error"))

        val viewModel = createViewModel()
        runCurrent()

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isLoading)
            assertEquals(0, state.stats.totalDocuments)
            assertTrue(state.recentDocuments.isEmpty())
        }
    }

    @Test
    fun `loadDashboardData handles task repository failure gracefully`() = runTest {
        coEvery { taskRepository.getUnacknowledgedTasks() } returns Result.failure(Exception("Error"))

        val viewModel = createViewModel()
        runCurrent()

        viewModel.uiState.test {
            val state = awaitItem()
            assertFalse(state.isLoading)
            assertTrue(state.processingTasks.isEmpty())
        }
    }

    // ==================== Related Document ID Tests ====================

    @Test
    fun `processing task parses related document id correctly`() = runTest {
        val now = LocalDateTime.now()
        val dateString = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))

        val tasks = listOf(
            PaperlessTask(
                id = 1,
                taskId = "task-1",
                taskFileName = "doc.pdf",
                dateCreated = dateString,
                type = "file",
                status = PaperlessTask.STATUS_SUCCESS,
                acknowledged = false,
                relatedDocument = "42"
            )
        )
        coEvery { taskRepository.getUnacknowledgedTasks() } returns Result.success(tasks)

        val viewModel = createViewModel()
        runCurrent()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(42, state.processingTasks[0].documentId)
        }
    }

    @Test
    fun `processing task handles null related document`() = runTest {
        val now = LocalDateTime.now()
        val dateString = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))

        val tasks = listOf(
            PaperlessTask(
                id = 1,
                taskId = "task-1",
                taskFileName = "doc.pdf",
                dateCreated = dateString,
                type = "file",
                status = PaperlessTask.STATUS_PENDING,
                acknowledged = false,
                relatedDocument = null
            )
        )
        coEvery { taskRepository.getUnacknowledgedTasks() } returns Result.success(tasks)

        val viewModel = createViewModel()
        runCurrent()

        viewModel.uiState.test {
            val state = awaitItem()
            assertNull(state.processingTasks[0].documentId)
        }
    }

    // ==================== Task Sorting Tests ====================

    @Test
    fun `processing tasks are sorted by id descending`() = runTest {
        val now = LocalDateTime.now()
        val dateString = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))

        val tasks = listOf(
            PaperlessTask(id = 1, taskId = "task-1", taskFileName = "first.pdf", dateCreated = dateString, type = "file", status = PaperlessTask.STATUS_SUCCESS, acknowledged = false),
            PaperlessTask(id = 3, taskId = "task-3", taskFileName = "third.pdf", dateCreated = dateString, type = "file", status = PaperlessTask.STATUS_SUCCESS, acknowledged = false),
            PaperlessTask(id = 2, taskId = "task-2", taskFileName = "second.pdf", dateCreated = dateString, type = "file", status = PaperlessTask.STATUS_SUCCESS, acknowledged = false)
        )
        coEvery { taskRepository.getUnacknowledgedTasks() } returns Result.success(tasks)

        val viewModel = createViewModel()
        runCurrent()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(3, state.processingTasks[0].id)
            assertEquals(2, state.processingTasks[1].id)
            assertEquals(1, state.processingTasks[2].id)
        }
    }

    @Test
    fun `processing tasks are limited to 10`() = runTest {
        val now = LocalDateTime.now()
        val dateString = now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))

        val tasks = (1..15).map { id ->
            PaperlessTask(
                id = id,
                taskId = "task-$id",
                taskFileName = "doc$id.pdf",
                dateCreated = dateString,
                type = "file",
                status = PaperlessTask.STATUS_SUCCESS,
                acknowledged = false
            )
        }
        coEvery { taskRepository.getUnacknowledgedTasks() } returns Result.success(tasks)

        val viewModel = createViewModel()
        runCurrent()

        viewModel.uiState.test {
            val state = awaitItem()
            assertEquals(10, state.processingTasks.size)
            // Should have highest IDs (15-6) due to descending sort
            assertEquals(15, state.processingTasks[0].id)
            assertEquals(6, state.processingTasks[9].id)
        }
    }
}
