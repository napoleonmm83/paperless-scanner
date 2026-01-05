package com.paperless.scanner.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.paperless.scanner.data.database.entities.CachedCorrespondent

@Dao
interface CachedCorrespondentDao {
    @Query("SELECT * FROM cached_correspondents WHERE isDeleted = 0 ORDER BY name ASC")
    suspend fun getAllCorrespondents(): List<CachedCorrespondent>

    @Query("SELECT * FROM cached_correspondents WHERE id = :id AND isDeleted = 0")
    suspend fun getCorrespondent(id: Int): CachedCorrespondent?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(correspondents: List<CachedCorrespondent>)

    @Insert( onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(correspondent: CachedCorrespondent)

    @Update
    suspend fun update(correspondent: CachedCorrespondent)

    @Query("UPDATE cached_correspondents SET isDeleted = 1 WHERE id = :id")
    suspend fun softDelete(id: Int)

    @Query("DELETE FROM cached_correspondents")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM cached_correspondents WHERE isDeleted = 0")
    suspend fun getCount(): Int
}
