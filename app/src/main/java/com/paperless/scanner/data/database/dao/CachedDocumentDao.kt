package com.paperless.scanner.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.paperless.scanner.data.database.entities.CachedDocument
import kotlinx.coroutines.flow.Flow

@Dao
interface CachedDocumentDao {
    // Reactive Flow - updates automatically on any DB change
    @Query("SELECT * FROM cached_documents WHERE isDeleted = 0 ORDER BY added DESC LIMIT :limit OFFSET :offset")
    fun observeDocuments(limit: Int, offset: Int): Flow<List<CachedDocument>>

    /**
     * BEST PRACTICE: Reactive Flow with optional filters for search and tags.
     * Supports offline-first filtering - automatically updates UI on DB changes.
     *
     * @param searchQuery Text search in title/content (nullable for no search)
     * @param tagId Single tag ID filter (nullable for no tag filter)
     * @param limit Max results
     * @param offset Pagination offset
     */
    @Query("""
        SELECT * FROM cached_documents
        WHERE isDeleted = 0
        AND (:searchQuery IS NULL OR title LIKE '%' || :searchQuery || '%' OR content LIKE '%' || :searchQuery || '%')
        AND (:tagId IS NULL OR tags LIKE '%"' || :tagId || '"%')
        ORDER BY added DESC
        LIMIT :limit OFFSET :offset
    """)
    fun observeDocumentsFiltered(
        searchQuery: String?,
        tagId: Int?,
        limit: Int,
        offset: Int
    ): Flow<List<CachedDocument>>

    /**
     * Get total count of filtered documents (for pagination).
     */
    @Query("""
        SELECT COUNT(*) FROM cached_documents
        WHERE isDeleted = 0
        AND (:searchQuery IS NULL OR title LIKE '%' || :searchQuery || '%' OR content LIKE '%' || :searchQuery || '%')
        AND (:tagId IS NULL OR tags LIKE '%"' || :tagId || '"%')
    """)
    fun getFilteredCount(searchQuery: String?, tagId: Int?): Flow<Int>

    // Legacy suspend method - kept for backward compatibility
    @Query("SELECT * FROM cached_documents WHERE isDeleted = 0 ORDER BY added DESC LIMIT :limit OFFSET :offset")
    suspend fun getDocuments(limit: Int, offset: Int): List<CachedDocument>

    @Query("SELECT * FROM cached_documents WHERE id = :id AND isDeleted = 0")
    suspend fun getDocument(id: Int): CachedDocument?

    @Query("SELECT * FROM cached_documents WHERE isDeleted = 0 AND (title LIKE '%' || :query || '%' OR content LIKE '%' || :query || '%')")
    suspend fun searchDocuments(query: String): List<CachedDocument>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(documents: List<CachedDocument>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(document: CachedDocument)

    @Update
    suspend fun update(document: CachedDocument)

    @Query("UPDATE cached_documents SET isDeleted = 1 WHERE id = :id")
    suspend fun softDelete(id: Int)

    @Query("DELETE FROM cached_documents")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM cached_documents WHERE isDeleted = 0")
    suspend fun getCount(): Int

    // Methods for orphan detection during sync
    @Query("SELECT id FROM cached_documents")
    suspend fun getAllIds(): List<Int>

    @Query("DELETE FROM cached_documents WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Int>)

    // Hard delete a single document
    @Query("DELETE FROM cached_documents WHERE id = :id")
    suspend fun hardDelete(id: Int)
}
