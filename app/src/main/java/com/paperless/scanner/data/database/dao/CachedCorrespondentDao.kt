package com.paperless.scanner.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.paperless.scanner.data.database.entities.CachedCorrespondent
import kotlinx.coroutines.flow.Flow

/**
 * CachedCorrespondentDao - Room DAO for correspondent cache operations.
 *
 * **PURPOSE:**
 * Provides local caching of correspondents from Paperless-ngx server.
 * Enables offline-first correspondent selection and reactive UI updates.
 *
 * **REACTIVE PATTERNS:**
 * - [observeCorrespondents] returns [Flow] for automatic UI updates
 * - Use for correspondent pickers, filters, and Labels screen
 *
 * **SOFT DELETE:**
 * Correspondents use soft delete (isDeleted=1) for offline change tracking.
 * Hard delete only during sync when removing orphaned entries.
 *
 * @see CachedCorrespondent Entity representing cached correspondent
 * @see CorrespondentRepository For business logic layer
 */
@Dao
interface CachedCorrespondentDao {
    // Reactive Flow - updates automatically on any DB change
    @Query("SELECT * FROM cached_correspondents WHERE isDeleted = 0 ORDER BY name ASC")
    fun observeCorrespondents(): Flow<List<CachedCorrespondent>>

    // Legacy suspend method - kept for backward compatibility
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

    // Methods for orphan detection during sync
    @Query("SELECT id FROM cached_correspondents")
    suspend fun getAllIds(): List<Int>

    @Query("DELETE FROM cached_correspondents WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Int>)
}
