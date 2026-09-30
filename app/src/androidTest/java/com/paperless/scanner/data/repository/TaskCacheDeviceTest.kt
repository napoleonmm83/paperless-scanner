package com.paperless.scanner.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.models.PaperlessTask
import com.paperless.scanner.data.api.models.TasksResponse
import com.paperless.scanner.data.database.AppDatabase
import com.paperless.scanner.data.database.mappers.toCachedEntity
import com.paperless.scanner.data.network.NetworkMonitor
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs generated Room transactions on Android using an isolated memory database. */
@RunWith(AndroidJUnit4::class)
class TaskCacheDeviceTest {
    @Test
    fun obsoleteTasksDisappearAndAcknowledgedTasksStayHiddenAfterRefresh() = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(), AppDatabase::class.java,
        ).build()
        try {
            val api = mockk<PaperlessApi>()
            val network = mockk<NetworkMonitor>()
            coEvery { network.checkOnlineStatus() } returns true
            val dao = database.cachedTaskDao()
            fun task(id: Int) = PaperlessTask(
                id = id, taskId = "device-task-$id", taskFileName = "scan-$id.pdf",
                dateCreated = "2026-09-30T10:00:00Z", type = "file",
                status = "SUCCESS", acknowledged = false,
            )
            dao.insert(task(6071).toCachedEntity())
            coEvery { api.getTasks(page = 1, pageSize = any()) } returns TasksResponse(
                count = 1, next = null, results = listOf(task(6072)),
            )
            val repository = TaskRepository(api, dao, network)
            assertTrue(repository.getTasks(forceRefresh = true).isSuccess)
            assertEquals(listOf(6072), dao.getAllTasks().map { it.id })
            dao.markAsAcknowledged(listOf(6072))
            assertTrue(repository.getTasks(forceRefresh = true).isSuccess)
            assertTrue(dao.getUnacknowledgedTasks().isEmpty())
            coEvery { api.getTasks(page = 1, pageSize = any()) } returns TasksResponse(
                count = 0, next = null, results = emptyList(),
            )
            assertTrue(repository.getTasks(forceRefresh = true).isSuccess)
            assertTrue(dao.getAllCachedTasks().isEmpty())
        } finally {
            database.close()
        }
    }
}
