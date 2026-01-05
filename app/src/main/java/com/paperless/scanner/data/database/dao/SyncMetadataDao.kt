package com.paperless.scanner.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.paperless.scanner.data.database.entities.SyncMetadata

@Dao
interface SyncMetadataDao {
    @Query("SELECT * FROM sync_metadata WHERE key = :key")
    suspend fun get(key: String): SyncMetadata?

    @Query("SELECT * FROM sync_metadata")
    suspend fun getAll(): List<SyncMetadata>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(metadata: SyncMetadata)

    @Query("DELETE FROM sync_metadata WHERE key = :key")
    suspend fun delete(key: String)

    @Query("DELETE FROM sync_metadata")
    suspend fun deleteAll()
}
