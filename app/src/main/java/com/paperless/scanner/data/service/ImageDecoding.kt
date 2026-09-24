package com.paperless.scanner.data.service

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.InputStream

/** Largest bitmap any page is decoded to: 16 MP is ~400 dpi on A4, far beyond what OCR needs. */
const val MAX_DECODE_PIXELS = 16_000_000L

/**
 * Smallest power-of-two sample size that brings [width] x [height] down to at most [maxPixels].
 * Long arithmetic prevents Int overflow for very large source images (>2GP).
 */
fun calculateInSampleSize(width: Int, height: Int, maxPixels: Long = MAX_DECODE_PIXELS): Int {
    var sample = 1
    while ((width.toLong() / sample) * (height / sample) > maxPixels) {
        sample *= 2
    }
    return sample
}

/**
 * Decodes an image in two passes (bounds first, then sampled) so a large photo never has to fit
 * in memory at full resolution (#407). [openStream] is called once per pass and decides itself how
 * a missing stream fails, so each caller keeps its own exception contract.
 *
 * Returns null when the data cannot be decoded — BitmapFactory's contract, which callers translate.
 */
fun decodeSampledBitmap(openStream: () -> InputStream, maxPixels: Long = MAX_DECODE_PIXELS): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openStream().use { BitmapFactory.decodeStream(it, null, bounds) }

    val options = BitmapFactory.Options().apply {
        inSampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxPixels)
    }
    return openStream().use { BitmapFactory.decodeStream(it, null, options) }
}
