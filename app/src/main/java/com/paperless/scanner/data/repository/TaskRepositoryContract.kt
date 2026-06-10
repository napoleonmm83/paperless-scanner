package com.paperless.scanner.data.repository

import com.paperless.scanner.domain.model.PaperlessTask

/**
 * Test-double seam for [TaskRepository] (#321): the task-refresh surface consumed by
 * UploadWorker. Default parameter values live HERE (Kotlin forbids them on overrides).
 */
interface TaskRepositoryContract {
    suspend fun getTasks(forceRefresh: Boolean = false): Result<List<PaperlessTask>>
}
