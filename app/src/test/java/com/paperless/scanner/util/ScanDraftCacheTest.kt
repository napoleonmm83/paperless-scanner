package com.paperless.scanner.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * Unit tests for [ScanDraftCache] — the App-readable SharedPreferences mirror of
 * the persisted scan draft's `shared_images` backing file names (#307).
 *
 * Uses Robolectric for a real SharedPreferences instance.
 */
@RunWith(RobolectricTestRunner::class)
class ScanDraftCacheTest {

    private lateinit var cache: ScanDraftCache

    @Before
    fun setUp() {
        val context = RuntimeEnvironment.getApplication()
        // Clear any leftover state from previous tests.
        context.getSharedPreferences(ScanDraftCache.PREFS_NAME, 0).edit().clear().commit()
        cache = ScanDraftCache(context)
    }

    @Test
    fun `defaults to an empty protected set`() {
        assertTrue(cache.getProtectedFileNames().isEmpty())
    }

    @Test
    fun `mirror stores backing file names from draft uris`() {
        cache.setProtectedFileNames(
            listOf(
                "content://com.paperless.scanner.fileprovider/shared_images/cropped_1.jpg",
                "content://com.paperless.scanner.fileprovider/shared_images/cropped_2.jpg"
            )
        )

        assertEquals(setOf("cropped_1.jpg", "cropped_2.jpg"), cache.getProtectedFileNames())
    }

    @Test
    fun `empty draft clears the protected set`() {
        cache.setProtectedFileNames(
            listOf("content://authority/shared_images/cropped_x.jpg")
        )
        assertEquals(setOf("cropped_x.jpg"), cache.getProtectedFileNames())

        cache.setProtectedFileNames(emptyList())
        assertTrue(cache.getProtectedFileNames().isEmpty())
    }

    @Test
    fun `survives a new instance over the same prefs`() {
        cache.setProtectedFileNames(
            listOf("content://authority/shared_images/cropped_persist.jpg")
        )

        // Simulates a fresh process reading at App.onCreate.
        val reopened = ScanDraftCache(RuntimeEnvironment.getApplication())
        assertEquals(setOf("cropped_persist.jpg"), reopened.getProtectedFileNames())
    }
}
