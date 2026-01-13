package com.paperless.scanner.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.paperless.scanner.data.database.entities.CachedTag
import kotlinx.coroutines.flow.Flow

@Dao
interface CachedTagDao {
    // Reactive Flow - updates automatically on any DB change
    @Query("SELECT * FROM cached_tags WHERE isDeleted = 0 ORDER BY name ASC")
    fun observeTags(): Flow<List<CachedTag>>

    // Legacy suspend method - kept for backward compatibility
    @Query("SELECT * FROM cached_tags WHERE isDeleted = 0 ORDER BY name ASC")
    suspend fun getAllTags(): List<CachedTag>

    @Query("SELECT * FROM cached_tags WHERE id = :id AND isDeleted = 0")
    suspend fun getTag(id: Int): CachedTag?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tags: List<CachedTag>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tag: CachedTag)

    @Update
    suspend fun update(tag: CachedTag)

    @Query("UPDATE cached_tags SET isDeleted = 1 WHERE id = :id")
    suspend fun softDelete(id: Int)

    @Query("DELETE FROM cached_tags")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM cached_tags WHERE isDeleted = 0")
    suspend fun getCount(): Int

    // Methods for orphan detection during sync
    @Query("SELECT id FROM cached_tags")
    suspend fun getAllIds(): List<Int>

    @Query("DELETE FROM cached_tags WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Int>)
}
