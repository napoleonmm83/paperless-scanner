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

    /**
     * Result of a single [cleanupAgedUnprotected] sweep.
     *
     * @param deletedCount number of files actually deleted.
     * @param freedBytes total size (bytes) of the deleted files.
     */
    data class SweepResult(val deletedCount: Int, val freedBytes: Long)

    /**
     * Pure, side-effect-scoped cache sweep usable from a unit test.
     *
     * Deletes the regular files directly inside [dir] that are BOTH:
     *  - older than [maxAgeMillis] (i.e. `now - lastModified > maxAgeMillis`), AND
     *  - NOT in [protectedFileNames] (matched by exact [File.getName]), AND
     *  - accepted by the optional [accept] predicate (defaults to all files).
     *
     * #307: this is the draft-aware sweep for `shared_images/`. Cropped scan
     * images (`cropped_*.jpg`) written by `ScanViewModel.cropPage` become the
     * persisted `page.uri` of an in-progress scan draft (ScanViewModel
     * KEY_PAGE_URIS, survives process death). An age-only sweep could delete a
     * still-referenced page on a delayed restore, so the caller passes the set of
     * file names referenced by persisted drafts as [protectedFileNames]; those
     * are never deleted regardless of age. Upload-only `rotated_*.jpg` are never
     * persisted, so they age out normally.
     *
     * This is intentionally a pure function over the filesystem (no Android
     * dependencies, no Hilt/Firebase boot) so it is directly unit-testable —
     * `PaperlessApp.onCreate` is not.
     *
     * @param dir directory to sweep (non-recursive; only direct file children).
     * @param now current wall-clock time in millis (injected for testability).
     * @param maxAgeMillis age threshold; files at or newer than this are kept.
     * @param protectedFileNames exact file names that must never be deleted.
     * @param accept optional extra filter; only matching files are eligible.
     * @return how many files were deleted and how many bytes were freed.
     */
    fun cleanupAgedUnprotected(
        dir: File,
        now: Long,
        maxAgeMillis: Long,
        protectedFileNames: Set<String>,
        accept: (File) -> Boolean = { true }
    ): SweepResult {
        val files = dir.listFiles() ?: return SweepResult(0, 0L)
        var deletedCount = 0
        var freedBytes = 0L
        files.forEach { file ->
            if (!file.isFile) return@forEach
            if (file.name in protectedFileNames) return@forEach
            if (!accept(file)) return@forEach
            if (now - file.lastModified() <= maxAgeMillis) return@forEach
            val length = file.length()
            if (file.delete()) {
                deletedCount++
                freedBytes += length
            }
        }
        return SweepResult(deletedCount, freedBytes)
    }

    /**
     * Extracts the backing cache file name from a FileProvider `content://` URI
     * (or `file://` URI) string for a file inside one of the shared subdirs.
     *
     * The app's FileProvider maps `shared_images/<name>` to a path ending in
     * `.../shared_images/<name>` (and likewise for `shared_pdfs/`), so the last
     * path segment is the on-disk file name. Returns null if the string has no
     * usable last segment.
     */
    fun fileNameFromSharedUri(uriString: String): String? {
        if (uriString.isBlank()) return null
        // Strip any query/fragment, then take the final '/'-delimited segment.
        val withoutQuery = uriString.substringBefore('?').substringBefore('#')
        val segment = withoutQuery.trimEnd('/').substringAfterLast('/')
        return segment.ifBlank { null }
    }
}
