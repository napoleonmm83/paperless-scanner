package com.paperless.scanner.data.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "sync_metadata")
data class SyncMetadata(
    @PrimaryKey val key: String, // "last_full_sync", "documents_synced_count", etc.
    val value: String,
    val updatedAt: Long = System.currentTimeMillis()
)
