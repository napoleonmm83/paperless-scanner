package com.paperless.scanner.domain.mapper

import com.paperless.scanner.data.api.models.DatabaseInfo as ApiDatabaseInfo
import com.paperless.scanner.data.api.models.MigrationStatus as ApiMigrationStatus
import com.paperless.scanner.data.api.models.ServerStatusResponse as ApiServerStatus
import com.paperless.scanner.data.api.models.StorageInfo as ApiStorageInfo
import com.paperless.scanner.data.api.models.TasksInfo as ApiTasksInfo
import com.paperless.scanner.domain.model.ServerDatabase
import com.paperless.scanner.domain.model.ServerMigrationStatus
import com.paperless.scanner.domain.model.ServerStatus
import com.paperless.scanner.domain.model.ServerStorage
import com.paperless.scanner.domain.model.ServerTasks

// ============================================================
// DIRECT API → DOMAIN MAPPERS (Server Status — Issue #153)
// ============================================================
// Mirrors the TagMapper seam: data.api ServerStatusResponse → domain ServerStatus,
// so UI never touches the transport DTO. ServerStatusRepository is the only caller.
// ============================================================

/**
 * Maps the API [ApiServerStatus] DTO to the domain [ServerStatus] model.
 */
fun ApiServerStatus.toDomain(): ServerStatus = ServerStatus(
    paperlessVersion = paperlessVersion,
    serverOs = serverOs,
    installType = installType,
    storage = storage?.toDomain(),
    database = database?.toDomain(),
    tasks = tasks?.toDomain(),
)

fun ApiStorageInfo.toDomain(): ServerStorage = ServerStorage(
    total = total,
    available = available,
)

fun ApiDatabaseInfo.toDomain(): ServerDatabase = ServerDatabase(
    type = type,
    url = url,
    status = status,
    migrationStatus = migrationStatus?.toDomain(),
)

fun ApiMigrationStatus.toDomain(): ServerMigrationStatus = ServerMigrationStatus(
    latestMigration = latestMigration,
    unappliedMigrations = unappliedMigrations,
)

fun ApiTasksInfo.toDomain(): ServerTasks = ServerTasks(
    redisUrl = redisUrl,
    redisStatus = redisStatus,
    celeryStatus = celeryStatus,
)
