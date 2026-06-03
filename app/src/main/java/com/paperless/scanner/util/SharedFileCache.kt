package com.paperless.scanner.util

import java.io.File

/**
 * Centralizes the purpose-scoped cache subdirectories that are exposed via the
 * app's FileProvider (`res/xml/file_paths.xml`).
 *
 * SECURITY (#241): the FileProvider must NOT expose the entire cache root. Only
 * files that legitimately need a shareable `content://` URI are written into
 * these narrow subdirectories, so a stray or mistaken `getUriForFile()` call can
 * never grant another app read access to unrelated cached content (OkHttp HTTP
 * cache, Coil image cache, transient processing artifacts, etc.).
 *
 * The directory names here MUST stay in sync with the `<cache-path>` entries in
 * `res/xml/file_paths.xml`.
 */
object SharedFileCache {

    /** Subdirectory for downloaded/generated PDFs shared from the PDF viewer. */
    const val SHARED_PDFS_DIR = "shared_pdfs"

    /** Subdirectory for cropped/rotated scan images handed to the upload flow. */
    const val SHARED_IMAGES_DIR = "shared_images"

    /**
     * All FileProvider-exposed subdirectory names. Must match the `<cache-path>`
     * entries in `res/xml/file_paths.xml` (asserted by FileProviderScopingTest).
     */
    val sharedDirNames: List<String> = listOf(SHARED_PDFS_DIR, SHARED_IMAGES_DIR)

    /**
     * Returns the shared-PDFs cache subdirectory, creating it if necessary.
     *
     * @param cacheDir the app cache root (`context.cacheDir`).
     */
    fun sharedPdfsDir(cacheDir: File): File = File(cacheDir, SHARED_PDFS_DIR).apply { mkdirs() }

    /**
     * Returns the shared-images cache subdirectory, creating it if necessary.
     *
     * @param cacheDir the app cache root (`context.cacheDir`).
     */
    fun sharedImagesDir(cacheDir: File): File = File(cacheDir, SHARED_IMAGES_DIR).apply { mkdirs() }
}
