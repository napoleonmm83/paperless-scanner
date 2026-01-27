package com.paperless.scanner.data.database.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "cached_documents",
    indices = [Index(value = ["deletedAt"])] // Index for efficient trash queries (WorkManager auto-cleanup)
)
data class CachedDocument(
    @PrimaryKey val id: Int,
    val title: String,
    val content: String?,
    val created: String,
    val modified: String,
    val added: String,
    val archiveSerialNumber: String?,
    val originalFileName: String?, // Original filename from upload
    val correspondent: Int?,
    val documentType: Int?,
    val storagePath: Int?,
    val tags: String, // JSON array of tag IDs: "[1,2,3]"
    val customFields: String?, // JSON array

    // Offline metadata
    val isCached: Boolean = true,
    val lastSyncedAt: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false, // Soft delete for sync
    val deletedAt: Long? = null // Timestamp when document was deleted (for 30-day auto-cleanup)
)
