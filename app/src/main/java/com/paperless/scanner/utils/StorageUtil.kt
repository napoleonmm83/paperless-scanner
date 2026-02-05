package com.paperless.scanner.utils

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import com.paperless.scanner.R

/**
 * Utility for storage checks and management.
 */
object StorageUtil {
    /**
     * Minimum free space required for upload operations (in bytes).
     * Set to 100MB to ensure enough buffer for temp files and processing.
     */
    private const val MIN_FREE_SPACE_BYTES = 100 * 1024 * 1024L  // 100MB

    /**
     * Maximum file size supported for upload (in bytes).
     * Set to 50MB as per requirements.
     */
    private const val MAX_FILE_SIZE_BYTES = 50 * 1024 * 1024L  // 50MB

    /**
     * Checks if there is sufficient storage space available for operations.
     *
     * @param requiredSpace Additional space required (optional)
     * @return true if sufficient space is available
     */
    fun hasSufficientSpace(requiredSpace: Long = 0): Boolean {
        val cacheDir = try {
            Environment.getDataDirectory()
        } catch (e: Exception) {
            return false
        }

        return try {
            val stat = StatFs(cacheDir.path)
            val availableBytes = stat.availableBlocksLong * stat.blockSizeLong
            availableBytes >= (MIN_FREE_SPACE_BYTES + requiredSpace)
        } catch (e: Exception) {
            // If we can't determine space, assume it's okay
            // Better to try and fail than to block user
            true
        }
    }

    /**
     * Gets available storage space in bytes.
     *
     * @return Available space in bytes, or null if cannot be determined
     */
    fun getAvailableSpaceBytes(): Long? {
        return try {
            val cacheDir = Environment.getDataDirectory()
            val stat = StatFs(cacheDir.path)
            stat.availableBlocksLong * stat.blockSizeLong
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Formats bytes to human-readable string (e.g., "2.5 MB").
     *
     * @param context Android context for localized string resources
     * @param bytes Size in bytes to format
     * @return Localized formatted string
     */
    fun formatBytes(context: Context, bytes: Long): String {
        return when {
            bytes < 1024 -> context.getString(R.string.storage_format_bytes, bytes.toInt())
            bytes < 1024 * 1024 -> context.getString(R.string.storage_format_kilobytes, bytes / 1024.0)
            bytes < 1024 * 1024 * 1024 -> context.getString(R.string.storage_format_megabytes, bytes / (1024.0 * 1024.0))
            else -> context.getString(R.string.storage_format_gigabytes, bytes / (1024.0 * 1024.0 * 1024.0))
        }
    }

    /**
     * Checks if a file/URI size exceeds the maximum supported size.
     *
     * @param context Android context
     * @param uri URI of the file to check
     * @return Result with file size if valid, or error if too large
     */
    fun validateFileSize(context: Context, uri: Uri): Result<Long> {
        return try {
            val fileSize = getFileSize(context, uri)
            if (fileSize > MAX_FILE_SIZE_BYTES) {
                val errorMessage = context.getString(
                    R.string.error_file_too_large_detail,
                    formatBytes(context, fileSize),
                    formatBytes(context, MAX_FILE_SIZE_BYTES)
                )
                Result.failure(IllegalArgumentException(errorMessage))
            } else {
                Result.success(fileSize)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Gets the size of a file from its URI.
     *
     * @param context Android context
     * @param uri URI of the file
     * @return File size in bytes
     * @throws IllegalArgumentException if file size cannot be read
     */
    fun getFileSize(context: Context, uri: Uri): Long {
        return context.contentResolver.openInputStream(uri)?.use { stream ->
            stream.available().toLong()
        } ?: throw IllegalArgumentException(context.getString(R.string.error_storage_cannot_read_file_size))
    }

    /**
     * Cleans up temporary files in cache directory.
     * Call this periodically to prevent cache bloat.
     *
     * @param context Android context
     * @param maxAgeMillis Maximum age of files to keep (default: 24 hours)
     * @return Number of files deleted
     */
    fun cleanupTempFiles(context: Context, maxAgeMillis: Long = 24 * 60 * 60 * 1000L): Int {
        val cacheDir = context.cacheDir
        if (!cacheDir.exists()) return 0

        val now = System.currentTimeMillis()
        var deletedCount = 0

        cacheDir.listFiles()?.forEach { file ->
            try {
                if (file.isFile && (now - file.lastModified()) > maxAgeMillis) {
                    if (file.delete()) {
                        deletedCount++
                    }
                }
            } catch (e: Exception) {
                // Ignore errors, continue cleanup
            }
        }

        return deletedCount
    }

    /**
     * Storage check result with detailed information.
     */
    data class StorageCheckResult(
        val hasEnoughSpace: Boolean,
        val availableBytes: Long?,
        val requiredBytes: Long,
        val message: String
    )

    /**
     * Performs a comprehensive storage check before upload.
     *
     * @param context Android context
     * @param uris List of URIs to upload
     * @return StorageCheckResult with detailed information
     */
    fun checkStorageForUpload(context: Context, uris: List<Uri>): StorageCheckResult {
        // Calculate total file size
        val totalSize = uris.sumOf { uri ->
            try {
                getFileSize(context, uri)
            } catch (e: Exception) {
                0L
            }
        }

        // Check if ANY individual file exceeds the maximum size (CRITICAL BUG FIX)
        // Previously this checked totalSize which failed for batch uploads
        val oversizedFile = uris.firstOrNull { uri ->
            try {
                val fileSize = getFileSize(context, uri)
                fileSize > MAX_FILE_SIZE_BYTES
            } catch (e: Exception) {
                false
            }
        }

        // Get available space
        val availableSpace = getAvailableSpaceBytes()

        // Check if we have enough space (file size + buffer)
        val requiredSpace = totalSize + MIN_FREE_SPACE_BYTES
        val hasEnoughSpace = availableSpace?.let { it >= requiredSpace } ?: true

        val message = when {
            oversizedFile != null -> {
                val fileSize = try {
                    getFileSize(context, oversizedFile)
                } catch (e: Exception) {
                    0L
                }
                context.getString(
                    R.string.error_file_too_large_batch,
                    formatBytes(context, fileSize),
                    formatBytes(context, MAX_FILE_SIZE_BYTES)
                )
            }
            !hasEnoughSpace && availableSpace != null -> {
                context.getString(
                    R.string.error_not_enough_storage_detail,
                    formatBytes(context, availableSpace),
                    formatBytes(context, requiredSpace)
                )
            }
            else -> {
                val availableFormatted = availableSpace?.let { formatBytes(context, it) }
                    ?: context.getString(R.string.storage_unknown)
                context.getString(R.string.storage_ok, availableFormatted)
            }
        }

        return StorageCheckResult(
            hasEnoughSpace = hasEnoughSpace && oversizedFile == null,
            availableBytes = availableSpace,
            requiredBytes = requiredSpace,
            message = message
        )
    }
}
