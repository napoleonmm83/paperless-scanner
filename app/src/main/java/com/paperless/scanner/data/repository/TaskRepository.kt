package com.paperless.scanner.data.repository

import android.util.Log
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.api.models.AcknowledgeTasksRequest
import com.paperless.scanner.data.api.safeApiCall
import com.paperless.scanner.data.api.models.PaperlessTask as ApiPaperlessTask
import com.paperless.scanner.data.database.dao.CachedTaskDao
import com.paperless.scanner.data.database.mappers.toCachedEntity
import com.paperless.scanner.data.database.mappers.toDomain as cachedTaskToDomain
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.domain.mapper.toDomain as apiTaskToDomain
import com.paperless.scanner.domain.model.PaperlessTask
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject

class TaskRepository @Inject constructor(
    private val api: PaperlessApi,
    private val cachedTaskDao: CachedTaskDao,
    private val networkMonitor: NetworkMonitor
) {
    companion object {
        private const val TAG = "TaskRepository"
    }

    /**
     * BEST PRACTICE: Reactive Flow for automatic UI updates.
     * Observes all tasks and automatically notifies when tasks are added/updated/deleted.
     */
    fun observeTasks(): Flow<List<PaperlessTask>> {
        return cachedTaskDao.observeTasks()
            .map { cachedList -> cachedList.map { it.cachedTaskToDomain() } }
    }

    /**
     * BEST PRACTICE: Reactive Flow for unacknowledged tasks.
     * Perfect for task notification badge UI - automatically updates count.
     */
    fun observeUnacknowledgedTasks(): Flow<List<PaperlessTask>> {
        return cachedTaskDao.observeUnacknowledgedTasks()
            .map { cachedList -> cachedList.map { it.cachedTaskToDomain() } }
    }

    /**
     * Observe count of unacknowledged tasks (for notification badge).
     */
    fun observeUnacknowledgedCount(): Flow<Int> {
        return cachedTaskDao.observeUnacknowledgedCount()
    }

    /**
     * BEST PRACTICE: Reactive Flow for pending tasks (PENDING or STARTED status).
     * Useful for showing active operations in UI.
     */
    fun observePendingTasks(): Flow<List<PaperlessTask>> {
        return cachedTaskDao.observePendingTasks()
            .map { cachedList -> cachedList.map { it.cachedTaskToDomain() } }
    }

    /**
     * Get all tasks with caching and offline-first strategy.
     *
     * @param forceRefresh If true, always fetch from API and update cache.
     *                     If false, return cache if available (offline-first).
     * @return Result with list of tasks
     */
    suspend fun getTasks(forceRefresh: Boolean = false): Result<List<PaperlessTask>> {
        return try {
            // Offline-First: Try cache first unless forceRefresh
            if (!forceRefresh || !networkMonitor.checkOnlineStatus()) {
                val cachedTasks = cachedTaskDao.getAllTasks()
                if (cachedTasks.isNotEmpty()) {
                    return Result.success(cachedTasks.map { it.cachedTaskToDomain() })
                }
            }

            // Network fetch (if online and forceRefresh or cache empty)
            if (networkMonitor.checkOnlineStatus()) {
                val response = api.getTasks()
                // Update cache - triggers reactive Flow update automatically
                val cachedEntities = response.map { it.toCachedEntity() }
                cachedTaskDao.insertAll(cachedEntities)
                Result.success(response.map { it.apiTaskToDomain() })
            } else {
                // Offline, no cache
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    suspend fun getTask(taskId: String): Result<PaperlessTask?> {
        return try {
            // Try cache first
            val cachedTask = cachedTaskDao.getTaskByTaskId(taskId)
            if (cachedTask != null) {
                return Result.success(cachedTask.cachedTaskToDomain())
            }

            // Fallback to API
            if (networkMonitor.checkOnlineStatus()) {
                val response = api.getTask(taskId).firstOrNull()
                response?.let {
                    cachedTaskDao.insert(it.toCachedEntity())
                }
                Result.success(response?.apiTaskToDomain())
            } else {
                Result.success(null)
            }
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    suspend fun getPendingTasks(): Result<List<PaperlessTask>> {
        return try {
            // Try cache first (offline-first)
            val cachedTasks = cachedTaskDao.getPendingTasks()
            if (cachedTasks.isNotEmpty()) {
                return Result.success(cachedTasks.map { it.cachedTaskToDomain() })
            }

            // Fallback to API
            if (networkMonitor.checkOnlineStatus()) {
                val response = api.getTasks().filter { it.isPending }
                val cachedEntities = response.map { it.toCachedEntity() }
                cachedTaskDao.insertAll(cachedEntities)
                Result.success(response.map { it.apiTaskToDomain() })
            } else {
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    suspend fun getUnacknowledgedTasks(): Result<List<PaperlessTask>> {
        return try {
            // Try cache first (offline-first)
            val cachedTasks = cachedTaskDao.getUnacknowledgedTasks()
            if (cachedTasks.isNotEmpty()) {
                return Result.success(cachedTasks.map { it.cachedTaskToDomain() })
            }

            // Fallback to API
            if (networkMonitor.checkOnlineStatus()) {
                val response = api.getTasks().filter { !it.acknowledged }
                val cachedEntities = response.map { it.toCachedEntity() }
                cachedTaskDao.insertAll(cachedEntities)
                Result.success(response.map { it.apiTaskToDomain() })
            } else {
                Result.success(emptyList())
            }
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    suspend fun getRecentTasks(limit: Int = 10): Result<List<PaperlessTask>> = safeApiCall {
        val response = api.getTasks()
            .sortedByDescending { it.dateCreated }
            .take(limit)

        // Update cache
        val cachedEntities = response.map { it.toCachedEntity() }
        cachedTaskDao.insertAll(cachedEntities)

        response.map { it.apiTaskToDomain() }
    }

    suspend fun acknowledgeTasks(taskIds: List<Int>): Result<Unit> {
        return try {
            val request = AcknowledgeTasksRequest(tasks = taskIds)
            Log.d(TAG, "Acknowledging tasks: $taskIds")
            val response = api.acknowledgeTasks(request)
            Log.d(TAG, "Response code: ${response.code()}")

            if (response.isSuccessful) {
                Log.d(TAG, "Tasks acknowledged successfully")

                // Update cache - mark tasks as acknowledged (triggers reactive Flow update)
                cachedTaskDao.markAsAcknowledged(taskIds)

                Result.success(Unit)
            } else {
                val errorBody = response.errorBody()?.string()
                Log.e(TAG, "Failed: ${response.code()} - $errorBody")
                Result.failure(PaperlessException.fromHttpCode(response.code(), errorBody))
            }
        } catch (e: IOException) {
            Log.e(TAG, "Network error: ${e.message}", e)
            Result.failure(PaperlessException.NetworkError(e))
        } catch (e: Exception) {
            Log.e(TAG, "Exception: ${e.message}", e)
            Result.failure(PaperlessException.from(e))
        }
    }

    /**
     * Clean up old completed tasks (optional - for storage management).
     * Keeps tasks for 30 days after completion.
     *
     * @param cutoffDate ISO 8601 date string (e.g., "2025-12-01T00:00:00Z")
     * @return Number of deleted tasks
     */
    suspend fun cleanupOldCompletedTasks(cutoffDate: String): Int {
        return cachedTaskDao.deleteOldCompletedTasks(cutoffDate)
    }
}
