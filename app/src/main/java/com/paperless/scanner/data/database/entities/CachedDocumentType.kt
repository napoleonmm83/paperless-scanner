package com.paperless.scanner.data.database.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "cached_document_types")
data class CachedDocumentType(
    @PrimaryKey val id: Int,
    val name: String,
    val match: String?,
    val matchingAlgorithm: Int?,
    val documentCount: Int?,

    val lastSyncedAt: Long = System.currentTimeMillis(),
    val isDeleted: Boolean = false
)
