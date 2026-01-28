package com.paperless.scanner.data.api.models

import com.google.gson.annotations.SerializedName

data class TokenResponse(
    @SerializedName("token")
    val token: String
)

data class Tag(
    @SerializedName("id")
    val id: Int,
    @SerializedName("name")
    val name: String,
    @SerializedName("color")
    val color: String? = null,
    @SerializedName("match")
    val match: String? = null,
    @SerializedName("matching_algorithm")
    val matchingAlgorithm: Int? = null,
    @SerializedName("is_inbox_tag")
    val isInboxTag: Boolean = false,
    @SerializedName("document_count")
    val documentCount: Int? = null
)

data class TagsResponse(
    @SerializedName("count")
    val count: Int,
    @SerializedName("next")
    val next: String? = null,
    @SerializedName("previous")
    val previous: String? = null,
    @SerializedName("results")
    val results: List<Tag>
)

data class CreateTagRequest(
    @SerializedName("name")
    val name: String,
    @SerializedName("color")
    val color: String? = null,
    @SerializedName("match")
    val match: String? = null,
    @SerializedName("matching_algorithm")
    val matchingAlgorithm: Int? = null
)

data class DocumentType(
    @SerializedName("id")
    val id: Int,
    @SerializedName("name")
    val name: String,
    @SerializedName("match")
    val match: String? = null,
    @SerializedName("matching_algorithm")
    val matchingAlgorithm: Int? = null,
    @SerializedName("document_count")
    val documentCount: Int? = null
)

data class DocumentTypesResponse(
    @SerializedName("count")
    val count: Int,
    @SerializedName("next")
    val next: String? = null,
    @SerializedName("previous")
    val previous: String? = null,
    @SerializedName("results")
    val results: List<DocumentType>
)

data class UploadResponse(
    @SerializedName("task_id")
    val taskId: String
)

data class Correspondent(
    @SerializedName("id")
    val id: Int,
    @SerializedName("name")
    val name: String,
    @SerializedName("match")
    val match: String? = null,
    @SerializedName("matching_algorithm")
    val matchingAlgorithm: Int? = null,
    @SerializedName("document_count")
    val documentCount: Int? = null
)

data class CorrespondentsResponse(
    @SerializedName("count")
    val count: Int,
    @SerializedName("next")
    val next: String? = null,
    @SerializedName("previous")
    val previous: String? = null,
    @SerializedName("results")
    val results: List<Correspondent>
)

// Note Models
data class NoteUser(
    @SerializedName("id")
    val id: Int,
    @SerializedName("username")
    val username: String
)

data class Note(
    @SerializedName("id")
    val id: Int,
    @SerializedName("note")
    val note: String,
    @SerializedName("created")
    val created: String,
    @SerializedName("user")
    val user: NoteUser?
)

// Permissions Models
data class PermissionSet(
    @SerializedName("users")
    val users: List<Int> = emptyList(),
    @SerializedName("groups")
    val groups: List<Int> = emptyList()
)

data class Permissions(
    @SerializedName("view")
    val view: PermissionSet = PermissionSet(),
    @SerializedName("change")
    val change: PermissionSet = PermissionSet()
)

// Audit Log Models
data class AuditLogEntry(
    @SerializedName("id")
    val id: Int,
    @SerializedName("timestamp")
    val timestamp: String,
    @SerializedName("action")
    val action: String, // "create", "update", "delete"
    @SerializedName("changes")
    val changes: Map<String, Any> = emptyMap(), // Can be List<String> or complex object
    @SerializedName("remote_addr")
    val remoteAddr: String? = null,
    @SerializedName("actor")
    val actor: NoteUser? = null
)

// Document Models
data class Document(
    @SerializedName("id")
    val id: Int,
    @SerializedName("title")
    val title: String,
    @SerializedName("content")
    val content: String? = null,
    @SerializedName("created")
    val created: String,
    @SerializedName("modified")
    val modified: String,
    @SerializedName("added")
    val added: String,
    @SerializedName("correspondent")
    val correspondentId: Int? = null,
    @SerializedName("document_type")
    val documentTypeId: Int? = null,
    @SerializedName("tags")
    val tags: List<Int> = emptyList(),
    @SerializedName("archive_serial_number")
    val archiveSerialNumber: Int? = null,
    @SerializedName("original_file_name")
    val originalFileName: String? = null,
    @SerializedName("notes")
    val notes: List<Note> = emptyList(),
    @SerializedName("owner")
    val owner: Int? = null,
    @SerializedName("permissions")
    val permissions: Permissions? = null,
    @SerializedName("user_can_change")
    val userCanChange: Boolean = true,
    @SerializedName("ocr_confidence")
    val ocrConfidence: Double? = null
)

data class DocumentsResponse(
    @SerializedName("count")
    val count: Int,
    @SerializedName("next")
    val next: String? = null,
    @SerializedName("previous")
    val previous: String? = null,
    @SerializedName("results")
    val results: List<Document>
)

data class UpdateTagRequest(
    @SerializedName("name")
    val name: String,
    @SerializedName("color")
    val color: String? = null
)

// Correspondent Create/Update Requests
data class CreateCorrespondentRequest(
    @SerializedName("name")
    val name: String,
    @SerializedName("match")
    val match: String? = null,
    @SerializedName("matching_algorithm")
    val matchingAlgorithm: Int? = null
)

data class UpdateCorrespondentRequest(
    @SerializedName("name")
    val name: String
)

// Document Type Create/Update Requests
data class CreateDocumentTypeRequest(
    @SerializedName("name")
    val name: String,
    @SerializedName("match")
    val match: String? = null,
    @SerializedName("matching_algorithm")
    val matchingAlgorithm: Int? = null
)

data class UpdateDocumentTypeRequest(
    @SerializedName("name")
    val name: String
)

// Custom Field Models
data class CustomField(
    @SerializedName("id")
    val id: Int,
    @SerializedName("name")
    val name: String,
    @SerializedName("data_type")
    val dataType: String? = null  // "string", "integer", "monetary", "date", etc.
)

data class CustomFieldsResponse(
    @SerializedName("count")
    val count: Int,
    @SerializedName("next")
    val next: String? = null,
    @SerializedName("previous")
    val previous: String? = null,
    @SerializedName("results")
    val results: List<CustomField>
)

data class CreateCustomFieldRequest(
    @SerializedName("name")
    val name: String,
    @SerializedName("data_type")
    val dataType: String = "string"
)

// Task Models for tracking document processing
data class PaperlessTask(
    @SerializedName("id")
    val id: Int,
    @SerializedName("task_id")
    val taskId: String,
    @SerializedName("task_file_name")
    val taskFileName: String? = null,
    @SerializedName("date_created")
    val dateCreated: String,
    @SerializedName("date_done")
    val dateDone: String? = null,
    @SerializedName("type")
    val type: String,
    @SerializedName("status")
    val status: String,
    @SerializedName("result")
    val result: String? = null,
    @SerializedName("acknowledged")
    val acknowledged: Boolean = false,
    @SerializedName("related_document")
    val relatedDocument: String? = null
) {
    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_STARTED = "STARTED"
        const val STATUS_SUCCESS = "SUCCESS"
        const val STATUS_FAILURE = "FAILURE"
    }

    val isCompleted: Boolean
        get() = status == STATUS_SUCCESS || status == STATUS_FAILURE

    val isSuccess: Boolean
        get() = status == STATUS_SUCCESS

    val isPending: Boolean
        get() = status == STATUS_PENDING || status == STATUS_STARTED
}

// Request body for acknowledging tasks
data class AcknowledgeTasksRequest(
    @SerializedName("tasks")
    val tasks: List<Int>
)

// Request body for updating documents
data class UpdateDocumentRequest(
    @SerializedName("title")
    val title: String? = null,
    @SerializedName("tags")
    val tags: List<Int>? = null,
    @SerializedName("correspondent")
    val correspondent: Int? = null,
    @SerializedName("document_type")
    val documentType: Int? = null,
    @SerializedName("archive_serial_number")
    val archiveSerialNumber: Int? = null,
    @SerializedName("created")
    val created: String? = null
)

// Request body for creating notes
data class CreateNoteRequest(
    @SerializedName("note")
    val note: String
)

// User Models
data class User(
    @SerializedName("id")
    val id: Int,
    @SerializedName("username")
    val username: String,
    @SerializedName("email")
    val email: String? = null,
    @SerializedName("first_name")
    val firstName: String? = null,
    @SerializedName("last_name")
    val lastName: String? = null,
    @SerializedName("is_staff")
    val isStaff: Boolean = false,
    @SerializedName("is_superuser")
    val isSuperuser: Boolean = false,
    @SerializedName("is_active")
    val isActive: Boolean = true
)

data class UsersResponse(
    @SerializedName("count")
    val count: Int,
    @SerializedName("next")
    val next: String? = null,
    @SerializedName("previous")
    val previous: String? = null,
    @SerializedName("results")
    val results: List<User>
)

// Group Models
data class Group(
    @SerializedName("id")
    val id: Int,
    @SerializedName("name")
    val name: String
)

data class GroupsResponse(
    @SerializedName("count")
    val count: Int,
    @SerializedName("next")
    val next: String? = null,
    @SerializedName("previous")
    val previous: String? = null,
    @SerializedName("results")
    val results: List<Group>
)

// Request body for setting permissions
data class SetPermissionsRequest(
    @SerializedName("view")
    val view: PermissionSet = PermissionSet(),
    @SerializedName("change")
    val change: PermissionSet = PermissionSet()
)

// Extended UpdateDocumentRequest with permissions
data class UpdateDocumentWithPermissionsRequest(
    @SerializedName("title")
    val title: String? = null,
    @SerializedName("tags")
    val tags: List<Int>? = null,
    @SerializedName("correspondent")
    val correspondent: Int? = null,
    @SerializedName("document_type")
    val documentType: Int? = null,
    @SerializedName("archive_serial_number")
    val archiveSerialNumber: Int? = null,
    @SerializedName("created")
    val created: String? = null,
    @SerializedName("owner")
    val owner: Int? = null,
    @SerializedName("set_permissions")
    val setPermissions: SetPermissionsRequest? = null
)

/**
 * Response from Paperless-ngx suggestions endpoint.
 * Contains ML-based suggestions for document metadata.
 */
data class SuggestionsResponse(
    @SerializedName("correspondents")
    val correspondents: List<Int> = emptyList(),
    @SerializedName("tags")
    val tags: List<Int> = emptyList(),
    @SerializedName("document_types")
    val documentTypes: List<Int> = emptyList(),
    @SerializedName("storage_paths")
    val storagePaths: List<Int> = emptyList(),
    @SerializedName("dates")
    val dates: List<String> = emptyList()
)

/**
 * Request for bulk actions on documents in trash (Paperless-ngx v2.20+).
 * Supports restore and hard-delete operations.
 *
 * API Endpoint: POST /api/trash/
 *
 * @param documents List of document IDs to perform action on
 * @param action Action to perform: "restore" or "empty"
 *               IMPORTANT: Valid actions are ONLY "restore" and "empty" (verified against Paperless-ngx v2.20.5)
 */
data class TrashBulkActionRequest(
    @SerializedName("documents")
    val documents: List<Int>,
    @SerializedName("action")
    val action: String  // "restore" or "empty"
)

/**
 * Response from trash endpoints (DocumentsResponse is reused).
 * GET /api/trash/ returns paginated list of deleted documents.
 */
// Note: TrashResponse uses existing DocumentsResponse model

/**
 * Response from /api/status/ endpoint.
 * Provides server version and system information.
 * Requires admin permissions.
 */
data class ServerStatusResponse(
    @SerializedName("paperless_version")
    val paperlessVersion: String? = null,
    @SerializedName("server_os")
    val serverOs: String? = null,
    @SerializedName("install_type")
    val installType: String? = null,
    @SerializedName("storage")
    val storage: StorageInfo? = null,
    @SerializedName("database")
    val database: DatabaseInfo? = null,
    @SerializedName("tasks")
    val tasks: TasksInfo? = null
)

data class StorageInfo(
    @SerializedName("total")
    val total: Long? = null,
    @SerializedName("available")
    val available: Long? = null
)

data class DatabaseInfo(
    @SerializedName("type")
    val type: String? = null,
    @SerializedName("url")
    val url: String? = null,
    @SerializedName("status")
    val status: String? = null,
    @SerializedName("migration_status")
    val migrationStatus: MigrationStatus? = null
)

data class MigrationStatus(
    @SerializedName("latest_migration")
    val latestMigration: String? = null,
    @SerializedName("unapplied_migrations")
    val unappliedMigrations: List<String>? = null
)

data class TasksInfo(
    @SerializedName("redis_url")
    val redisUrl: String? = null,
    @SerializedName("redis_status")
    val redisStatus: String? = null,
    @SerializedName("celery_status")
    val celeryStatus: String? = null
)
