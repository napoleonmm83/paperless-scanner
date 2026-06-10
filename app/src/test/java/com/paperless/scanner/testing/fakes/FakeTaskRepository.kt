package com.paperless.scanner.testing.fakes

import com.paperless.scanner.data.repository.TaskRepositoryContract
import com.paperless.scanner.domain.model.PaperlessTask

/**
 * Typed fake for [TaskRepositoryContract] (#202/#321): records the forceRefresh flag
 * of every [getTasks] call; configure [tasksResult] (sticky) for the return value.
 */
class FakeTaskRepository : TaskRepositoryContract {
    val getTasksCalls = mutableListOf<Boolean>()
    var tasksResult: Result<List<PaperlessTask>> = Result.success(emptyList())

    override suspend fun getTasks(forceRefresh: Boolean): Result<List<PaperlessTask>> {
        getTasksCalls += forceRefresh
        return tasksResult
    }
}
