package com.paperless.scanner.domain.model

data class AuditLogEntry(
    val id: Int,
    val timestamp: String,
    val action: String, // "create", "update", "delete"
    val changes: Map<String, Any> = emptyMap(), // Can be List<String> or complex object
    val remoteAddr: String? = null,
    val actor: NoteUser? = null
)
