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
    // Reactive Flow - updates automatically on any DB change
    @Query("SELECT * FROM cached_documents WHERE isDeleted = 0 ORDER BY added DESC LIMIT :limit OFFSET :offset")
    fun observeDocuments(limit: Int, offset: Int): Flow<List<CachedDocument>>

    /**
     * BEST PRACTICE: Reactive Flow with optional filters for search and tags.
     * Supports offline-first filtering - automatically updates UI on DB changes.
     *
     * @param searchQuery Text search in title/content/originalFileName/tag names (nullable for no search)
     * @param tagId Single tag ID filter (nullable for no tag filter)
     * @param limit Max results
     * @param offset Pagination offset
     *
     * NOTE: Tag matching pattern explanation:
     * Gson stores tags as JSON array of integers: [1,2,3]
     * We match complete numbers, not partials (e.g., 1 shouldn't match 10):
     * - '[' || tagId || ',' : matches first element [1,...]
     * - ',' || tagId || ',' : matches middle element [...,1,...]
     * - ',' || tagId || ']' : matches last element [...,1]
     * - '[' || tagId || ']' : matches single element [1]
     *
     * Tag name search: Joins with cached_tags to search in tag names as well.
     */
    @Query("""
        SELECT * FROM cached_documents
        WHERE isDeleted = 0
        AND (:searchQuery IS NULL
             OR title LIKE '%' || :searchQuery || '%' ESCAPE '\'
             OR content LIKE '%' || :searchQuery || '%' ESCAPE '\'
             OR originalFileName LIKE '%' || :searchQuery || '%' ESCAPE '\'
             OR EXISTS (
                 SELECT 1 FROM cached_tags t
                 WHERE t.name LIKE '%' || :searchQuery || '%' ESCAPE '\'
                 AND (
                     tags LIKE '[' || t.id || ',%' OR
                     tags LIKE '%,' || t.id || ',%' OR
                     tags LIKE '%,' || t.id || ']%' OR
                     tags LIKE '[' || t.id || ']'
                 )
             ))
        AND (:tagId IS NULL OR
             tags LIKE '[' || :tagId || ',%' OR
             tags LIKE '%,' || :tagId || ',%' OR
             tags LIKE '%,' || :tagId || ']%' OR
             tags LIKE '[' || :tagId || ']')
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
     * Same tag matching logic as observeDocumentsFiltered(), including tag name search.
     */
    @Query("""
        SELECT COUNT(*) FROM cached_documents
        WHERE isDeleted = 0
        AND (:searchQuery IS NULL
             OR title LIKE '%' || :searchQuery || '%' ESCAPE '\'
             OR content LIKE '%' || :searchQuery || '%' ESCAPE '\'
             OR originalFileName LIKE '%' || :searchQuery || '%' ESCAPE '\'
             OR EXISTS (
                 SELECT 1 FROM cached_tags t
                 WHERE t.name LIKE '%' || :searchQuery || '%' ESCAPE '\'
                 AND (
                     tags LIKE '[' || t.id || ',%' OR
                     tags LIKE '%,' || t.id || ',%' OR
                     tags LIKE '%,' || t.id || ']%' OR
                     tags LIKE '[' || t.id || ']'
                 )
             ))
        AND (:tagId IS NULL OR
             tags LIKE '[' || :tagId || ',%' OR
             tags LIKE '%,' || :tagId || ',%' OR
             tags LIKE '%,' || :tagId || ']%' OR
             tags LIKE '[' || :tagId || ']')
    """)
    fun getFilteredCount(searchQuery: String?, tagId: Int?): Flow<Int>

    // Legacy suspend method - kept for backward compatibility
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

    @Query("""
        SELECT * FROM cached_documents
        WHERE isDeleted = 0
        AND (title LIKE '%' || :query || '%' ESCAPE '\'
             OR content LIKE '%' || :query || '%' ESCAPE '\'
             OR originalFileName LIKE '%' || :query || '%' ESCAPE '\'
             OR EXISTS (
                 SELECT 1 FROM cached_tags t
                 WHERE t.name LIKE '%' || :query || '%' ESCAPE '\'
                 AND (
                     tags LIKE '[' || t.id || ',%' OR
                     tags LIKE '%,' || t.id || ',%' OR
                     tags LIKE '%,' || t.id || ']%' OR
                     tags LIKE '[' || t.id || ']'
                 )
             ))
    """)
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

    /**
     * BEST PRACTICE: Dynamic multi-tag filtering with @RawQuery.
     * Supports OR logic - documents matching ANY of the selected tags.
     *
     * Use this for multi-tag filtering instead of observeDocumentsFiltered().
     * Query must be built using SimpleSQLiteQuery in Repository layer.
     *
     * @param query Dynamic SQL query with bind parameters
     * @return Flow that updates automatically on DB changes
     */
    @RawQuery(observedEntities = [CachedDocument::class])
    fun observeDocumentsFilteredDynamic(query: SupportSQLiteQuery): Flow<List<CachedDocument>>

    /**
     * Get count for dynamically filtered documents.
     * Must be used with multi-tag filtering count queries.
     */
    @RawQuery(observedEntities = [CachedDocument::class])
    fun getFilteredCountDynamic(query: SupportSQLiteQuery): Flow<Int>

    /**
     * PAGING 3: PagingSource for efficient infinite scrolling with dynamic SQL.
     *
     * Automatically handles:
     * - Pagination (load pages on demand)
     * - Invalidation (reload on DB changes)
     * - Placeholder counting
     *
     * Use with Pager to create Flow<PagingData<CachedDocument>>.
     * Query must be built using SimpleSQLiteQuery in Repository layer.
     *
     * @param query Dynamic SQL query with bind parameters (LIMIT and OFFSET will be added automatically)
     * @return PagingSource that Room will implement automatically
     */
    @RawQuery(observedEntities = [CachedDocument::class])
    fun getDocumentsPagingSource(query: SupportSQLiteQuery): PagingSource<Int, CachedDocument>
}
