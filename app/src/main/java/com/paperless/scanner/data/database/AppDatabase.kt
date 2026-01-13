package com.paperless.scanner.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.paperless.scanner.data.database.dao.AiUsageDao
import com.paperless.scanner.data.database.dao.CachedCorrespondentDao
import com.paperless.scanner.data.database.dao.CachedDocumentDao
import com.paperless.scanner.data.database.dao.CachedDocumentTypeDao
import com.paperless.scanner.data.database.dao.CachedTagDao
import com.paperless.scanner.data.database.dao.PendingChangeDao
import com.paperless.scanner.data.database.dao.SyncMetadataDao
import com.paperless.scanner.data.database.entities.AiUsageLog
import com.paperless.scanner.data.database.entities.CachedCorrespondent
import com.paperless.scanner.data.database.entities.CachedDocument
import com.paperless.scanner.data.database.entities.CachedDocumentType
import com.paperless.scanner.data.database.entities.CachedTag
import com.paperless.scanner.data.database.entities.PendingChange
import com.paperless.scanner.data.database.entities.SyncMetadata

@Database(
    entities = [
        PendingUpload::class,
        CachedDocument::class,
        CachedTag::class,
        CachedCorrespondent::class,
        CachedDocumentType::class,
        PendingChange::class,
        SyncMetadata::class,
        AiUsageLog::class
    ],
    version = 4,
    exportSchema = false
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun pendingUploadDao(): PendingUploadDao
    abstract fun cachedDocumentDao(): CachedDocumentDao
    abstract fun cachedTagDao(): CachedTagDao
    abstract fun cachedCorrespondentDao(): CachedCorrespondentDao
    abstract fun cachedDocumentTypeDao(): CachedDocumentTypeDao
    abstract fun pendingChangeDao(): PendingChangeDao
    abstract fun syncMetadataDao(): SyncMetadataDao
    abstract fun aiUsageDao(): AiUsageDao

    companion object {
        const val DATABASE_NAME = "paperless_scanner_db"
    }
}
