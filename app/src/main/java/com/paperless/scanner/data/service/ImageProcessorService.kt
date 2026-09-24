package com.paperless.scanner.data.service

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.paperless.scanner.R
import com.paperless.scanner.data.analytics.CrashlyticsHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Image-handling service extracted from DocumentRepository as part of issue #51 Phase 1.1.
 *
 * Contract:
 * - getImageBytesFromUri: reads URI, sample-decodes to <=16MP, JPEG-compresses with
 *   pixel-count-based quality, recycles bitmap. Throws IllegalArgumentException on null
 *   input stream (either pass), IllegalStateException on null bitmap decode.
 * - getFileFromUri: copies URI bytes to a timestamped JPG in cacheDir.
 */
@Singleton
class ImageProcessorService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val crashlyticsHelper: CrashlyticsHelper
) {
    fun getImageBytesFromUri(uri: Uri): ByteArray {
        crashlyticsHelper.logActionBreadcrumb("IMAGE_PROCESS", uri.lastPathSegment ?: "unknown")
        // Sampled to <=16MP to prevent OOM; a null stream fails fast on either pass.
        val bitmap = decodeSampledBitmap({
            context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException(context.getString(R.string.error_open_input_stream))
        }) ?: throw IllegalStateException(context.getString(R.string.error_decode_image))

        return try {
            val quality = calculateCompressionQuality(bitmap)
            ByteArrayOutputStream().use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
                outputStream.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    fun getFileFromUri(uri: Uri): File {
        val fileName = "document_${System.currentTimeMillis()}.jpg"
        val tempFile = File(context.cacheDir, fileName)

        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException(context.getString(R.string.error_open_input_stream))

        try {
            inputStream.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }
        } catch (e: Throwable) {
            tempFile.delete()
            throw e
        }

        return tempFile
    }

    private fun calculateCompressionQuality(bitmap: Bitmap): Int {
        val pixels = bitmap.width.toLong() * bitmap.height.toLong()
        return when {
            pixels > 12_000_000 -> 70  // >12MP: aggressive compression
            pixels > 8_000_000 -> 75   // >8MP: moderate compression
            pixels > 4_000_000 -> 80   // >4MP: light compression
            else -> 85                  // <=4MP: high quality
        }
    }
}
