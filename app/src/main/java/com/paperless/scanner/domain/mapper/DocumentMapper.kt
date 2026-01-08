package com.paperless.scanner.domain.mapper

import com.paperless.scanner.data.api.models.AuditLogEntry as ApiAuditLogEntry
import com.paperless.scanner.data.api.models.Document as ApiDocument
import com.paperless.scanner.data.api.models.DocumentsResponse as ApiDocumentsResponse
import com.paperless.scanner.data.api.models.Note as ApiNote
import com.paperless.scanner.data.api.models.NoteUser as ApiNoteUser
import com.paperless.scanner.data.api.models.Permissions as ApiPermissions
import com.paperless.scanner.data.api.models.PermissionSet as ApiPermissionSet
import com.paperless.scanner.domain.model.AuditLogEntry as DomainAuditLogEntry
import com.paperless.scanner.domain.model.Document as DomainDocument
import com.paperless.scanner.domain.model.DocumentsResponse as DomainDocumentsResponse
import com.paperless.scanner.domain.model.Note as DomainNote
import com.paperless.scanner.domain.model.NoteUser as DomainNoteUser
import com.paperless.scanner.domain.model.Permissions as DomainPermissions
import com.paperless.scanner.domain.model.PermissionSet as DomainPermissionSet

/**
 * Maps API NoteUser model to Domain NoteUser model
 */
fun ApiNoteUser.toDomain(): DomainNoteUser {
    return DomainNoteUser(
        id = id,
        username = username
    )
}

/**
 * Maps API Note model to Domain Note model
 */
fun ApiNote.toDomain(): DomainNote {
    return DomainNote(
        id = id,
        note = note,
        created = created,
        user = user?.toDomain()
    )
}

/**
 * Maps list of API Notes to list of Domain Notes
 */
fun List<ApiNote>.toNoteDomain(): List<DomainNote> {
    return map { it.toDomain() }
}

/**
 * Maps API PermissionSet model to Domain PermissionSet model
 */
fun ApiPermissionSet.toDomain(): DomainPermissionSet {
    return DomainPermissionSet(
        users = users,
        groups = groups
    )
}

/**
 * Maps API Permissions model to Domain Permissions model
 */
fun ApiPermissions.toDomain(): DomainPermissions {
    return DomainPermissions(
        view = view.toDomain(),
        change = change.toDomain()
    )
}

/**
 * Maps API Document model to Domain Document model
 */
fun ApiDocument.toDomain(): DomainDocument {
    return DomainDocument(
        id = id,
        title = title,
        content = content,
        created = created,
        modified = modified,
        added = added,
        correspondentId = correspondentId,
        documentTypeId = documentTypeId,
        tags = tags,
        archiveSerialNumber = archiveSerialNumber,
        originalFileName = originalFileName,
        notes = notes.map { it.toDomain() },
        owner = owner,
        permissions = permissions?.toDomain(),
        userCanChange = userCanChange
    )
}

/**
 * Maps list of API Documents to list of Domain Documents
 */
fun List<ApiDocument>.toDomain(): List<DomainDocument> {
    return map { it.toDomain() }
}

/**
 * Maps API DocumentsResponse to Domain DocumentsResponse
 */
fun ApiDocumentsResponse.toDomain(): DomainDocumentsResponse {
    return DomainDocumentsResponse(
        count = count,
        next = next,
        previous = previous,
        results = results.toDomain()
    )
}

/**
 * Maps API AuditLogEntry model to Domain AuditLogEntry model
 */
fun ApiAuditLogEntry.toDomain(): DomainAuditLogEntry {
    return DomainAuditLogEntry(
        id = id,
        timestamp = timestamp, // Keep ISO format, will be formatted in UI
        action = action,
        changes = changes,
        remoteAddr = remoteAddr,
        actor = actor?.toDomain()
    )
}

/**
 * Maps list of API AuditLogEntries to list of Domain AuditLogEntries
 */
fun List<ApiAuditLogEntry>.toAuditLogDomain(): List<DomainAuditLogEntry> {
    return map { it.toDomain() }
}
