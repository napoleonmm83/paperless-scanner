package com.paperless.scanner.data.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "pending_changes")
data class PendingChange(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entityType: String, // "document", "tag", "correspondent", etc.
    val entityId: Int?, // null for new entities
    val changeType: String, // "update", "delete", "create"
    val changeData: String, // JSON payload
    val createdAt: Long = System.currentTimeMillis(),
    val syncAttempts: Int = 0,
    val lastError: String? = null
)
