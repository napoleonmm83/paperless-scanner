package com.paperless.scanner.data.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface PendingUploadDao {

    @Query("SELECT * FROM pending_uploads ORDER BY createdAt ASC")
    fun getAllPendingUploads(): Flow<List<PendingUpload>>

    @Query("SELECT * FROM pending_uploads WHERE status = :status ORDER BY createdAt ASC")
    fun getUploadsByStatus(status: UploadStatus): Flow<List<PendingUpload>>

    @Query("SELECT * FROM pending_uploads WHERE status IN ('PENDING', 'FAILED') ORDER BY createdAt ASC")
    suspend fun getPendingAndFailedUploads(): List<PendingUpload>

    @Query("SELECT * FROM pending_uploads WHERE id = :id")
    suspend fun getUploadById(id: Long): PendingUpload?

    @Query("SELECT COUNT(*) FROM pending_uploads WHERE status IN ('PENDING', 'UPLOADING')")
    fun getPendingCount(): Flow<Int>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(upload: PendingUpload): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(uploads: List<PendingUpload>): List<Long>

    @Update
    suspend fun update(upload: PendingUpload)

    @Delete
    suspend fun delete(upload: PendingUpload)

    @Query("DELETE FROM pending_uploads WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM pending_uploads WHERE status = :status")
    suspend fun deleteByStatus(status: UploadStatus)

    @Query("UPDATE pending_uploads SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: UploadStatus)

    @Query("UPDATE pending_uploads SET status = :status, errorMessage = :errorMessage, lastAttemptAt = :lastAttemptAt, retryCount = retryCount + 1 WHERE id = :id")
    suspend fun markAsFailed(id: Long, status: UploadStatus = UploadStatus.FAILED, errorMessage: String?, lastAttemptAt: Long = System.currentTimeMillis())

    @Query("UPDATE pending_uploads SET status = 'PENDING', errorMessage = NULL WHERE status = 'FAILED' AND retryCount < :maxRetries")
    suspend fun resetFailedForRetry(maxRetries: Int = 3)
}
