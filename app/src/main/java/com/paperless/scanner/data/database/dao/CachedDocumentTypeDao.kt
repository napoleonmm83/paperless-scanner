package com.paperless.scanner.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.paperless.scanner.data.database.entities.CachedDocumentType

@Dao
interface CachedDocumentTypeDao {
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
}
