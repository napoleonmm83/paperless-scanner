package com.paperless.scanner.util

import android.app.Application
import android.content.Context
import androidx.core.content.FileProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * #241: verifies the app's FileProvider resolves ONLY files inside the
 * purpose-scoped cache subdirectories ([SharedFileCache]) and REJECTS arbitrary
 * files in the cache root (or other, unexposed subdirectories). This guarantees a
 * stray/mistaken `getUriForFile()` can never grant another app read access to
 * unrelated cached content (OkHttp HTTP cache, Coil image cache, processing temp).
 *
 * Runs against a plain [Application] (not the Hilt `PaperlessApp`) so the test
 * boots without DI/Firebase; the merged manifest's FileProvider `<meta-data>`
 * (which points at `res/xml/file_paths.xml`) is still read by the package manager.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FileProviderScopingTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val authority = "${context.packageName}.fileprovider"

    // --- Security property (AC1 + AC3): unrelated cache files are NOT shareable.
    //     These run on every platform.

    @Test(expected = IllegalArgumentException::class)
    fun `arbitrary file in cache root cannot be resolved`() {
        // Simulates an unrelated cache artifact (HTTP cache, processing temp, etc.).
        val rogue = File(context.cacheDir, "http_cache_entry.bin").apply { writeText("secret") }

        // Must throw: the provider no longer exposes the cache root.
        FileProvider.getUriForFile(context, authority, rogue)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `file in an unexposed sibling subdir cannot be resolved`() {
        val sibling = File(context.cacheDir, "image_cache").apply { mkdirs() }
        val rogue = File(sibling, "coil_thumb.0").apply { writeText("secret") }

        FileProvider.getUriForFile(context, authority, rogue)
    }

    // --- Cross-platform structural check: the scoped dirs are nested directly under
    //     the cache root (so the FileProvider <cache-path> entries map onto them).

    @Test
    fun `shared cache dirs are created directly under cacheDir`() {
        listOf(
            SharedFileCache.sharedPdfsDir(context.cacheDir),
            SharedFileCache.sharedImagesDir(context.cacheDir),
        ).forEach { dir ->
            assertTrue("$dir must exist as a directory", dir.isDirectory)
            assertEquals(context.cacheDir.canonicalFile, dir.canonicalFile.parentFile)
        }
    }

    // --- Positive FileProvider proof: files in the scoped subdirs ARE shareable
    //     (catches a file_paths.xml <-> SharedFileCache directory-name mismatch).
    //
    //     FileProvider.getUriForFile() compares file.getCanonicalPath() against each
    //     root's getPath() (non-canonical). On Windows the Robolectric temp cacheDir
    //     canonicalizes to a different prefix (8.3 name / junction / drive-letter
    //     case), so this comparison is only reliable on POSIX file systems — which is
    //     what GitHub Actions CI runs on. Skipped locally on Windows.

    @Test
    fun `pdf in shared_pdfs subdir is shareable`() {
        assumeTrue("FileProvider canonical-path check is unreliable on Windows", File.separatorChar == '/')
        val file = File(SharedFileCache.sharedPdfsDir(context.cacheDir), "doc.pdf").apply { writeText("pdf") }

        val uri = FileProvider.getUriForFile(context, authority, file)

        assertNotNull(uri)
        assertTrue(uri.toString().startsWith("content://$authority/"))
    }

    @Test
    fun `image in shared_images subdir is shareable`() {
        assumeTrue("FileProvider canonical-path check is unreliable on Windows", File.separatorChar == '/')
        val file = File(SharedFileCache.sharedImagesDir(context.cacheDir), "page.jpg").apply { writeText("jpg") }

        val uri = FileProvider.getUriForFile(context, authority, file)

        assertNotNull(uri)
        assertTrue(uri.toString().startsWith("content://$authority/"))
    }
}
