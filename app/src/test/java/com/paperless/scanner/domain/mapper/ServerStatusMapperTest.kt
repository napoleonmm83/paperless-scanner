package com.paperless.scanner.domain.mapper

import com.paperless.scanner.data.api.models.DatabaseInfo
import com.paperless.scanner.data.api.models.MigrationStatus
import com.paperless.scanner.data.api.models.ServerStatusResponse
import com.paperless.scanner.data.api.models.StorageInfo
import com.paperless.scanner.data.api.models.TasksInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Confirms the [ServerStatusResponse.toDomain] mapper preserves every field of the
 * `/api/status/` DTO tree on the way to the domain [com.paperless.scanner.domain.model.ServerStatus]
 * model (Issue #153, PR-A). Pure JVM — no Android/Robolectric needed.
 */
class ServerStatusMapperTest {

    @Test
    fun `toDomain maps all fields including nested structures`() {
        val api = ServerStatusResponse(
            paperlessVersion = "2.6.0",
            serverOs = "Linux",
            installType = "docker",
            storage = StorageInfo(total = 1_000L, available = 250L),
            database = DatabaseInfo(
                type = "postgresql",
                url = "db:5432",
                status = "OK",
                migrationStatus = MigrationStatus(
                    latestMigration = "0042_foo",
                    unappliedMigrations = listOf("0043_bar"),
                ),
            ),
            tasks = TasksInfo(redisUrl = "redis:6379", redisStatus = "OK", celeryStatus = "OK"),
        )

        val domain = api.toDomain()

        assertEquals("2.6.0", domain.paperlessVersion)
        assertEquals("Linux", domain.serverOs)
        assertEquals("docker", domain.installType)
        assertEquals(1_000L, domain.storage?.total)
        assertEquals(250L, domain.storage?.available)
        assertEquals("postgresql", domain.database?.type)
        assertEquals("db:5432", domain.database?.url)
        assertEquals("OK", domain.database?.status)
        assertEquals("0042_foo", domain.database?.migrationStatus?.latestMigration)
        assertEquals(listOf("0043_bar"), domain.database?.migrationStatus?.unappliedMigrations)
        assertEquals("redis:6379", domain.tasks?.redisUrl)
        assertEquals("OK", domain.tasks?.redisStatus)
        assertEquals("OK", domain.tasks?.celeryStatus)
    }

    @Test
    fun `toDomain maps absent nested objects to null`() {
        val domain = ServerStatusResponse(paperlessVersion = "2.6.0").toDomain()

        assertEquals("2.6.0", domain.paperlessVersion)
        assertNull(domain.serverOs)
        assertNull(domain.installType)
        assertNull(domain.storage)
        assertNull(domain.database)
        assertNull(domain.tasks)
    }
}
