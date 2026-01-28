package com.paperless.scanner.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.paperless.scanner.data.database.entities.SyncHistoryEntry
import kotlinx.coroutines.flow.Flow

/**
 * DAO for SyncHistory CRUD operations.
 *
 * Provides reactive Flows for UI observation and methods for
 * recording sync operations from Workers.
 */
@Dao
interface SyncHistoryDao {

    // ==================== Reactive Flows (for UI) ====================

    /**
     * Observe all history entries (newest first).
     * Used for full history view.
     */
    @Query("SELECT * FROM sync_history ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<SyncHistoryEntry>>

    /**
     * Observe recent history entries with limit.
     * Used for SyncCenter "Recently Completed" section.
     */
    @Query("SELECT * FROM sync_history ORDER BY createdAt DESC LIMIT :limit")
    fun observeRecent(limit: Int = 50): Flow<List<SyncHistoryEntry>>

    /**
     * Observe entries filtered by status.
     * Used for "Failed" section in SyncCenter.
     */
    @Query("SELECT * FROM sync_history WHERE status = :status ORDER BY createdAt DESC")
    fun observeByStatus(status: String): Flow<List<SyncHistoryEntry>>

    /**
     * Observe successful entries only.
     * Convenience method for SyncCenter "Completed" section.
     */
    @Query("SELECT * FROM sync_history WHERE status = 'success' ORDER BY createdAt DESC LIMIT :limit")
    fun observeSuccessful(limit: Int = 50): Flow<List<SyncHistoryEntry>>

    /**
     * Observe failed entries only.
     * Convenience method for SyncCenter "Failed" section.
     */
    @Query("SELECT * FROM sync_history WHERE status = 'failed' ORDER BY createdAt DESC")
    fun observeFailed(): Flow<List<SyncHistoryEntry>>

    /**
     * Observe total count of history entries.
     */
    @Query("SELECT COUNT(*) FROM sync_history")
    fun observeCount(): Flow<Int>

    /**
     * Observe count of failed entries.
     * Used for badge display.
     */
    @Query("SELECT COUNT(*) FROM sync_history WHERE status = 'failed'")
    fun observeFailedCount(): Flow<Int>

    // ==================== Suspend Methods (for Workers) ====================

    /**
     * Insert a new history entry.
     * Called by Workers after completing an operation.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: SyncHistoryEntry): Long

    /**
     * Get entry by ID.
     */
    @Query("SELECT * FROM sync_history WHERE id = :id")
    suspend fun getById(id: Long): SyncHistoryEntry?

    /**
     * Get all entries (non-reactive).
     */
    @Query("SELECT * FROM sync_history ORDER BY createdAt DESC")
    suspend fun getAll(): List<SyncHistoryEntry>

    /**
     * Get recent entries with limit (non-reactive).
     */
    @Query("SELECT * FROM sync_history ORDER BY createdAt DESC LIMIT :limit")
    suspend fun getRecent(limit: Int = 50): List<SyncHistoryEntry>

    /**
     * Delete entry by ID.
     */
    @Query("DELETE FROM sync_history WHERE id = :id")
    suspend fun deleteById(id: Long)

    /**
     * Delete entries older than timestamp.
     * Used for cleanup (30-day retention).
     */
    @Query("DELETE FROM sync_history WHERE createdAt < :timestamp")
    suspend fun deleteOlderThan(timestamp: Long)

    /**
     * Delete all failed entries.
     * Used for "Clear Failed" action.
     */
    @Query("DELETE FROM sync_history WHERE status = 'failed'")
    suspend fun deleteAllFailed()

    /**
     * Delete all entries.
     * Used for "Clear History" action.
     */
    @Query("DELETE FROM sync_history")
    suspend fun deleteAll()

    /**
     * Get count of entries (non-reactive).
     */
    @Query("SELECT COUNT(*) FROM sync_history")
    suspend fun getCount(): Int

    /**
     * Get entries for a specific day.
     * Used for grouping by date in UI.
     */
    @Query("""
        SELECT * FROM sync_history
        WHERE createdAt >= :dayStart AND createdAt < :dayEnd
        ORDER BY createdAt DESC
    """)
    suspend fun getEntriesForDay(dayStart: Long, dayEnd: Long): List<SyncHistoryEntry>
}
