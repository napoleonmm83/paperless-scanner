package com.paperless.scanner.data.repository

import android.util.Log
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.api.models.AcknowledgeTasksRequest
import com.paperless.scanner.data.api.models.PaperlessTask
import com.paperless.scanner.data.api.safeApiCall
import java.io.IOException
import javax.inject.Inject

class TaskRepository @Inject constructor(
    private val api: PaperlessApi
) {
    suspend fun getTasks(): Result<List<PaperlessTask>> = safeApiCall {
        api.getTasks()
    }

    suspend fun getTask(taskId: String): Result<PaperlessTask?> = safeApiCall {
        api.getTask(taskId).firstOrNull()
    }

    suspend fun getPendingTasks(): Result<List<PaperlessTask>> = safeApiCall {
        api.getTasks().filter { it.isPending }
    }

    suspend fun getUnacknowledgedTasks(): Result<List<PaperlessTask>> = safeApiCall {
        api.getTasks().filter { !it.acknowledged }
    }

    suspend fun getRecentTasks(limit: Int = 10): Result<List<PaperlessTask>> = safeApiCall {
        api.getTasks()
            .sortedByDescending { it.dateCreated }
            .take(limit)
    }

    suspend fun acknowledgeTasks(taskIds: List<Int>): Result<Unit> {
        return try {
            val request = AcknowledgeTasksRequest(tasks = taskIds)
            Log.d(TAG, "Acknowledging tasks: $taskIds")
            val response = api.acknowledgeTasks(request)
            Log.d(TAG, "Response code: ${response.code()}")

            if (response.isSuccessful) {
                Log.d(TAG, "Tasks acknowledged successfully")
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

    companion object {
        private const val TAG = "TaskRepository"
    }
}
