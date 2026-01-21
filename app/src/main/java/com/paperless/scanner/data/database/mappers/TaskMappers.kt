package com.paperless.scanner.data.database.mappers

import com.paperless.scanner.data.api.models.PaperlessTask as ApiPaperlessTask
import com.paperless.scanner.data.database.entities.CachedTask
import com.paperless.scanner.domain.model.PaperlessTask as DomainPaperlessTask

/**
 * Convert API PaperlessTask to cached entity.
 * Used when fetching tasks from API and storing them in cache.
 */
fun ApiPaperlessTask.toCachedEntity(): CachedTask {
    return CachedTask(
        id = id,
        taskId = taskId,
        taskFileName = taskFileName,
        dateCreated = dateCreated,
        dateDone = dateDone,
        type = type,
        status = status,
        result = result,
        acknowledged = acknowledged,
        relatedDocument = relatedDocument,
        lastSyncedAt = System.currentTimeMillis(),
        isDeleted = false
    )
}

/**
 * Convert cached entity to domain PaperlessTask.
 * Used when reading tasks from cache for UI display.
 */
fun CachedTask.toDomain(): DomainPaperlessTask {
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
