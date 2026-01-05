package com.paperless.scanner.data.database.mappers

import com.google.gson.Gson
import com.paperless.scanner.data.api.models.Document as ApiDocument
import com.paperless.scanner.data.database.entities.CachedDocument
import com.paperless.scanner.domain.model.Document as DomainDocument

private val gson = Gson()

fun ApiDocument.toCachedEntity(): CachedDocument {
    return CachedDocument(
        id = id,
        title = title,
        content = content,
        created = created,
        modified = modified,
        added = added,
        archiveSerialNumber = archiveSerialNumber?.toString(),
        correspondent = correspondentId,
        documentType = documentTypeId,
        storagePath = null, // Not in current API model
        tags = gson.toJson(tags), // Convert List<Int> to JSON string
        customFields = null, // Not in current API model
        isCached = true,
        lastSyncedAt = System.currentTimeMillis(),
        isDeleted = false
    )
}

fun CachedDocument.toDomain(): DomainDocument {
    val tagList = try {
        gson.fromJson(tags, Array<Int>::class.java).toList()
    } catch (e: Exception) {
        emptyList()
    }

    return DomainDocument(
        id = id,
        title = title,
        content = content,
        created = created,
        modified = modified,
        added = added,
        correspondentId = correspondent,
        documentTypeId = documentType,
        tags = tagList,
        archiveSerialNumber = archiveSerialNumber?.toIntOrNull(),
        originalFileName = null // Not stored in cache
    )
}
