package com.paperless.scanner.data.database.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.room.Update
import androidx.sqlite.db.SupportSQLiteQuery
import com.paperless.scanner.data.database.entities.CachedDocument
import kotlinx.coroutines.flow.Flow

@Dao
interface CachedDocumentDao {
    // Reactive Flow for recent documents (HomeScreen) - updates automatically on any DB change
    @Query("SELECT * FROM cached_documents WHERE isDeleted = 0 ORDER BY added DESC LIMIT :limit OFFSET :offset")
    fun observeDocuments(limit: Int, offset: Int): Flow<List<CachedDocument>>

    // Suspend method for getRecentDocuments and similar "Top N" queries
    @Query("SELECT * FROM cached_documents WHERE isDeleted = 0 ORDER BY added DESC LIMIT :limit OFFSET :offset")
    suspend fun getDocuments(limit: Int, offset: Int): List<CachedDocument>

    /**
     * BEST PRACTICE: Reactive Flow for single document observation.
     * Automatically updates UI when document is modified/synced.
     * Used by DocumentDetailViewModel for live updates.
     */
    @Query("SELECT * FROM cached_documents WHERE id = :id AND isDeleted = 0")
    fun observeDocument(id: Int): Flow<CachedDocument?>

    @Query("SELECT * FROM cached_documents WHERE id = :id AND isDeleted = 0")
    suspend fun getDocument(id: Int): CachedDocument?

    @Query("SELECT * FROM cached_documents WHERE isDeleted = 0 AND (title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%' OR originalFileName LIKE '%' || :query || '%')")
    suspend fun searchDocuments(query: String): List<CachedDocument>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(documents: List<CachedDocument>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(document: CachedDocument)

    @Update
    suspend fun update(document: CachedDocument)

    /**
     * Soft-delete a document (marks as deleted with timestamp).
     * UPDATED for Trash feature: Now also sets deletedAt timestamp.
     *
     * @param id Document ID to soft-delete
     * @param deletedAt Timestamp when deleted (default = current time)
     */
    @Query("UPDATE cached_documents SET isDeleted = 1, deletedAt = :deletedAt WHERE id = :id")
    suspend fun softDelete(id: Int, deletedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM cached_documents")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM cached_documents WHERE isDeleted = 0")
    suspend fun getCount(): Int

    /**
     * Get count of documents without tags (for Smart Tagging).
     * Checks if tags field is empty array "[]" or NULL.
     */
    @Query("SELECT COUNT(*) FROM cached_documents WHERE isDeleted = 0 AND (tags IS NULL OR tags = '[]')")
    fun observeUntaggedCount(): Flow<Int>

    /**
     * Get all untagged documents (for Smart Tagging screen).
     * Ordered by most recently added first.
     */
    @Query("SELECT * FROM cached_documents WHERE isDeleted = 0 AND (tags IS NULL OR tags = '[]') ORDER BY added DESC")
    suspend fun getUntaggedDocuments(): List<CachedDocument>

    // Methods for orphan detection during sync (excludes soft-deleted/trashed docs)
    @Query("SELECT id FROM cached_documents WHERE isDeleted = 0")
    suspend fun getAllIds(): List<Int>

    @Query("DELETE FROM cached_documents WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Int>)

    // Hard delete a single document
    @Query("DELETE FROM cached_documents WHERE id = :id")
    suspend fun hardDelete(id: Int)

    // Advanced Filtering with @RawQuery for DocumentFilter support

    /**
     * Get total count of filtered documents (for DocumentsScreen header).
     * Uses buildCountQuery() which doesn't need LIMIT/OFFSET.
     *
     * @param query SupportSQLiteQuery built from DocumentFilterQueryBuilder.buildCountQuery()
     * @return Flow that emits count and updates automatically
     */
    @RawQuery(observedEntities = [CachedDocument::class])
    fun getCountWithFilter(query: SupportSQLiteQuery): Flow<Int>

    /**
     * PAGING 3: Get documents as PagingSource for infinite scroll.
     * Supports all DocumentFilter fields via @RawQuery with dynamic SQL.
     *
     * CRITICAL: Room automatically handles pagination bounds (LIMIT/OFFSET).
     * The query should NOT include LIMIT/OFFSET - Room adds them automatically!
     *
     * Use DocumentFilterQueryBuilder.buildPagingQuery() for correct SQL generation.
     *
     * @param query SupportSQLiteQuery built from DocumentFilter (NO LIMIT/OFFSET!)
     * @return PagingSource that Room uses for Paging 3 integration
     */
    @RawQuery(observedEntities = [CachedDocument::class])
    fun getDocumentsPagingSource(query: SupportSQLiteQuery): PagingSource<Int, CachedDocument>

    // ========================================
    // TRASH / SOFT DELETE METHODS
    // ========================================

    /**
     * Observe all deleted documents (for TrashScreen).
     * Returns documents where isDeleted = 1, ordered by deletion time (most recent first).
     *
     * @return Flow that emits list of deleted documents and updates automatically
     */
    @Query("SELECT * FROM cached_documents WHERE isDeleted = 1 ORDER BY deletedAt DESC")
    fun observeDeletedDocuments(): Flow<List<CachedDocument>>

    /**
     * Get paginated deleted documents (for TrashScreen with pagination).
     *
     * @param limit Max results per page
     * @param offset Pagination offset
     * @return Flow that emits paginated list of deleted documents
     */
    @Query("SELECT * FROM cached_documents WHERE isDeleted = 1 ORDER BY deletedAt DESC LIMIT :limit OFFSET :offset")
    fun observeDeletedDocuments(limit: Int, offset: Int): Flow<List<CachedDocument>>

    /**
     * Get count of documents in trash.
     *
     * @return Flow that emits count of deleted documents
     */
    @Query("SELECT COUNT(*) FROM cached_documents WHERE isDeleted = 1")
    fun observeDeletedCount(): Flow<Int>

    /**
     * Restore a document from trash (set isDeleted = 0, clear deletedAt).
     * Used when user clicks "Restore" in TrashScreen or Undo in Snackbar.
     *
     * @param id Document ID to restore
     */
    @Query("UPDATE cached_documents SET isDeleted = 0, deletedAt = NULL WHERE id = :id")
    suspend fun restoreDocument(id: Int)

    /**
     * Restore multiple documents from trash (bulk restore).
     *
     * @param ids List of document IDs to restore
     */
    @Query("UPDATE cached_documents SET isDeleted = 0, deletedAt = NULL WHERE id IN (:ids)")
    suspend fun restoreDocuments(ids: List<Int>)

    /**
     * Soft-delete multiple documents (bulk delete).
     *
     * @param ids List of document IDs to delete
     * @param deletedAt Timestamp when deleted (default = current time)
     */
    @Query("UPDATE cached_documents SET isDeleted = 1, deletedAt = :deletedAt WHERE id IN (:ids)")
    suspend fun softDeleteMultiple(ids: List<Int>, deletedAt: Long = System.currentTimeMillis())

    /**
     * Get documents deleted before a certain time (for auto-cleanup).
     * Used by WorkManager to find documents older than 30 days in trash.
     *
     * @param cutoffTime Timestamp cutoff (e.g., now - 30 days)
     * @return List of document IDs to permanently delete
     */
    @Query("SELECT id FROM cached_documents WHERE isDeleted = 1 AND deletedAt < :cutoffTime")
    suspend fun getOldDeletedDocumentIds(cutoffTime: Long): List<Int>

    /**
     * Get oldest deletion timestamp (for expiration countdown on HomeScreen).
     * Used to show "Expires in X days" on TrashCard.
     *
     * @return Flow that emits the oldest deletedAt timestamp, or null if trash is empty
     */
    @Query("SELECT MIN(deletedAt) FROM cached_documents WHERE isDeleted = 1")
    fun getOldestDeletedTimestamp(): Flow<Long?>

    /**
     * Get all soft-deleted document IDs.
     * Used for orphan cleanup after full trash sync from server.
     */
    @Query("SELECT id FROM cached_documents WHERE isDeleted = 1")
    suspend fun getDeletedIds(): List<Int>
}
