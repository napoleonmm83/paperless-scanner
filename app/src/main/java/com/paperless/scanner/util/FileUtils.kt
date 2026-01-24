package com.paperless.scanner.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.core.net.toUri
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * Utility functions for file operations.
 *
 * Key use case: Content URIs from SAF (Storage Access Framework) file pickers lose their
 * permissions when passed to WorkManager/background processes. This utility copies files
 * to app-local storage where they can be safely accessed later.
 */
object FileUtils {

    private const val TAG = "FileUtils"
    private const val UPLOAD_PERSISTENT_DIR = "pending_uploads" // Changed from cache to persistent storage

    /**
     * Copies a content URI to app-local cache storage.
     *
     * This is essential for files selected via:
     * - ActivityResultContracts.OpenMultipleDocuments() (file picker)
     * - ActivityResultContracts.PickMultipleVisualMedia() (photo picker)
     *
     * The returned file URI can be safely accessed by background workers.
     *
     * @param context Application context
     * @param sourceUri Content URI to copy
     * @return Local file URI, or null if copy fails
     */
    fun copyToLocalStorage(context: Context, sourceUri: Uri): Uri? {
        return try {
            // Create persistent upload directory if needed (using filesDir, not cacheDir)
            val persistentDir = File(context.filesDir, UPLOAD_PERSISTENT_DIR)
            if (!persistentDir.exists()) {
                persistentDir.mkdirs()
                Log.d(TAG, "Created persistent upload directory: ${persistentDir.absolutePath}")
            }

            // Generate unique filename with original extension
            val extension = getFileExtension(context, sourceUri)
            val uniqueName = "${UUID.randomUUID()}.$extension"
            val destFile = File(persistentDir, uniqueName)

            // Copy file content
            context.contentResolver.openInputStream(sourceUri)?.use { inputStream ->
                FileOutputStream(destFile).use { outputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: run {
                Log.e(TAG, "Failed to open input stream for: $sourceUri")
                return null
            }

            Log.d(TAG, "Copied ${sourceUri} to ${destFile.absolutePath} (${destFile.length()} bytes)")
            destFile.toUri()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy file to local storage: $sourceUri", e)
            null
        }
    }

    /**
     * Copies multiple URIs to local storage.
     *
     * @param context Application context
     * @param sourceUris List of content URIs to copy
     * @return List of local file URIs (same size as input, null entries for failed copies)
     */
    fun copyMultipleToLocalStorage(context: Context, sourceUris: List<Uri>): List<Uri?> {
        return sourceUris.map { uri ->
            copyToLocalStorage(context, uri)
        }
    }

    /**
     * Deletes a local file URI from the upload cache.
     * Call this after successful upload to clean up.
     *
     * @param uri File URI to delete (must be a file:// URI in cache directory)
     * @return true if deleted, false otherwise
     */
    fun deleteLocalCopy(uri: Uri): Boolean {
        return try {
            if (uri.scheme == "file") {
                val file = File(uri.path ?: return false)
                // Safety check: only delete files in our cache directory
                if (file.exists() && file.absolutePath.contains(UPLOAD_PERSISTENT_DIR)) {
                    val deleted = file.delete()
                    Log.d(TAG, "Deleted local copy: ${file.absolutePath}, success=$deleted")
                    deleted
                } else {
                    Log.w(TAG, "Won't delete file outside cache: ${file.absolutePath}")
                    false
                }
            } else {
                // Don't try to delete content URIs
                Log.d(TAG, "Skipping delete for non-file URI: $uri")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete local copy: $uri", e)
            false
        }
    }

    /**
     * Deletes all files in the persistent upload directory.
     * Use with caution - only call when no uploads are pending.
     */
    fun clearUploadCache(context: Context) {
        try {
            val persistentDir = File(context.filesDir, UPLOAD_PERSISTENT_DIR)
            if (persistentDir.exists()) {
                val deletedCount = persistentDir.listFiles()?.count { file ->
                    file.delete()
                } ?: 0
                Log.d(TAG, "Cleared upload directory: deleted $deletedCount files from ${persistentDir.absolutePath}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear upload directory", e)
        }
    }

    /**
     * Gets the file extension from a URI.
     */
    private fun getFileExtension(context: Context, uri: Uri): String {
        // Try MIME type first
        val mimeType = context.contentResolver.getType(uri)
        if (mimeType != null) {
            return when {
                mimeType == "application/pdf" -> "pdf"
                mimeType.startsWith("image/jpeg") -> "jpg"
                mimeType.startsWith("image/png") -> "png"
                mimeType.startsWith("image/webp") -> "webp"
                mimeType.startsWith("image/heic") -> "heic"
                mimeType.startsWith("image/") -> mimeType.substringAfter("image/")
                else -> "bin"
            }
        }

        // Fallback: try URI path
        uri.lastPathSegment?.let { segment ->
            val dotIndex = segment.lastIndexOf('.')
            if (dotIndex > 0 && dotIndex < segment.length - 1) {
                return segment.substring(dotIndex + 1).lowercase()
            }
        }

        // Default
        return "bin"
    }

    /**
     * Checks if a URI is a local file URI (file://) vs content URI (content://).
     */
    fun isLocalFileUri(uri: Uri): Boolean {
        return uri.scheme == "file"
    }

    /**
     * Checks if a file URI actually exists and is readable.
     *
     * @param uri File URI to check (must be file:// scheme)
     * @return true if file exists and is readable, false otherwise
     */
    fun fileExists(uri: Uri): Boolean {
        if (uri.scheme != "file") {
            Log.w(TAG, "fileExists() called with non-file URI: $uri")
            return false
        }

        return try {
            val file = File(uri.path ?: return false)
            val exists = file.exists() && file.canRead()
            if (!exists) {
                Log.w(TAG, "File does not exist or not readable: ${uri.path}")
            }
            exists
        } catch (e: Exception) {
            Log.e(TAG, "Error checking file existence: $uri", e)
            false
        }
    }

    /**
     * Gets file size in bytes for a URI.
     *
     * @param uri File URI (file:// scheme)
     * @return File size in bytes, or 0 if file doesn't exist
     */
    fun getFileSize(uri: Uri): Long {
        if (uri.scheme != "file") {
            return 0
        }

        return try {
            val file = File(uri.path ?: return 0)
            if (file.exists()) file.length() else 0
        } catch (e: Exception) {
            Log.e(TAG, "Error getting file size: $uri", e)
            0
        }
    }

    /**
     * Checks if a URI points to a PDF file based on MIME type or extension.
     */
    fun isPdfFile(context: Context, uri: Uri): Boolean {
        // Check MIME type first
        val mimeType = context.contentResolver.getType(uri)
        if (mimeType == "application/pdf") {
            return true
        }

        // Fallback: check file extension
        uri.lastPathSegment?.let { segment ->
            if (segment.lowercase().endsWith(".pdf")) {
                return true
            }
        }

        // For file:// URIs, check the actual path
        if (uri.scheme == "file") {
            uri.path?.let { path ->
                if (path.lowercase().endsWith(".pdf")) {
                    return true
                }
            }
        }

        return false
    }

    /**
     * Renders the first page of a PDF file as a Bitmap.
     * Used for thumbnail previews and AI analysis.
     *
     * @param context Application context
     * @param uri URI to the PDF file (file:// or content://)
     * @param maxWidth Maximum width for the rendered bitmap (default 1024)
     * @return Bitmap of the first page, or null if rendering fails
     */
    fun renderPdfFirstPage(context: Context, uri: Uri, maxWidth: Int = 1024): Bitmap? {
        return try {
            // For content URIs, we need to copy to a temp file first
            val tempFile: File?
            val fileDescriptor: ParcelFileDescriptor

            if (uri.scheme == "file") {
                val file = File(uri.path ?: return null)
                if (!file.exists()) {
                    Log.e(TAG, "PDF file does not exist: ${uri.path}")
                    return null
                }
                fileDescriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                tempFile = null
            } else {
                // Content URI - copy to temp file
                tempFile = File(context.cacheDir, "temp_pdf_${UUID.randomUUID()}.pdf")
                context.contentResolver.openInputStream(uri)?.use { inputStream ->
                    FileOutputStream(tempFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                } ?: run {
                    Log.e(TAG, "Failed to open content URI: $uri")
                    return null
                }
                fileDescriptor = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            }

            try {
                val pdfRenderer = PdfRenderer(fileDescriptor)
                if (pdfRenderer.pageCount == 0) {
                    Log.w(TAG, "PDF has no pages")
                    pdfRenderer.close()
                    return null
                }

                val page = pdfRenderer.openPage(0)

                // Calculate scale to fit maxWidth while maintaining aspect ratio
                val scale = if (page.width > maxWidth) {
                    maxWidth.toFloat() / page.width.toFloat()
                } else {
                    1f
                }

                val renderWidth = (page.width * scale).toInt()
                val renderHeight = (page.height * scale).toInt()

                val bitmap = Bitmap.createBitmap(
                    renderWidth,
                    renderHeight,
                    Bitmap.Config.ARGB_8888
                )

                // Fill with white background
                val canvas = Canvas(bitmap)
                canvas.drawColor(Color.WHITE)

                // Render the page
                page.render(
                    bitmap,
                    null,
                    null,
                    PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY
                )

                page.close()
                pdfRenderer.close()

                Log.d(TAG, "Successfully rendered PDF first page: ${renderWidth}x${renderHeight}")
                bitmap
            } finally {
                fileDescriptor.close()
                // Clean up temp file
                tempFile?.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to render PDF first page: $uri", e)
            null
        }
    }
}
