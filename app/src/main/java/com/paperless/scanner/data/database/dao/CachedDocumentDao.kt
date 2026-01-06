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
    @Query("SELECT * FROM cached_documents WHERE isDeleted = 0 ORDER BY created DESC LIMIT :limit OFFSET :offset")
    fun observeDocuments(limit: Int, offset: Int): Flow<List<CachedDocument>>

    // Legacy suspend method - kept for backward compatibility
    @Query("SELECT * FROM cached_documents WHERE isDeleted = 0 ORDER BY created DESC LIMIT :limit OFFSET :offset")
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
}
