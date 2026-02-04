package com.paperless.scanner.data.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * PendingUploadDao - Room DAO for upload queue persistence.
 *
 * **PURPOSE:**
 * Persists document uploads in a queue for background processing.
 * Enables the "queue-only" upload architecture where uploads happen
 * asynchronously via WorkManager after user returns to home screen.
 *
 * **STATUS LIFECYCLE:**
 * ```
 * PENDING → UPLOADING → (deleted on success)
 *              ↓
 *           FAILED → (retry) → PENDING
 * ```
 *
 * **REACTIVE PATTERNS:**
 * - [getAllPendingUploads] returns [Flow] for queue screen updates
 * - [getPendingCount] returns [Flow] for badge/notification display
 *
 * **RETRY LOGIC:**
 * - [getPendingAndFailedUploads] includes failed uploads under retry limit
 * - [resetFailedForRetry] resets eligible failed uploads for retry
 * - [markAsFailed] increments retry count and records error
 *
 * @see PendingUpload Entity representing queued upload
 * @see UploadQueueRepository For business logic layer
 * @see UploadWorker For background upload processing
 * @see UploadStatus For status enum values
 */
@Dao
interface PendingUploadDao {

    @Query("SELECT * FROM pending_uploads ORDER BY createdAt ASC")
    fun getAllPendingUploads(): Flow<List<PendingUpload>>

    @Query("SELECT * FROM pending_uploads WHERE status = :status ORDER BY createdAt ASC")
    fun getUploadsByStatus(status: UploadStatus): Flow<List<PendingUpload>>

    @Query("SELECT * FROM pending_uploads WHERE status = 'PENDING' OR (status = 'FAILED' AND retryCount < :maxRetries) ORDER BY createdAt ASC")
    suspend fun getPendingAndFailedUploads(maxRetries: Int = 3): List<PendingUpload>

    @Query("SELECT * FROM pending_uploads WHERE id = :id")
    suspend fun getUploadById(id: Long): PendingUpload?

    @Query("SELECT COUNT(*) FROM pending_uploads WHERE status IN ('PENDING', 'UPLOADING')")
    fun getPendingCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM pending_uploads WHERE status = 'PENDING' OR (status = 'FAILED' AND retryCount < 3)")
    suspend fun getPendingUploadCountSync(): Int

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
