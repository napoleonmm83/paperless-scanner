package com.paperless.scanner.data.datastore

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * #359: the restore path must EVICT Android's process-level SharedPreferences cache
 * (via deleteSharedPreferences) before swapping the snapshot into place — overwriting
 * the file on disk alone is invisible to every later getSharedPreferences() call.
 *
 * These tests run against PLAIN SharedPreferences (Robolectric runs the real framework
 * SharedPreferencesImpl + per-name process cache): #359 is a cache bug, not a crypto
 * bug, so the mechanism is fully testable without the Keystore. The
 * EncryptedSharedPreferences composition (real AEADBadTagException at open) is covered
 * by the manual device test (docs/DEVICE_TEST_TOKEN_CORRUPTION.md).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class SecureTokenStorageRestoreEvictionTest {

    private companion object {
        const val PREFS_NAME = "paperless_secure_prefs"
        const val BACKUP_NAME = "paperless_secure_prefs.backup.xml"
        const val STAGING_NAME = "$BACKUP_NAME.restore.tmp"
        const val KEY = "probe"
    }

    private lateinit var context: Context
    private lateinit var storage: SecureTokenStorage

    private val prefsFile: File
        get() = File(context.dataDir, "shared_prefs/$PREFS_NAME.xml")

    private val backupFile: File
        get() = File(context.noBackupFilesDir, BACKUP_NAME)

    private val stagingFile: File
        get() = File(context.noBackupFilesDir, STAGING_NAME)

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        storage = SecureTokenStorage(context)
    }

    /** restorePrefsFileFromBackup is destructive and demands the storage monitor. */
    private fun restoreLocked(): Boolean =
        synchronized(storage) { storage.restorePrefsFileFromBackup() }

    private fun writeProbe(value: String) {
        assertTrue(
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY, value).commit()
        )
    }

    private fun readProbe(): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY, null)

    /**
     * Anti-vacuity guard: proves this harness reproduces the device semantics the fix
     * depends on (per-name process cache, eviction via deleteSharedPreferences, and the
     * SP file living at SecureTokenStorage's prefsFile() path). If Robolectric ever
     * stops emulating the cache, THIS fails loudly instead of the regression test
     * passing vacuously.
     */
    @Test
    fun `harness guard - SharedPreferences are cached per name and evicted by deleteSharedPreferences`() {
        val first = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        assertSame(first, context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

        first.edit().putString(KEY, "on-disk").commit()
        assertTrue(prefsFile.exists())

        context.deleteSharedPreferences(PREFS_NAME)
        assertNotSame(first, context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }

    /** The #359 regression pin: fails on the old copyTo-only restore, passes with eviction. */
    @Test
    fun `restore is visible through the process SharedPreferences cache`() {
        writeProbe("good")
        assertTrue(storage.backupCurrentPrefsFile())
        writeProbe("corrupt") // BOTH disk and the cached SharedPreferencesImpl now hold "corrupt"

        assertTrue(restoreLocked())

        // Old impl: the cache still serves "corrupt" even though the file was restored.
        assertEquals("good", readProbe())
    }

    /**
     * SharedPreferencesImpl.loadFromDisk rolls a `.bak` file back over the `.xml`
     * ("last write failed") — a stale corrupt `.bak` surviving the restore would
     * resurrect the corrupt bytes. deleteSharedPreferences removes both.
     */
    @Test
    fun `restore removes the prefs bak file so it cannot roll corrupt bytes back`() {
        writeProbe("good")
        assertTrue(storage.backupCurrentPrefsFile())
        writeProbe("corrupt")
        val bak = File(prefsFile.parentFile, "${prefsFile.name}.bak")
        prefsFile.copyTo(bak, overwrite = true) // valid XML holding "corrupt"

        assertTrue(restoreLocked())

        assertFalse(bak.exists())
        assertEquals("good", readProbe())
    }

    /** Crash-leftover staging tmp must not break later restores (pins overwrite=true). */
    @Test
    fun `leftover staging tmp does not break the next restore`() {
        writeProbe("good")
        assertTrue(storage.backupCurrentPrefsFile())
        writeProbe("corrupt")
        stagingFile.parentFile?.mkdirs()
        stagingFile.writeText("garbage-from-a-previous-crash")

        assertTrue(restoreLocked())

        assertEquals("good", readProbe())
        assertFalse(stagingFile.exists())
    }

    /**
     * Backup-policy: backup_rules.xml/data_extraction_rules.xml exclude only the exact
     * prefs path, so no restore artifact may ever appear inside shared_prefs/ where it
     * would be Auto-Backup eligible.
     */
    @Test
    fun `restore staging never leaves artifacts inside shared_prefs`() {
        writeProbe("good")
        assertTrue(storage.backupCurrentPrefsFile())
        writeProbe("corrupt")

        assertTrue(restoreLocked())

        val leaked = prefsFile.parentFile!!.listFiles().orEmpty()
            .filter { it.name.endsWith(".restore.tmp") }
        assertEquals(emptyList<File>(), leaked)
    }

    @Test
    fun `restore returns false without a backup and leaves no staging residue`() {
        backupFile.delete()

        assertFalse(restoreLocked())
        assertFalse(stagingFile.exists())
    }

    /**
     * The wipe path must clean the staging tmp alongside the snapshot — an orphaned
     * tmp completed after a wipe could resurrect a wiped credential. The return value
     * is the tombstone gate for the credential-mutating writes: true only when nothing
     * remains on disk (and idempotently true when nothing existed).
     */
    @Test
    fun `deleteBackupArtifacts removes snapshot AND staging tmp`() {
        backupFile.parentFile?.mkdirs()
        backupFile.writeText("snapshot")
        stagingFile.writeText("staging")

        assertTrue(storage.deleteBackupArtifacts())

        assertFalse(backupFile.exists())
        assertFalse(stagingFile.exists())

        // Idempotent tombstone: absent artifacts count as successfully deleted.
        assertTrue(storage.deleteBackupArtifacts())
    }

    /** The destructive restore (cache eviction) demands the storage lock. */
    @Test
    fun `restore without the storage lock trips the invariant`() {
        backupFile.parentFile?.mkdirs()
        backupFile.writeText("snapshot")

        try {
            storage.restorePrefsFileFromBackup()
            fail("expected IllegalStateException — restore is destructive and requires the lock")
        } catch (expected: IllegalStateException) {
            // expected: check(Thread.holdsLock(this)) tripped
        }
    }
}
