package com.paperless.scanner.data.repository

import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.models.AcknowledgeTasksRequest
import com.paperless.scanner.data.api.models.PaperlessTask
import javax.inject.Inject

class TaskRepository @Inject constructor(
    private val api: PaperlessApi
) {
    suspend fun getTasks(): Result<List<PaperlessTask>> {
        return try {
            val tasks = api.getTasks()
            Result.success(tasks)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getTask(taskId: String): Result<PaperlessTask?> {
        return try {
            val tasks = api.getTask(taskId)
            Result.success(tasks.firstOrNull())
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getPendingTasks(): Result<List<PaperlessTask>> {
        return try {
            val tasks = api.getTasks()
            val pendingTasks = tasks.filter { it.isPending }
            Result.success(pendingTasks)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getUnacknowledgedTasks(): Result<List<PaperlessTask>> {
        return try {
            val tasks = api.getTasks()
            val unacknowledged = tasks.filter { !it.acknowledged }
            Result.success(unacknowledged)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun getRecentTasks(limit: Int = 10): Result<List<PaperlessTask>> {
        return try {
            val tasks = api.getTasks()
            // Sort by date_created descending and take limit
            val recentTasks = tasks
                .sortedByDescending { it.dateCreated }
                .take(limit)
            Result.success(recentTasks)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun acknowledgeTasks(taskIds: List<Int>): Result<Unit> {
        return try {
            val request = AcknowledgeTasksRequest(tasks = taskIds)
            android.util.Log.d("TaskRepository", "Acknowledging tasks: $taskIds")
            val response = api.acknowledgeTasks(request)
            android.util.Log.d("TaskRepository", "Response code: ${response.code()}")
            if (response.isSuccessful) {
                android.util.Log.d("TaskRepository", "Tasks acknowledged successfully")
                Result.success(Unit)
            } else {
                val errorBody = response.errorBody()?.string()
                android.util.Log.e("TaskRepository", "Failed: ${response.code()} - $errorBody")
                Result.failure(Exception("Failed to acknowledge tasks: ${response.code()} - $errorBody"))
            }
        } catch (e: Exception) {
            android.util.Log.e("TaskRepository", "Exception: ${e.message}", e)
            Result.failure(e)
        }
    }
}
