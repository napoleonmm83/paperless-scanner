package com.paperless.scanner.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.paperless.scanner.data.database.entities.PendingChange

@Dao
interface PendingChangeDao {
    @Query("SELECT * FROM pending_changes ORDER BY createdAt ASC")
    suspend fun getAll(): List<PendingChange>

    @Query("SELECT * FROM pending_changes WHERE id = :id")
    suspend fun getById(id: Long): PendingChange?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(change: PendingChange): Long

    @Update
    suspend fun update(change: PendingChange)

    @Delete
    suspend fun delete(change: PendingChange)

    @Query("DELETE FROM pending_changes WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM pending_changes")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM pending_changes")
    suspend fun getCount(): Int
}
