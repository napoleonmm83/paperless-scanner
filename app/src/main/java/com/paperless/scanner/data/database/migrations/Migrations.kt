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
