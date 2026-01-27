package com.paperless.scanner.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.paperless.scanner.data.database.entities.CachedDocument
import com.paperless.scanner.data.database.entities.CachedTask
import kotlinx.coroutines.flow.Flow

@Dao
interface CachedTaskDao {
    /**
     * BEST PRACTICE: Reactive Flow for automatic UI updates.
     * Observes all tasks and automatically notifies when tasks are added/updated/deleted.
     */
    @Query("SELECT * FROM cached_tasks WHERE isDeleted = 0 ORDER BY dateCreated DESC")
    fun observeTasks(): Flow<List<CachedTask>>

    /**
     * Observe unacknowledged tasks (for notifications).
     * Automatically updates when new tasks arrive or tasks are acknowledged.
     *
     * BEST PRACTICE: Use observeUnacknowledgedTasksExcludingDeleted() instead
     * to automatically filter tasks for deleted documents.
     */
    @Query("SELECT * FROM cached_tasks WHERE isDeleted = 0 AND acknowledged = 0 ORDER BY dateCreated DESC")
    fun observeUnacknowledgedTasks(): Flow<List<CachedTask>>

    /**
     * BEST PRACTICE: Reactive query with multi-table observation.
     * Filters tasks where related document is deleted (isDeleted = 1).
     *
     * CRITICAL: Uses @RawQuery with observedEntities to explicitly track
     * BOTH cached_tasks AND cached_documents tables!
     * Room will re-emit this Flow when EITHER table changes.
     *
     * This ensures immediate UI update when document is deleted, without
     * needing navigation or manual refresh.
     *
     * @param query SimpleSQLiteQuery with the filtering logic
     * @return Flow that re-emits when tasks OR documents change
     */
    @RawQuery(observedEntities = [CachedTask::class, CachedDocument::class])
    fun observeUnacknowledgedTasksWithFilter(query: SupportSQLiteQuery): Flow<List<CachedTask>>

    /**
     * Observe pending tasks (PENDING or STARTED status).
     * Useful for showing active operations in UI.
     */
    @Query("SELECT * FROM cached_tasks WHERE isDeleted = 0 AND (status = 'PENDING' OR status = 'STARTED') ORDER BY dateCreated DESC")
    fun observePendingTasks(): Flow<List<CachedTask>>

    /**
     * Get count of unacknowledged tasks (for badge UI).
     */
    @Query("SELECT COUNT(*) FROM cached_tasks WHERE isDeleted = 0 AND acknowledged = 0")
    fun observeUnacknowledgedCount(): Flow<Int>

    // Legacy suspend methods - kept for backward compatibility
    @Query("SELECT * FROM cached_tasks WHERE isDeleted = 0 ORDER BY dateCreated DESC")
    suspend fun getAllTasks(): List<CachedTask>

    @Query("SELECT * FROM cached_tasks WHERE isDeleted = 0 AND acknowledged = 0 ORDER BY dateCreated DESC")
    suspend fun getUnacknowledgedTasks(): List<CachedTask>

    @Query("SELECT * FROM cached_tasks WHERE isDeleted = 0 AND (status = 'PENDING' OR status = 'STARTED') ORDER BY dateCreated DESC")
    suspend fun getPendingTasks(): List<CachedTask>

    @Query("SELECT * FROM cached_tasks WHERE taskId = :taskId AND isDeleted = 0")
    suspend fun getTaskByTaskId(taskId: String): CachedTask?

    @Query("SELECT * FROM cached_tasks WHERE id = :id AND isDeleted = 0")
    suspend fun getTask(id: Int): CachedTask?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tasks: List<CachedTask>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: CachedTask)

    @Update
    suspend fun update(task: CachedTask)

    /**
     * Mark task as acknowledged (for dismissing notifications).
     */
    @Query("UPDATE cached_tasks SET acknowledged = 1 WHERE id IN (:ids)")
    suspend fun markAsAcknowledged(ids: List<Int>)

    @Query("UPDATE cached_tasks SET isDeleted = 1 WHERE id = :id")
    suspend fun softDelete(id: Int)

    @Query("DELETE FROM cached_tasks")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM cached_tasks WHERE isDeleted = 0")
    suspend fun getCount(): Int

    // Methods for orphan detection during sync
    @Query("SELECT id FROM cached_tasks")
    suspend fun getAllIds(): List<Int>

    @Query("DELETE FROM cached_tasks WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Int>)

    /**
     * Clean up old completed tasks (optional - for storage management).
     * Keeps tasks for 30 days after completion.
     */
    @Query("DELETE FROM cached_tasks WHERE status IN ('SUCCESS', 'FAILURE') AND dateDone IS NOT NULL AND dateDone < :cutoffDate")
    suspend fun deleteOldCompletedTasks(cutoffDate: String): Int

    /**
     * CASCADE DELETE: Acknowledge tasks for a specific document.
     * Used when document is deleted to immediately hide related tasks from UI.
     *
     * BEST PRACTICE: Cascade cleanup on document deletion ensures immediate UI update
     * without relying on Room's subquery reactivity (which is unreliable).
     *
     * @param documentId Document ID as String (relatedDocument is stored as String)
     */
    @Query("UPDATE cached_tasks SET acknowledged = 1 WHERE relatedDocument = :documentId AND acknowledged = 0")
    suspend fun acknowledgeTasksForDocument(documentId: String)

    /**
     * BEST PRACTICE: Helper function to observe unacknowledged tasks excluding deleted documents.
     * Uses @RawQuery with observedEntities to ensure reactivity on both tables.
     *
     * This is the RECOMMENDED method for HomeScreen to use.
     * Automatically updates when:
     * - Task is added/updated/acknowledged (cached_tasks changes)
     * - Document is deleted/restored (cached_documents.isDeleted changes)
     *
     * @return Flow<List<CachedTask>> - Excludes tasks for deleted documents
     */
    fun observeUnacknowledgedTasksExcludingDeleted(): Flow<List<CachedTask>> {
        val query = SimpleSQLiteQuery("""
            SELECT * FROM cached_tasks
            WHERE isDeleted = 0
            AND acknowledged = 0
            AND (
                relatedDocument IS NULL
                OR NOT EXISTS (
                    SELECT 1 FROM cached_documents
                    WHERE id = CAST(cached_tasks.relatedDocument AS INTEGER)
                    AND isDeleted = 1
                )
            )
            ORDER BY dateCreated DESC
        """.trimIndent())
        return observeUnacknowledgedTasksWithFilter(query)
    }
}
