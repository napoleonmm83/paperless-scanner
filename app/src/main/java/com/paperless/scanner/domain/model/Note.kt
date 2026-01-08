package com.paperless.scanner.domain.model

/**
 * Domain model for document Note
 */
data class NoteUser(
    val id: Int,
    val username: String
)

data class Note(
    val id: Int,
    val note: String,
    val created: String,
    val user: NoteUser?
)
