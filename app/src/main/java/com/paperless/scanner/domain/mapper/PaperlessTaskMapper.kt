package com.paperless.scanner.domain.mapper

import com.paperless.scanner.data.api.models.PaperlessTask as ApiPaperlessTask
import com.paperless.scanner.domain.model.PaperlessTask as DomainPaperlessTask

/**
 * Maps API PaperlessTask model to Domain PaperlessTask model
 */
fun ApiPaperlessTask.toDomain(): DomainPaperlessTask {
    return DomainPaperlessTask(
        id = id,
        taskId = taskId,
        taskFileName = taskFileName,
        dateCreated = dateCreated,
        dateDone = dateDone,
        type = type,
        status = status,
        result = result,
        acknowledged = acknowledged,
        relatedDocument = relatedDocument
    )
}

/**
 * Maps list of API PaperlessTasks to list of Domain PaperlessTasks
 */
fun List<ApiPaperlessTask>.toDomain(): List<DomainPaperlessTask> {
    return map { it.toDomain() }
}
