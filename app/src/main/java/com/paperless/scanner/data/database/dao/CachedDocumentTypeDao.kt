package com.paperless.scanner.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.paperless.scanner.data.database.entities.CachedDocumentType
import kotlinx.coroutines.flow.Flow

/**
 * CachedDocumentTypeDao - Room DAO for document type cache operations.
 *
 * **PURPOSE:**
 * Provides local caching of document types from Paperless-ngx server.
 * Enables offline-first document type selection and reactive UI updates.
 *
 * **REACTIVE PATTERNS:**
 * - [observeDocumentTypes] returns [Flow] for automatic UI updates
 * - Use for document type pickers, filters, and Labels screen
 *
 * **SOFT DELETE:**
 * Document types use soft delete (isDeleted=1) for offline change tracking.
 * Hard delete only during sync when removing orphaned entries.
 *
 * @see CachedDocumentType Entity representing cached document type
 * @see DocumentTypeRepository For business logic layer
 */
@Dao
interface CachedDocumentTypeDao {
    // Reactive Flow - updates automatically on any DB change
    @Query("SELECT * FROM cached_document_types WHERE isDeleted = 0 ORDER BY name ASC")
    fun observeDocumentTypes(): Flow<List<CachedDocumentType>>

    // Legacy suspend method - kept for backward compatibility
    @Query("SELECT * FROM cached_document_types WHERE isDeleted = 0 ORDER BY name ASC")
    suspend fun getAllDocumentTypes(): List<CachedDocumentType>

    @Query("SELECT * FROM cached_document_types WHERE id = :id AND isDeleted = 0")
    suspend fun getDocumentType(id: Int): CachedDocumentType?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(documentTypes: List<CachedDocumentType>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(documentType: CachedDocumentType)

    @Update
    suspend fun update(documentType: CachedDocumentType)

    @Query("UPDATE cached_document_types SET isDeleted = 1 WHERE id = :id")
    suspend fun softDelete(id: Int)

    @Query("DELETE FROM cached_document_types")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM cached_document_types WHERE isDeleted = 0")
    suspend fun getCount(): Int

    // Methods for orphan detection during sync
    @Query("SELECT id FROM cached_document_types")
    suspend fun getAllIds(): List<Int>

    @Query("DELETE FROM cached_document_types WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Int>)
}
