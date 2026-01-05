package com.paperless.scanner.data.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cached_tags")
data class CachedTag(
    @PrimaryKey val id: Int,
    val name: String,
    val color: String?,
    val match: String?,
    val matchingAlgorithm: Int?,
    val isInboxTag: Boolean,

    val lastSyncedAt: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false
)
