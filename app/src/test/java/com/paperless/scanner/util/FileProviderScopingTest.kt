package com.paperless.scanner.util

import android.app.Application
import android.content.Context
import androidx.core.content.FileProvider
import com.paperless.scanner.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.xmlpull.v1.XmlPullParser
import java.io.File

/**
 * #241: verifies the app's FileProvider is scoped to purpose-specific cache
 * subdirectories ([SharedFileCache]) and never exposes the cache root.
 *
 * The security property is fully determined by `res/xml/file_paths.xml`, so the
 * core assertions parse that resource directly and check (a) the cache root "/"
 * is never exposed and (b) the exposed subdir names stay in sync with
 * [SharedFileCache]. This is platform-independent and cannot pass vacuously — if
 * someone reintroduces `path="/"` or renames a subdir on one side only, these
 * fail.
 *
 * Two getUriForFile() smoke tests then assert that unrelated cache files (cache
 * root, unexposed sibling) are rejected at runtime. They run against a plain
 * [Application] so the test boots without Hilt/Firebase.
 *
 * We deliberately do NOT assert getUriForFile() SUCCEEDS for an in-subdir file.
 * Under Robolectric the provider's configured roots are derived from a per-test
 * temporary cacheDir (the temp path embeds the test-method name) and do not
 * reliably match a file created in the same run, so that direction is a harness
 * artifact rather than a device behavior. The file_paths.xml parse test above
 * already pins the exact root names that make in-subdir files resolve on-device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class FileProviderScopingTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val authority = "${context.packageName}.fileprovider"

    /** Reads an attribute by local name, ignoring XML namespace prefixes. */
    private fun XmlPullParser.attr(localName: String): String? {
        for (i in 0 until attributeCount) {
            if (getAttributeName(i) == localName) return getAttributeValue(i)
        }
        return null
    }

    /** Parses res/xml/file_paths.xml into (name, path) pairs of its <cache-path> entries. */
    private fun cachePaths(): List<Pair<String, String>> {
        val entries = mutableListOf<Pair<String, String>>()
        val parser = context.resources.getXml(R.xml.file_paths)
        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && parser.name == "cache-path") {
                    entries.add((parser.attr("name") ?: "") to (parser.attr("path") ?: ""))
                }
                event = parser.next()
            }
        } finally {
            parser.close()
        }
        return entries
    }

    // --- Security property (AC1 + AC3): the cache root is never exposed. ---

    @Test
    fun `file_paths never exposes the cache root`() {
        val paths = cachePaths()
        assertTrue("file_paths.xml must declare at least one cache-path", paths.isNotEmpty())
        paths.forEach { (name, path) ->
            assertNotEquals("cache-path '$name' must not expose the cache root", "/", path)
            assertFalse("cache-path '$name' must not be blank/root", path.isBlank())
        }
    }

    // --- Invariant (AC2): exposed dirs match SharedFileCache, so shares resolve. ---

    @Test
    fun `exposed cache-path dirs exactly match SharedFileCache scoped dirs`() {
        val exposed = cachePaths().map { it.second.trimEnd('/') }.toSet()
        assertEquals(SharedFileCache.sharedDirNames.toSet(), exposed)
    }

    // --- Structural: scoped dirs are direct children of cacheDir. ---

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

    // --- Runtime rejection (AC3): unrelated cache files are NOT shareable. ---

    @Test(expected = IllegalArgumentException::class)
    fun `arbitrary file in cache root cannot be resolved`() {
        // Simulates an unrelated cache artifact (HTTP cache, processing temp, etc.).
        val rogue = File(context.cacheDir, "http_cache_entry.bin").apply { writeText("secret") }
        FileProvider.getUriForFile(context, authority, rogue)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `file in an unexposed sibling subdir cannot be resolved`() {
        val sibling = File(context.cacheDir, "image_cache").apply { mkdirs() }
        val rogue = File(sibling, "coil_thumb.0").apply { writeText("secret") }
        FileProvider.getUriForFile(context, authority, rogue)
    }
}
