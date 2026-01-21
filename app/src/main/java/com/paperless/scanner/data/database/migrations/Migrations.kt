package com.paperless.scanner.data.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Create cached_documents table
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS cached_documents (
                id INTEGER PRIMARY KEY NOT NULL,
                title TEXT NOT NULL,
                content TEXT,
                created TEXT NOT NULL,
                modified TEXT NOT NULL,
                added TEXT NOT NULL,
                archiveSerialNumber TEXT,
                correspondent INTEGER,
                documentType INTEGER,
                storagePath INTEGER,
                tags TEXT NOT NULL,
                customFields TEXT,
                isCached INTEGER NOT NULL,
                lastSyncedAt INTEGER NOT NULL,
                isDeleted INTEGER NOT NULL
            )
        """)

        // Create index on isDeleted for faster queries
        database.execSQL("""
            CREATE INDEX IF NOT EXISTS index_cached_documents_isDeleted
            ON cached_documents(isDeleted)
        """)

        // Create cached_tags table
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS cached_tags (
                id INTEGER PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                color TEXT,
                match TEXT,
                matchingAlgorithm INTEGER,
                isInboxTag INTEGER NOT NULL,
                lastSyncedAt INTEGER NOT NULL,
                isDeleted INTEGER NOT NULL
            )
        """)

        // Create index on isDeleted for faster queries
        database.execSQL("""
            CREATE INDEX IF NOT EXISTS index_cached_tags_isDeleted
            ON cached_tags(isDeleted)
        """)

        // Create cached_correspondents table
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS cached_correspondents (
                id INTEGER PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                match TEXT,
                matchingAlgorithm INTEGER,
                lastSyncedAt INTEGER NOT NULL,
                isDeleted INTEGER NOT NULL
            )
        """)

        // Create index on isDeleted for faster queries
        database.execSQL("""
            CREATE INDEX IF NOT EXISTS index_cached_correspondents_isDeleted
            ON cached_correspondents(isDeleted)
        """)

        // Create cached_document_types table
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS cached_document_types (
                id INTEGER PRIMARY KEY NOT NULL,
                name TEXT NOT NULL,
                match TEXT,
                matchingAlgorithm INTEGER,
                lastSyncedAt INTEGER NOT NULL,
                isDeleted INTEGER NOT NULL
            )
        """)

        // Create index on isDeleted for faster queries
        database.execSQL("""
            CREATE INDEX IF NOT EXISTS index_cached_document_types_isDeleted
            ON cached_document_types(isDeleted)
        """)

        // Create pending_changes table
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS pending_changes (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                entityType TEXT NOT NULL,
                entityId INTEGER,
                changeType TEXT NOT NULL,
                changeData TEXT NOT NULL,
                createdAt INTEGER NOT NULL,
                syncAttempts INTEGER NOT NULL,
                lastError TEXT
            )
        """)

        // Create sync_metadata table
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS sync_metadata (
                key TEXT PRIMARY KEY NOT NULL,
                value TEXT NOT NULL,
                updatedAt INTEGER NOT NULL
            )
        """)
    }
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Create ai_usage_logs table
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS ai_usage_logs (
                id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                timestamp INTEGER NOT NULL,
                featureType TEXT NOT NULL,
                inputTokens INTEGER NOT NULL,
                outputTokens INTEGER NOT NULL,
                estimatedCostUsd REAL NOT NULL,
                success INTEGER NOT NULL,
                subscriptionMonth TEXT NOT NULL,
                subscriptionType TEXT NOT NULL
            )
        """)

        // Create indices for faster queries
        database.execSQL("""
            CREATE INDEX IF NOT EXISTS index_ai_usage_logs_subscriptionMonth
            ON ai_usage_logs(subscriptionMonth)
        """)

        database.execSQL("""
            CREATE INDEX IF NOT EXISTS index_ai_usage_logs_timestamp
            ON ai_usage_logs(timestamp)
        """)

        database.execSQL("""
            CREATE INDEX IF NOT EXISTS index_ai_usage_logs_featureType
            ON ai_usage_logs(featureType)
        """)
    }
}

val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Add documentCount column to cached_tags table
        database.execSQL("""
            ALTER TABLE cached_tags ADD COLUMN documentCount INTEGER NOT NULL DEFAULT 0
        """)
    }
}

val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Drop cached_tasks if it exists (from old debug/beta builds with incorrect schema)
        // This is safe because cached_tasks is a NEW table in this release (no production data)
        database.execSQL("DROP TABLE IF EXISTS cached_tasks")

        // Create cached_tasks table for offline task access and reactive UI
        database.execSQL("""
            CREATE TABLE cached_tasks (
                id INTEGER PRIMARY KEY NOT NULL,
                taskId TEXT NOT NULL,
                taskFileName TEXT,
                dateCreated TEXT NOT NULL,
                dateDone TEXT,
                type TEXT NOT NULL,
                status TEXT NOT NULL,
                result TEXT,
                acknowledged INTEGER NOT NULL,
                relatedDocument TEXT,
                lastSyncedAt INTEGER NOT NULL,
                isDeleted INTEGER NOT NULL
            )
        """)

        // Create indices for faster queries
        database.execSQL("""
            CREATE INDEX index_cached_tasks_isDeleted
            ON cached_tasks(isDeleted)
        """)

        database.execSQL("""
            CREATE INDEX index_cached_tasks_acknowledged
            ON cached_tasks(acknowledged)
        """)

        database.execSQL("""
            CREATE INDEX index_cached_tasks_status
            ON cached_tasks(status)
        """)

        database.execSQL("""
            CREATE INDEX index_cached_tasks_taskId
            ON cached_tasks(taskId)
        """)
    }
}

val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Fix for users who already upgraded to v1.4.78 with faulty cached_tasks schema
        // from internal test builds (v1.4.77). These users are at DB v5 but have incorrect schema.
        // MIGRATION_4_5 won't run for them (already at v5), so we need this additional migration.

        // Drop and recreate cached_tasks to ensure correct schema for all users
        database.execSQL("DROP TABLE IF EXISTS cached_tasks")

        // Create cached_tasks table with correct schema
        database.execSQL("""
            CREATE TABLE cached_tasks (
                id INTEGER PRIMARY KEY NOT NULL,
                taskId TEXT NOT NULL,
                taskFileName TEXT,
                dateCreated TEXT NOT NULL,
                dateDone TEXT,
                type TEXT NOT NULL,
                status TEXT NOT NULL,
                result TEXT,
                acknowledged INTEGER NOT NULL,
                relatedDocument TEXT,
                lastSyncedAt INTEGER NOT NULL,
                isDeleted INTEGER NOT NULL
            )
        """)

        // Recreate indices
        database.execSQL("""
            CREATE INDEX index_cached_tasks_isDeleted
            ON cached_tasks(isDeleted)
        """)

        database.execSQL("""
            CREATE INDEX index_cached_tasks_acknowledged
            ON cached_tasks(acknowledged)
        """)

        database.execSQL("""
            CREATE INDEX index_cached_tasks_status
            ON cached_tasks(status)
        """)

        database.execSQL("""
            CREATE INDEX index_cached_tasks_taskId
            ON cached_tasks(taskId)
        """)
    }
}

val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // CRITICAL FIX for users stuck at v5/v6 with faulty cached_tasks
        // Root cause: CachedTask entity has NO @Index annotations, but old migrations created indices
        // Room expects NO indices, but DB has indices → schema mismatch
        // Solution: Drop indices, recreate table WITHOUT indices (matching Entity definition)

        // Drop all indices first (from old faulty migrations)
        database.execSQL("DROP INDEX IF EXISTS index_cached_tasks_isDeleted")
        database.execSQL("DROP INDEX IF EXISTS index_cached_tasks_acknowledged")
        database.execSQL("DROP INDEX IF EXISTS index_cached_tasks_status")
        database.execSQL("DROP INDEX IF EXISTS index_cached_tasks_taskId")

        // Force drop table (any old schema)
        database.execSQL("DROP TABLE IF EXISTS cached_tasks")

        // Recreate table with correct schema (matching CachedTask.kt)
        database.execSQL("""
            CREATE TABLE cached_tasks (
                id INTEGER PRIMARY KEY NOT NULL,
                taskId TEXT NOT NULL,
                taskFileName TEXT,
                dateCreated TEXT NOT NULL,
                dateDone TEXT,
                type TEXT NOT NULL,
                status TEXT NOT NULL,
                result TEXT,
                acknowledged INTEGER NOT NULL,
                relatedDocument TEXT,
                lastSyncedAt INTEGER NOT NULL,
                isDeleted INTEGER NOT NULL
            )
        """)

        // DO NOT recreate indices!
        // CachedTask entity has NO @Index annotations, so Room expects NO indices
    }
}
