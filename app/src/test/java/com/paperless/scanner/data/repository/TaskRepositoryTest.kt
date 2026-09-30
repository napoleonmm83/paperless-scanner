package com.paperless.scanner.data.repository

import androidx.test.filters.LargeTest
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.models.TasksResponse
import com.paperless.scanner.data.database.dao.CachedTaskDao
import com.paperless.scanner.data.database.mappers.toCachedEntity
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.testing.BaseRoomRepositoryTest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Response
import com.paperless.scanner.data.api.models.PaperlessTask as ApiPaperlessTask

/**
 * Repository tests for [TaskRepository], covering the API-version work in PR #404.
 *
 * `/api/tasks/` is an unpaginated array up to API v9 and a paginated page object
 * from v10 on, so [TaskRepository] now walks pages via `fetchAllPages`. That walk
 * is the part no other test reaches: `ProcessingTasksViewModelTest` and
 * `UploadWorkerTest` both go through `FakeTaskRepository`, and the pagination
 * helper is only covered in isolation.
 *
 * Uses a real in-memory Room database (see [BaseRoomRepositoryTest]) so the cache
 * writes are verified for real; only the network boundary is mocked.
 */
@LargeTest
@OptIn(ExperimentalCoroutinesApi::class)
class TaskRepositoryTest : BaseRoomRepositoryTest() {
    // AIRLOCK-OHNE-PLANWERKZEUG: Drei Schritte sichtbar vorgelegt, kalt geprueft und am 2026-09-30 mit "plan frei gegeben" freigegeben; update_plan ist nicht verfuegbar.

    private lateinit var api: PaperlessApi
    private lateinit var cachedTaskDao: CachedTaskDao
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var taskRepository: TaskRepository

    @Before
    fun setup() {
        api = mockk()
        networkMonitor = mockk(relaxed = true)
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        cachedTaskDao = database.cachedTaskDao()
        taskRepository = TaskRepository(api, cachedTaskDao, networkMonitor)
    }

    private fun task(id: Int, status: String = "SUCCESS") = ApiPaperlessTask(
        id = id,
        taskId = "task-$id",
        taskFileName = "scan-$id.pdf",
        dateCreated = "2026-08-02T10:00:0${id % 10}Z",
        type = "file",
        status = status,
        acknowledged = false,
    )

    @Test
    fun `walks every page of a paginated v10 task list`() = runTest {
        // The v10 shape only returns page 1 unless the `next` link is followed.
        // Stopping there would silently hide in-flight uploads from the
        // "In Verarbeitung" list.
        coEvery { api.getTasks(page = 1, pageSize = any()) } returns TasksResponse(
            count = 2,
            next = "https://paperless.example.com/api/tasks/?page=2",
            results = listOf(task(1)),
        )
        coEvery { api.getTasks(page = 2, pageSize = any()) } returns TasksResponse(
            count = 2,
            next = null,
            results = listOf(task(2)),
        )

        val result = taskRepository.getTasks(forceRefresh = true)

        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrThrow().size)
        // The second page must actually have been requested — pinning `page` to a
        // constant by mistake would still return 2 tasks from a stubbed mock, so
        // assert the call itself.
        coVerify(exactly = 1) { api.getTasks(page = 2, pageSize = any()) }
        assertEquals(2, cachedTaskDao.getAllTasks().size)
    }

    @Test
    fun `stops after one request on the unpaginated v9 shape`() = runTest {
        // API v9 returns a bare array, which the deserializer normalises to a
        // response with a null `next`. The walk must not ask for page 2.
        coEvery { api.getTasks(page = 1, pageSize = any()) } returns TasksResponse(
            count = 1,
            next = null,
            results = listOf(task(1)),
        )

        val result = taskRepository.getTasks(forceRefresh = true)

        assertEquals(1, result.getOrThrow().size)
        coVerify(exactly = 0) { api.getTasks(page = 2, pageSize = any()) }
    }

    @Test
    fun `surfaces a mid-walk failure instead of returning a partial list`() = runTest {
        // A partial walk reported as success would look identical to "the server
        // has fewer tasks", so the failure must propagate.
        coEvery { api.getTasks(page = 1, pageSize = any()) } returns TasksResponse(
            count = 2,
            next = "https://paperless.example.com/api/tasks/?page=2",
            results = listOf(task(1)),
        )
        coEvery { api.getTasks(page = 2, pageSize = any()) } throws IllegalStateException("boom")

        val result = taskRepository.getTasks(forceRefresh = true)

        assertTrue(result.isFailure)
    }

    @Test
    fun `reads a single task out of the response envelope`() = runTest {
        // v10 wraps the task_id lookup in a page object; v9 returns a bare array.
        // Both arrive here as TasksResponse, so the repository must read `results`.
        coEvery { api.getTask("task-7") } returns TasksResponse(
            count = 1,
            results = listOf(task(7)),
        )

        val result = taskRepository.getTask("task-7")

        assertEquals("task-7", result.getOrThrow()?.taskId)
    }

    @Test
    fun `returns null when the task is not found`() = runTest {
        coEvery { api.getTask("missing") } returns TasksResponse(count = 0, results = emptyList())

        val result = taskRepository.getTask("missing")

        assertTrue(result.isSuccess)
        assertEquals(null, result.getOrThrow())
    }

    @Test
    fun `refresh removes cached tasks missing from the complete server list`() = runTest {
        cachedTaskDao.insertAll(listOf(task(1).toCachedEntity(), task(2).toCachedEntity()))
        coEvery { api.getTasks(page = 1, pageSize = any()) } returns
            TasksResponse(count = 1, results = listOf(task(2)))

        assertTrue(taskRepository.getTasks(forceRefresh = true).isSuccess)

        assertEquals(listOf(2), cachedTaskDao.getUnacknowledgedTasks().map { it.id })
        assertEquals(null, cachedTaskDao.getTask(1))
    }

    @Test
    fun `successful empty server list clears obsolete cached tasks`() = runTest {
        cachedTaskDao.insert(task(1).toCachedEntity())
        coEvery { api.getTasks(page = 1, pageSize = any()) } returns TasksResponse()

        assertTrue(taskRepository.getTasks(forceRefresh = true).isSuccess)

        assertTrue(cachedTaskDao.getAllTasks().isEmpty())
    }

    @Test
    fun `failed complete fetch preserves cached tasks`() = runTest {
        cachedTaskDao.insert(task(1).toCachedEntity())
        coEvery { api.getTasks(page = 1, pageSize = any()) } returns TasksResponse(
            count = 2,
            next = "https://paperless.example.com/api/tasks/?page=2",
            results = listOf(task(2)),
        )
        coEvery { api.getTasks(page = 2, pageSize = any()) } throws IllegalStateException("page failed")

        assertTrue(taskRepository.getTasks(forceRefresh = true).isFailure)

        assertEquals(listOf(1), cachedTaskDao.getAllTasks().map { it.id })
    }

    @Test
    fun `refresh cannot undo an acknowledgment made while the response is in flight`() = runTest {
        cachedTaskDao.insert(task(1).toCachedEntity())
        coEvery { api.acknowledgeTasks(any()) } returns Response.success(Unit)
        coEvery { api.getTasks(page = 1, pageSize = any()) } coAnswers {
            assertTrue(taskRepository.acknowledgeTasks(listOf(1)).isSuccess)
            TasksResponse(count = 1, results = listOf(task(1)))
        }

        assertTrue(taskRepository.getTasks(forceRefresh = true).isSuccess)

        assertTrue(cachedTaskDao.getTask(1)!!.acknowledged)
        assertTrue(cachedTaskDao.getUnacknowledgedTasks().isEmpty())
    }

    @Test
    fun `refresh preserves local soft deletion for the same task`() = runTest {
        cachedTaskDao.insert(task(1).toCachedEntity())
        cachedTaskDao.softDelete(1)
        coEvery { api.getTasks(page = 1, pageSize = any()) } returns
            TasksResponse(count = 1, results = listOf(task(1)))

        assertTrue(taskRepository.getTasks(forceRefresh = true).isSuccess)

        assertTrue(cachedTaskDao.getAllTasks().isEmpty())
    }

    @Test
    fun `a reused numeric id does not inherit another task's local flags`() = runTest {
        cachedTaskDao.insert(task(1).toCachedEntity())
        cachedTaskDao.markAsAcknowledged(listOf(1))
        cachedTaskDao.softDelete(1)
        val replacement = task(1).copy(taskId = "new-task-identity")
        coEvery { api.getTasks(page = 1, pageSize = any()) } returns
            TasksResponse(count = 1, results = listOf(replacement))

        assertTrue(taskRepository.getTasks(forceRefresh = true).isSuccess)

        assertEquals("new-task-identity", cachedTaskDao.getTask(1)!!.taskId)
        assertEquals(listOf(1), cachedTaskDao.getUnacknowledgedTasks().map { it.id })
    }

    @Test
    fun `pending fetch caches the complete list before selecting pending tasks`() = runTest {
        coEvery { api.getTasks(page = 1, pageSize = any()) } returns TasksResponse(
            count = 2,
            results = listOf(task(1, "STARTED"), task(2)),
        )

        assertEquals(listOf(1), taskRepository.getPendingTasks().getOrThrow().map { it.id })
        assertEquals(setOf(1, 2), cachedTaskDao.getAllTasks().map { it.id }.toSet())
    }

    @Test
    fun `unacknowledged fetch caches acknowledged tasks too`() = runTest {
        coEvery { api.getTasks(page = 1, pageSize = any()) } returns TasksResponse(
            count = 2,
            results = listOf(task(1), task(2).copy(acknowledged = true)),
        )

        assertEquals(listOf(1), taskRepository.getUnacknowledgedTasks().getOrThrow().map { it.id })
        assertTrue(cachedTaskDao.getTask(2)!!.acknowledged)
    }

    @Test
    fun `single task fetch does not remove unrelated cached tasks`() = runTest {
        cachedTaskDao.insert(task(1).toCachedEntity())
        coEvery { api.getTask("task-2") } returns TasksResponse(count = 1, results = listOf(task(2)))

        assertEquals(2, taskRepository.getTask("task-2").getOrThrow()!!.id)
        assertEquals(setOf(1, 2), cachedTaskDao.getAllTasks().map { it.id }.toSet())
    }

    @Test
    fun `offline forced refresh preserves cached tasks`() = runTest {
        cachedTaskDao.insert(task(1).toCachedEntity())
        coEvery { networkMonitor.checkOnlineStatus() } returns false

        assertEquals(listOf(1), taskRepository.getTasks(forceRefresh = true).getOrThrow().map { it.id })
        assertEquals(listOf(1), cachedTaskDao.getAllTasks().map { it.id })
        coVerify(exactly = 0) { api.getTasks(any(), any()) }
    }

    @Test
    fun `concurrent full refreshes fetch and apply in order`() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var fetchCount = 0
        coEvery { api.getTasks(page = 1, pageSize = any()) } coAnswers {
            fetchCount++
            if (fetchCount == 1) {
                firstStarted.complete(Unit)
                releaseFirst.await()
                TasksResponse(count = 1, results = listOf(task(1)))
            } else {
                TasksResponse(count = 1, results = listOf(task(2)))
            }
        }
        val first = async { taskRepository.getTasks(forceRefresh = true) }
        firstStarted.await()
        val second = async { taskRepository.getTasks(forceRefresh = true) }
        runCurrent()
        try {
            assertEquals(1, fetchCount)
        } finally {
            releaseFirst.complete(Unit)
        }

        assertTrue(first.await().isSuccess)
        assertTrue(second.await().isSuccess)
        assertEquals(listOf(2), cachedTaskDao.getAllTasks().map { it.id })
    }
}
