package com.paperless.scanner.data.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cached Paperless-ngx Task entity for offline access and reactive UI updates.
 *
 * Tasks represent background operations in Paperless-ngx:
 * - Document uploads
 * - OCR processing
 * - Document indexing
 * - Consumption (file processing)
 *
 * Caching tasks enables:
 * - Offline viewing of recent tasks
 * - Reactive task notifications (via Room Flow)
 * - Reduced API calls for task list
 */
@Entity(tableName = "cached_tasks")
data class CachedTask(
    @PrimaryKey val id: Int,
    val taskId: String,
    val taskFileName: String?,
    val dateCreated: String,
    val dateDone: String?,
    val type: String,
    val status: String,
    val result: String?,
    val acknowledged: Boolean,
    val relatedDocument: String?,

    val lastSyncedAt: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false
)
