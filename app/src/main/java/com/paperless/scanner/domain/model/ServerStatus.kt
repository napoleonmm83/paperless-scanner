package com.paperless.scanner.domain.model

/**
 * Domain model for the Paperless-ngx `/api/status/` response (Issue #153).
 *
 * Mirrors the shape of `data.api.models.ServerStatusResponse` but lives in the
 * domain namespace so UI ViewModels/Screens never import the transport DTO.
 * Map with `ServerStatusResponse.toDomain()` (see `domain/mapper/ServerStatusMapper`).
 */
data class ServerStatus(
    val paperlessVersion: String? = null,
    val serverOs: String? = null,
    val installType: String? = null,
    val storage: ServerStorage? = null,
    val database: ServerDatabase? = null,
    val tasks: ServerTasks? = null,
)

data class ServerStorage(
    val total: Long? = null,
    val available: Long? = null,
)

data class ServerDatabase(
    val type: String? = null,
    val url: String? = null,
    val status: String? = null,
    val migrationStatus: ServerMigrationStatus? = null,
)

data class ServerMigrationStatus(
    val latestMigration: String? = null,
    val unappliedMigrations: List<String>? = null,
)

data class ServerTasks(
    val redisUrl: String? = null,
    val redisStatus: String? = null,
    val celeryStatus: String? = null,
)
