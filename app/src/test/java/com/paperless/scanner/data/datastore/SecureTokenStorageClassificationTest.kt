package com.paperless.scanner.data.datastore

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.KeyStoreException
import javax.crypto.AEADBadTagException

/**
 * #320 Phase 1: pure-JVM coverage of the crypto-corruption classification that decides
 * whether [SecureTokenStorage] may destructively recover (wipe) the encrypted store.
 *
 * The REAL AEADBadTagException decrypt path is not reproducible under Robolectric
 * (the Keystore shadow does not actually decrypt) — that end-to-end path is covered
 * by the documented manual device test (docs/DEVICE_TEST_TOKEN_CORRUPTION.md).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30], manifest = Config.NONE)
class SecureTokenStorageClassificationTest {

    private lateinit var storage: SecureTokenStorage

    @Before
    fun setup() {
        val context: Context = RuntimeEnvironment.getApplication()
        storage = SecureTokenStorage(context)
    }

    @Test
    fun `direct AEADBadTagException is crypto corruption`() {
        assertTrue(storage.isCryptoCorruption(AEADBadTagException("bad tag")))
    }

    @Test
    fun `AEADBadTagException wrapped in GeneralSecurityException is crypto corruption`() {
        val wrapped = GeneralSecurityException("keyset decrypt failed", AEADBadTagException("bad tag"))
        assertTrue(storage.isCryptoCorruption(wrapped))
    }

    @Test
    fun `deeply nested AEADBadTagException is crypto corruption`() {
        val deep = RuntimeException(
            "outer",
            SecurityException("middle", GeneralSecurityException("inner", AEADBadTagException("bad tag"))),
        )
        assertTrue(storage.isCryptoCorruption(deep))
    }

    @Test
    fun `transient keystore failure is NOT crypto corruption`() {
        assertFalse(storage.isCryptoCorruption(KeyStoreException("keystore temporarily unavailable")))
        assertFalse(storage.isCryptoCorruption(IOException("disk error")))
        assertFalse(storage.isCryptoCorruption(RuntimeException("anything else")))
    }

    @Test
    fun `cyclic cause chain terminates and is NOT crypto corruption`() {
        val a = RuntimeException("a")
        val b = RuntimeException("b", a)
        a.initCause(b)

        assertFalse(storage.isCryptoCorruption(a))
    }

    @Test
    fun `consumeRecoveredCryptoFailure returns null when nothing was recovered`() {
        assertTrue(storage.consumeRecoveredCryptoFailure() == null)
    }

    // ==================== classifyFailure taxonomy (#320 Phase 2) ====================

    @Test
    fun `classifyFailure maps AEADBadTagException to CRYPTO_CORRUPTION`() {
        assertEquals(
            TokenStorageFailureKind.CRYPTO_CORRUPTION,
            storage.classifyFailure(GeneralSecurityException("wrap", AEADBadTagException("bad tag"))),
        )
    }

    @Test
    fun `classifyFailure maps keystore exceptions to KEYSTORE_UNAVAILABLE`() {
        assertEquals(
            TokenStorageFailureKind.KEYSTORE_UNAVAILABLE,
            storage.classifyFailure(KeyStoreException("keystore busy")),
        )
        assertEquals(
            TokenStorageFailureKind.KEYSTORE_UNAVAILABLE,
            storage.classifyFailure(RuntimeException("wrap", java.security.ProviderException("provider down"))),
        )
    }

    @Test
    fun `classifyFailure maps IOException to IO_ERROR`() {
        assertEquals(
            TokenStorageFailureKind.IO_ERROR,
            storage.classifyFailure(RuntimeException("wrap", IOException("disk full"))),
        )
    }

    @Test
    fun `classifyFailure maps anything else to UNEXPECTED`() {
        assertEquals(
            TokenStorageFailureKind.UNEXPECTED,
            storage.classifyFailure(IllegalStateException("boom")),
        )
    }

    // ==================== Backup / restore roundtrip (#320 Phase 2) ====================

    @Test
    fun `backup and restore roundtrip preserves the prefs file content`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val prefsDir = java.io.File(context.dataDir, "shared_prefs").apply { mkdirs() }
        val prefsFile = java.io.File(prefsDir, "paperless_secure_prefs.xml")

        prefsFile.writeText("original-ciphertext")
        storage.backupCurrentPrefsFile()

        // Simulate corruption of the live file, then restore the snapshot.
        prefsFile.writeText("corrupted-garbage")
        assertTrue(storage.restorePrefsFileFromBackup())

        assertEquals("original-ciphertext", prefsFile.readText())
    }

    @Test
    fun `restore returns false when no backup exists`() {
        val context: Context = RuntimeEnvironment.getApplication()
        java.io.File(context.noBackupFilesDir, "paperless_secure_prefs.backup.xml").delete()

        assertFalse(storage.restorePrefsFileFromBackup())
    }

    @Test
    fun `backup snapshot lives in noBackupFilesDir — excluded from Android Auto Backup`() {
        val context: Context = RuntimeEnvironment.getApplication()
        val prefsDir = java.io.File(context.dataDir, "shared_prefs").apply { mkdirs() }
        java.io.File(prefsDir, "paperless_secure_prefs.xml").writeText("ciphertext")

        storage.backupCurrentPrefsFile()

        assertTrue(java.io.File(context.noBackupFilesDir, "paperless_secure_prefs.backup.xml").exists())
        assertFalse(java.io.File(context.filesDir, "paperless_secure_prefs.backup.xml").exists())
    }
}
