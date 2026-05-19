package com.paperless.scanner.ui.screens.home

import android.content.Context
import com.paperless.scanner.data.repository.DocumentMetadataRepository
import com.paperless.scanner.data.repository.TaskRepository
import com.paperless.scanner.domain.model.Document
import com.paperless.scanner.domain.model.PaperlessTask
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
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

/**
 * NOTE: `runTest` automatically `advanceUntilIdle()`s at the end of each test
 * body. ProcessingTasksViewModel starts an infinite-polling loop while there
 * are active tasks, which would make `advanceUntilIdle` never return. Every
 * test that triggers polling (active tasks present) MUST call
 * `vm.resetState()` before the test body ends to cancel the polling job.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProcessingTasksViewModelTest {

    private lateinit var context: Context
    private lateinit var taskRepository: TaskRepository
    private lateinit var documentMetadataRepository: DocumentMetadataRepository

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = mockk(relaxed = true)
        taskRepository = mockk(relaxed = true)
        documentMetadataRepository = mockk(relaxed = true)

        every { taskRepository.observeUnacknowledgedTasksExcludingDeleted() } returns MutableStateFlow(emptyList())
        coEvery { taskRepository.getTasks(any()) } returns Result.success(emptyList())
        coEvery { taskRepository.acknowledgeTasks(any()) } returns Result.success(Unit)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): ProcessingTasksViewModel = ProcessingTasksViewModel(
        context = context,
        taskRepository = taskRepository,
        documentMetadataRepository = documentMetadataRepository,
    )

    private fun task(
        id: Int,
        status: String = PaperlessTask.STATUS_STARTED,
        fileName: String? = "file-$id.pdf",
        relatedDocument: String? = null,
    ) = PaperlessTask(
        id = id,
        taskId = "task-$id",
        taskFileName = fileName,
        status = status,
        dateCreated = "2026-05-17T10:00:00",
        result = null,
        relatedDocument = relatedDocument,
        type = "consume",
    )

    @Test
    fun `tasks without fileName are filtered out`() = runTest {
        val flow = MutableStateFlow(
            listOf(
                task(1, fileName = null),
                task(2, fileName = "x.pdf"),
            ),
        )
        every { taskRepository.observeUnacknowledgedTasksExcludingDeleted() } returns flow

        val vm = createViewModel()
        runCurrent()

        assertEquals(1, vm.uiState.value.tasks.size)
        assertEquals(2, vm.uiState.value.tasks.first().id)
        vm.resetState() // stop polling before runTest auto-drain
    }

    @Test
    fun `activeCount counts PENDING and STARTED tasks only`() = runTest {
        val flow = MutableStateFlow(
            listOf(
                task(1, status = PaperlessTask.STATUS_PENDING),
                task(2, status = PaperlessTask.STATUS_STARTED),
                task(3, status = PaperlessTask.STATUS_SUCCESS),
                task(4, status = PaperlessTask.STATUS_FAILURE),
            ),
        )
        every { taskRepository.observeUnacknowledgedTasksExcludingDeleted() } returns flow

        val vm = createViewModel()
        runCurrent()

        assertEquals(2, vm.uiState.value.activeCount)
        assertEquals(4, vm.uiState.value.tasks.size)
        vm.resetState()
    }

    @Test
    fun `tasks are sorted by id descending`() = runTest {
        val flow = MutableStateFlow(listOf(task(1), task(3), task(2)))
        every { taskRepository.observeUnacknowledgedTasksExcludingDeleted() } returns flow

        val vm = createViewModel()
        runCurrent()

        assertEquals(listOf(3, 2, 1), vm.uiState.value.tasks.map { it.id })
        vm.resetState()
    }

    @Test
    fun `successful transition fetches related document for sync`() = runTest {
        val flow = MutableStateFlow<List<PaperlessTask>>(listOf(task(1, status = PaperlessTask.STATUS_STARTED, relatedDocument = "42")))
        every { taskRepository.observeUnacknowledgedTasksExcludingDeleted() } returns flow
        coEvery { documentMetadataRepository.getDocument(any(), any()) } returns Result.success(
            Document(id = 42, title = "x", created = "2026-01-01", modified = "2026-01-01", added = "2026-01-01", tags = emptyList()),
        )

        val vm = createViewModel()
        runCurrent()
        coVerify(exactly = 0) { documentMetadataRepository.getDocument(42, true) }

        flow.value = listOf(task(1, status = PaperlessTask.STATUS_SUCCESS, relatedDocument = "42"))
        runCurrent()
        coVerify(exactly = 1) { documentMetadataRepository.getDocument(42, true) }
        vm.resetState()
    }

    @Test
    fun `repeated SUCCESS emissions do not re-fetch document`() = runTest {
        val successful = listOf(task(1, status = PaperlessTask.STATUS_SUCCESS, relatedDocument = "42"))
        val flow = MutableStateFlow(successful)
        every { taskRepository.observeUnacknowledgedTasksExcludingDeleted() } returns flow
        coEvery { documentMetadataRepository.getDocument(any(), any()) } returns Result.success(
            Document(id = 42, title = "x", created = "2026-01-01", modified = "2026-01-01", added = "2026-01-01", tags = emptyList()),
        )

        val vm = createViewModel()
        runCurrent()
        coVerify(exactly = 1) { documentMetadataRepository.getDocument(42, true) }

        flow.value = successful.toList()
        runCurrent()
        coVerify(exactly = 1) { documentMetadataRepository.getDocument(42, true) }
        // No polling triggered (SUCCESS-only), but resetState for symmetry.
        vm.resetState()
    }

    @Test
    fun `polling fires getTasks and pollingTick while active tasks exist`() = runTest {
        val flow = MutableStateFlow(listOf(task(1, status = PaperlessTask.STATUS_STARTED)))
        every { taskRepository.observeUnacknowledgedTasksExcludingDeleted() } returns flow

        val vm = createViewModel()
        var tickCount = 0
        backgroundScope.launch { vm.pollingTick.collect { tickCount++ } }
        runCurrent()

        advanceTimeBy(3_500)
        runCurrent()
        coVerify(atLeast = 1) { taskRepository.getTasks(forceRefresh = true) }
        assertTrue("pollingTick should have emitted at least once", tickCount >= 1)
        vm.resetState()
    }

    @Test
    fun `polling stops when no active tasks remain`() = runTest {
        val flow = MutableStateFlow(listOf(task(1, status = PaperlessTask.STATUS_SUCCESS)))
        every { taskRepository.observeUnacknowledgedTasksExcludingDeleted() } returns flow

        val vm = createViewModel()
        runCurrent()

        advanceTimeBy(5_000)
        runCurrent()
        // Exactly 1 = the init-time refresh; no polling iterations since no active tasks.
        coVerify(exactly = 1) { taskRepository.getTasks(forceRefresh = true) }
    }

    @Test
    fun `acknowledgeTask removes task optimistically and calls server`() = runTest {
        val flow = MutableStateFlow(listOf(task(1, status = PaperlessTask.STATUS_STARTED)))
        every { taskRepository.observeUnacknowledgedTasksExcludingDeleted() } returns flow

        val vm = createViewModel()
        runCurrent()
        assertEquals(1, vm.uiState.value.activeCount)

        vm.acknowledgeTask(taskId = 1)
        runCurrent()

        assertTrue(vm.uiState.value.tasks.isEmpty())
        assertEquals(0, vm.uiState.value.activeCount)
        coVerify { taskRepository.acknowledgeTasks(listOf(1)) }
        vm.resetState()
    }

    @Test
    fun `acknowledgeCompletedTasks acks only SUCCESS and FAILURE`() = runTest {
        val flow = MutableStateFlow(
            listOf(
                task(1, status = PaperlessTask.STATUS_STARTED),
                task(2, status = PaperlessTask.STATUS_SUCCESS),
                task(3, status = PaperlessTask.STATUS_FAILURE),
            ),
        )
        every { taskRepository.observeUnacknowledgedTasksExcludingDeleted() } returns flow

        val vm = createViewModel()
        runCurrent()

        vm.acknowledgeCompletedTasks()
        runCurrent()

        assertEquals(listOf(1), vm.uiState.value.tasks.map { it.id })
        coVerify { taskRepository.acknowledgeTasks(match { it.toSet() == setOf(2, 3) }) }
        vm.resetState()
    }

    @Test
    fun `acknowledgeCompletedTasks no-ops when nothing to ack`() = runTest {
        val flow = MutableStateFlow(listOf(task(1, status = PaperlessTask.STATUS_STARTED)))
        every { taskRepository.observeUnacknowledgedTasksExcludingDeleted() } returns flow

        val vm = createViewModel()
        runCurrent()

        vm.acknowledgeCompletedTasks()
        runCurrent()

        coVerify(exactly = 0) { taskRepository.acknowledgeTasks(any()) }
        vm.resetState()
    }

    @Test
    fun `toggleShowAll flips showAll`() = runTest {
        val vm = createViewModel()
        runCurrent()
        assertFalse(vm.uiState.value.showAll)
        vm.toggleShowAll()
        assertTrue(vm.uiState.value.showAll)
        vm.toggleShowAll()
        assertFalse(vm.uiState.value.showAll)
    }

    @Test
    fun `error recorded when observe Flow throws`() = runTest {
        every { taskRepository.observeUnacknowledgedTasksExcludingDeleted() } returns flow {
            throw RuntimeException("DB boom")
        }

        val vm = createViewModel()
        runCurrent()

        val err = vm.error.value
        assertNotNull(err)
        assertTrue(err is ProcessingTasksError.LoadFailed)
        assertEquals("processingTasks", (err as ProcessingTasksError.LoadFailed).source)
    }

    @Test
    fun `clearError resets error to null`() = runTest {
        every { taskRepository.observeUnacknowledgedTasksExcludingDeleted() } returns flow {
            throw RuntimeException("boom")
        }
        val vm = createViewModel()
        runCurrent()
        assertNotNull(vm.error.value)
        vm.clearError()
        assertNull(vm.error.value)
    }

    @Test
    fun `derived state hiddenCount and displayed respect DISPLAY_LIMIT`() = runTest {
        val tasks = (1..15).map { task(it, status = PaperlessTask.STATUS_STARTED) }
        val flow = MutableStateFlow(tasks)
        every { taskRepository.observeUnacknowledgedTasksExcludingDeleted() } returns flow

        val vm = createViewModel()
        runCurrent()

        assertEquals(ProcessingTasksUiState.DISPLAY_LIMIT, vm.uiState.value.displayed.size)
        assertEquals(15 - ProcessingTasksUiState.DISPLAY_LIMIT, vm.uiState.value.hiddenCount)

        vm.toggleShowAll()
        assertEquals(15, vm.uiState.value.displayed.size)
        assertEquals(0, vm.uiState.value.hiddenCount)
        vm.resetState()
    }
}
