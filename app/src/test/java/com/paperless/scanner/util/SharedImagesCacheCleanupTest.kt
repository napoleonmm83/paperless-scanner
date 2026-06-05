package com.paperless.scanner.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Pure JVM unit tests for the #307 draft-aware cache sweep helper
 * [SharedFileCache.cleanupAgedUnprotected] (and the [SharedFileCache.fileNameFromSharedUri]
 * helper that builds its protected set).
 *
 * The sweep is a pure function over the filesystem (no Android, no Hilt/Firebase
 * boot) precisely so it is directly testable — `PaperlessApp.onCreate`, which
 * calls it on a real cache dir at process start, is not. Conventions (#137/#99):
 * one logical case per @Test, plain JUnit assertions, no Robolectric.
 */
class SharedImagesCacheCleanupTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val now = 1_000_000_000L
    private val maxAge = 60 * 60 * 1000L // 1 hour, mirrors PaperlessApp.CACHE_MAX_AGE_MS

    /** Creates a file in the temp dir with the given last-modified time. */
    private fun fileWithAge(name: String, lastModified: Long): File {
        val file = File(tempFolder.root, name)
        file.writeText("content-$name")
        assertTrue("test setup: setLastModified must succeed", file.setLastModified(lastModified))
        return file
    }

    // --- Core behavior: aged + unprotected files are deleted. ---

    @Test
    fun `aged unprotected file is deleted`() {
        val aged = fileWithAge("rotated_1.jpg", now - maxAge - 1)

        val result = SharedFileCache.cleanupAgedUnprotected(
            dir = tempFolder.root,
            now = now,
            maxAgeMillis = maxAge,
            protectedFileNames = emptySet()
        )

        assertFalse("aged unprotected file must be deleted", aged.exists())
        assertEquals(1, result.deletedCount)
        assertTrue("freedBytes must be > 0", result.freedBytes > 0)
    }

    // --- Protection: a protected file is kept regardless of age. ---

    @Test
    fun `aged but protected file is kept`() {
        val protectedFile = fileWithAge("cropped_42.jpg", now - maxAge - 10_000)

        val result = SharedFileCache.cleanupAgedUnprotected(
            dir = tempFolder.root,
            now = now,
            maxAgeMillis = maxAge,
            protectedFileNames = setOf("cropped_42.jpg")
        )

        assertTrue("protected file must never be deleted (the #307 landmine)", protectedFile.exists())
        assertEquals(0, result.deletedCount)
        assertEquals(0L, result.freedBytes)
    }

    // --- Freshness: a recent file is kept even when unprotected. ---

    @Test
    fun `recent unprotected file is kept`() {
        val recent = fileWithAge("cropped_new.jpg", now - (maxAge / 2))

        val result = SharedFileCache.cleanupAgedUnprotected(
            dir = tempFolder.root,
            now = now,
            maxAgeMillis = maxAge,
            protectedFileNames = emptySet()
        )

        assertTrue("recent file must be kept", recent.exists())
        assertEquals(0, result.deletedCount)
    }

    // --- Boundary: a file exactly at the threshold is kept (not strictly older). ---

    @Test
    fun `file exactly at the age threshold is kept`() {
        val atThreshold = fileWithAge("cropped_edge.jpg", now - maxAge)

        SharedFileCache.cleanupAgedUnprotected(
            dir = tempFolder.root,
            now = now,
            maxAgeMillis = maxAge,
            protectedFileNames = emptySet()
        )

        assertTrue("file exactly at the threshold must be kept", atThreshold.exists())
    }

    // --- Mixed: only the aged+unprotected subset is deleted. ---

    @Test
    fun `mixed dir deletes only aged unprotected files`() {
        val agedProtected = fileWithAge("cropped_keep.jpg", now - maxAge - 1)   // kept: protected
        val agedUnprotected = fileWithAge("rotated_old.jpg", now - maxAge - 1)  // deleted
        val recentUnprotected = fileWithAge("rotated_new.jpg", now - 1000)      // kept: recent

        val result = SharedFileCache.cleanupAgedUnprotected(
            dir = tempFolder.root,
            now = now,
            maxAgeMillis = maxAge,
            protectedFileNames = setOf("cropped_keep.jpg")
        )

        assertTrue(agedProtected.exists())
        assertFalse(agedUnprotected.exists())
        assertTrue(recentUnprotected.exists())
        assertEquals(1, result.deletedCount)
    }

    // --- accept predicate: only matching files are eligible (legacy "document_" sweep). ---

    @Test
    fun `accept predicate limits eligible files`() {
        val matching = fileWithAge("document_old.pdf", now - maxAge - 1)
        val nonMatching = fileWithAge("coil_cache.0", now - maxAge - 1)

        val result = SharedFileCache.cleanupAgedUnprotected(
            dir = tempFolder.root,
            now = now,
            maxAgeMillis = maxAge,
            protectedFileNames = emptySet(),
            accept = { it.name.startsWith("document_") }
        )

        assertFalse("matching aged file must be deleted", matching.exists())
        assertTrue("non-matching file must be kept even when aged", nonMatching.exists())
        assertEquals(1, result.deletedCount)
    }

    // --- Subdirectories are never touched (only direct file children). ---

    @Test
    fun `subdirectories are ignored`() {
        val subDir = File(tempFolder.root, "nested").apply { mkdirs() }
        assertTrue(subDir.setLastModified(now - maxAge - 1))

        val result = SharedFileCache.cleanupAgedUnprotected(
            dir = tempFolder.root,
            now = now,
            maxAgeMillis = maxAge,
            protectedFileNames = emptySet()
        )

        assertTrue("subdirectory must not be deleted", subDir.isDirectory)
        assertEquals(0, result.deletedCount)
    }

    // --- Missing dir is a safe no-op. ---

    @Test
    fun `nonexistent dir is a safe no-op`() {
        val missing = File(tempFolder.root, "does_not_exist")

        val result = SharedFileCache.cleanupAgedUnprotected(
            dir = missing,
            now = now,
            maxAgeMillis = maxAge,
            protectedFileNames = emptySet()
        )

        assertEquals(0, result.deletedCount)
        assertEquals(0L, result.freedBytes)
    }

    // --- fileNameFromSharedUri: builds the protected set from draft URIs. ---

    @Test
    fun `fileNameFromSharedUri extracts last segment of content uri`() {
        assertEquals(
            "cropped_123.jpg",
            SharedFileCache.fileNameFromSharedUri(
                "content://com.paperless.scanner.fileprovider/shared_images/cropped_123.jpg"
            )
        )
    }

    @Test
    fun `fileNameFromSharedUri extracts last segment of file uri`() {
        assertEquals(
            "cropped_9.jpg",
            SharedFileCache.fileNameFromSharedUri("file:///data/cache/shared_images/cropped_9.jpg")
        )
    }

    @Test
    fun `fileNameFromSharedUri strips query and fragment`() {
        assertEquals(
            "cropped_5.jpg",
            SharedFileCache.fileNameFromSharedUri(
                "content://authority/shared_images/cropped_5.jpg?foo=bar#frag"
            )
        )
    }

    @Test
    fun `fileNameFromSharedUri returns null for blank input`() {
        assertNull(SharedFileCache.fileNameFromSharedUri(""))
        assertNull(SharedFileCache.fileNameFromSharedUri("   "))
    }

    @Test
    fun `protected set built from draft uris protects the backing file`() {
        val croppedFile = fileWithAge("cropped_777.jpg", now - maxAge - 1)
        val draftUris = listOf(
            "content://com.paperless.scanner.fileprovider/shared_images/cropped_777.jpg"
        )
        val protectedNames = draftUris.mapNotNull { SharedFileCache.fileNameFromSharedUri(it) }.toSet()

        SharedFileCache.cleanupAgedUnprotected(
            dir = tempFolder.root,
            now = now,
            maxAgeMillis = maxAge,
            protectedFileNames = protectedNames
        )

        assertTrue("draft-referenced cropped page must survive the sweep", croppedFile.exists())
    }
}
