package com.paperless.scanner.data.datastore

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore

/**
 * Secure storage for sensitive authentication data using EncryptedSharedPreferences.
 *
 * Uses Android Keystore backed encryption (AES256-GCM for values, AES256-SIV for keys).
 * This ensures tokens are encrypted at rest and cannot be accessed even with root access
 * without the device's hardware-backed keys.
 *
 * Features:
 * - AES256 encryption for all stored values
 * - Hardware-backed key storage (Android Keystore)
 * - Automatic migration from plaintext DataStore storage
 * - Automatic recovery from corrupted keystore (AEADBadTagException)
 *
 * @param context Application context for accessing Android Keystore
 */
class SecureTokenStorage(private val context: Context) : TokenStorage {

    companion object {
        private const val TAG = "SecureTokenStorage"
        private const val ENCRYPTED_PREFS_FILE = "paperless_secure_prefs"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_MIGRATION_COMPLETED = "migration_completed"
        private const val MASTER_KEY_ALIAS = "_androidx_security_master_key_"

        /** Cap for the cause-chain walk in [isCryptoCorruption] (guards against cycles). */
        private const val MAX_CAUSE_CHAIN_DEPTH = 10

        /** #320 Phase 2: ciphertext snapshot used for restore-instead-of-wipe. */
        private const val BACKUP_FILE_NAME = "paperless_secure_prefs.backup.xml"
    }

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    /**
     * Set when a CONFIRMED crypto corruption (AEADBadTagException) forced
     * [recoverCorruptedStorage] to wipe the store. Consumed once by
     * [consumeRecoveredCryptoFailure] so TokenManager can surface "please
     * re-authenticate" instead of a silent token loss (#320 Phase 1).
     */
    @Volatile
    private var lastRecoveredCryptoFailure: Exception? = null

    /** Why the last open attempt failed — feeds [getTokenResult]/[saveTokenResult] (#320 Phase 2). */
    @Volatile
    private var lastOpenFailure: Pair<TokenStorageFailureKind, Throwable>? = null

    override fun consumeRecoveredCryptoFailure(): Exception? {
        val failure = lastRecoveredCryptoFailure
        lastRecoveredCryptoFailure = null
        return failure
    }

    private fun getOrCreateEncryptedPrefs(): SharedPreferences? {
        cachedPrefs?.let { return it }

        synchronized(this) {
            cachedPrefs?.let { return it }

            return try {
                createEncryptedPrefs().also {
                    cachedPrefs = it
                    lastOpenFailure = null
                }
            } catch (e: Exception) {
                // #320 Phase 1: classify BEFORE recovering. Only a confirmed crypto
                // corruption (AEADBadTagException in the cause chain) justifies the
                // destructive wipe — a transient failure (Keystore temporarily
                // unavailable, disk IO) must NOT delete the user's token: returning
                // null leaves the data intact for a later retry.
                if (isCryptoCorruption(e)) {
                    Log.e(TAG, "Crypto corruption detected on open", e)

                    // #320 Phase 2: try the ciphertext snapshot BEFORE the destructive
                    // wipe — when only the prefs FILE is damaged (master key intact),
                    // this brings the token back instead of forcing a re-login.
                    if (restorePrefsFileFromBackup()) {
                        try {
                            return createEncryptedPrefs().also {
                                cachedPrefs = it
                                lastOpenFailure = null
                                Log.i(TAG, "Restored encrypted storage from backup — token preserved")
                            }
                        } catch (retryError: Exception) {
                            if (!isCryptoCorruption(retryError)) {
                                // The RETRY failed transiently (Keystore busy, IO) — the
                                // restored file may well be fine. Do NOT wipe; keep the
                                // data for a later attempt (codex P2).
                                val kind = classifyFailure(retryError)
                                lastOpenFailure = kind to retryError
                                Log.w(TAG, "Restore retry failed transiently ($kind) — keeping data, NOT wiping", retryError)
                                return null
                            }
                            Log.w(TAG, "Backup restore did not open either, falling back to wipe", retryError)
                        }
                    }

                    Log.e(TAG, "Recovering by wipe (stored token will be lost)")
                    lastRecoveredCryptoFailure = e
                    lastOpenFailure = TokenStorageFailureKind.CRYPTO_CORRUPTION to e
                    recoverCorruptedStorage()
                } else {
                    val kind = classifyFailure(e)
                    lastOpenFailure = kind to e
                    Log.e(TAG, "Transient failure opening encrypted prefs ($kind) — NOT wiping, will retry later", e)
                    null
                }
            }
        }
    }

    private fun causeChain(e: Throwable): Sequence<Throwable> =
        generateSequence<Throwable>(e) { it.cause?.takeIf { cause -> cause !== it } }
            .take(MAX_CAUSE_CHAIN_DEPTH)

    /**
     * True when [e] is a confirmed crypto corruption: an [javax.crypto.AEADBadTagException]
     * anywhere in the cause chain. EncryptedSharedPreferences wraps it in varying outer
     * exceptions (GeneralSecurityException, SecurityException) depending on whether the
     * keyset or a value fails to decrypt, so the whole chain is walked.
     */
    internal fun isCryptoCorruption(e: Throwable): Boolean =
        causeChain(e).any { it is javax.crypto.AEADBadTagException }

    /**
     * #320 Phase 2: maps an exception to the [TokenStorageFailureKind] taxonomy.
     * Only CRYPTO_CORRUPTION ever justifies destructive recovery — every other kind
     * leaves the stored data intact.
     */
    internal fun classifyFailure(e: Throwable): TokenStorageFailureKind = when {
        isCryptoCorruption(e) -> TokenStorageFailureKind.CRYPTO_CORRUPTION
        causeChain(e).any {
            it is java.security.KeyStoreException ||
                it is java.security.ProviderException ||
                it is java.security.UnrecoverableKeyException
        } -> TokenStorageFailureKind.KEYSTORE_UNAVAILABLE
        causeChain(e).any { it is java.io.IOException } -> TokenStorageFailureKind.IO_ERROR
        else -> TokenStorageFailureKind.UNEXPECTED
    }

    // ==================== Backup / restore (#320 Phase 2) ====================

    private fun prefsFile(): java.io.File =
        java.io.File(context.dataDir, "shared_prefs/$ENCRYPTED_PREFS_FILE.xml")

    private fun backupFile(): java.io.File =
        // noBackupFilesDir: the snapshot must NEVER reach Android Auto Backup or
        // device transfer — the original prefs file is excluded via backup_rules.xml/
        // data_extraction_rules.xml, and the snapshot must follow the same policy
        // without depending on anyone remembering to extend those rules (codex P1).
        java.io.File(context.noBackupFilesDir, BACKUP_FILE_NAME)

    /**
     * Snapshots the encrypted prefs FILE after a successful token write. The master
     * key lives in the Android Keystore (hardware-backed, non-exportable) and cannot
     * be backed up — this restore therefore only helps when the FILE is damaged but
     * the key is intact (the common partial-write AEADBadTagException case). The
     * backup holds the same ciphertext as the original: no plaintext touches disk.
     */
    internal fun backupCurrentPrefsFile(): Boolean {
        return try {
            val src = prefsFile()
            if (!src.exists()) return false
            val backup = backupFile()
            // Atomic swap (codex P2): copy to a temp file first and rename only after
            // the copy fully succeeded — a crash mid-copy must never truncate the last
            // good snapshot (which the next corruption recovery would then restore).
            val tmp = java.io.File(backup.parentFile, "$BACKUP_FILE_NAME.tmp")
            src.copyTo(tmp, overwrite = true)
            if (!tmp.renameTo(backup)) {
                // rename-over-existing can fail on some filesystems: delete-then-rename.
                backup.delete()
                if (!tmp.renameTo(backup)) {
                    Log.w(TAG, "Failed to swap backup snapshot into place")
                    tmp.delete()
                    return false
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to back up encrypted prefs file", e)
            false
        }
    }

    /** One-time-per-process guard for [seedBackupIfMissing] (avoids a stat per read). */
    @Volatile
    private var backupSeedChecked = false

    private fun seedBackupIfMissing() {
        if (backupSeedChecked) return
        backupSeedChecked = true
        try {
            if (!backupFile().exists()) {
                backupCurrentPrefsFile()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to seed backup snapshot", e)
        }
    }

    /** Staging file for the restore swap (#359). Lives in noBackupFilesDir like the
     *  snapshot itself: backup_rules.xml / data_extraction_rules.xml exclude only the
     *  exact prefs path, so a crash-leftover tmp inside shared_prefs would silently
     *  become Auto-Backup/D2D-eligible ciphertext. */
    private fun restoreStagingFile(): java.io.File =
        java.io.File(context.noBackupFilesDir, "$BACKUP_FILE_NAME.restore.tmp")

    internal fun deleteBackupArtifacts() {
        try {
            backupFile().delete()
            // #359: a crash-leftover restore staging file must not outlive the snapshot —
            // a later completion of an orphaned tmp could resurrect a wiped credential.
            restoreStagingFile().delete()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to delete backup file", e)
        }
    }

    /**
     * Restores the prefs file from the last snapshot. Returns false when no backup exists.
     *
     * #359: Android caches the SharedPreferencesImpl per file name for the process
     * lifetime, so overwriting the file on disk is INVISIBLE to every later
     * getSharedPreferences() call (and SharedPreferencesImpl's own stale `.bak` can
     * even roll corrupt bytes back over a restored file). deleteSharedPreferences is
     * the only public API that evicts that cache, hence stage → evict → rename:
     * a fresh SharedPreferencesImpl is then built from the restored bytes.
     *
     * A process death between evict and rename leaves no prefs file: the next launch
     * opens a fresh empty store (silent logged-out, no corruption signal) — same data
     * outcome as today's wipe, accepted as a two-syscall-wide window. Deliberately NO
     * auto-completion of an orphaned tmp on a later open: a stale tmp surviving a
     * subsequent wipe could resurrect a wiped credential.
     *
     * Destructive (evicts the process cache): callers MUST hold the storage monitor.
     */
    internal fun restorePrefsFileFromBackup(): Boolean {
        check(Thread.holdsLock(this)) { "restorePrefsFileFromBackup requires the storage lock" }
        return try {
            val backup = backupFile()
            if (!backup.exists()) return false
            // overwrite=true: a crash-leftover tmp must never turn every future
            // restore into a FileAlreadyExistsException → wipe.
            val tmp = restoreStagingFile()
            backup.copyTo(tmp, overwrite = true)
            // The destructive operation owns the in-memory cache: a stale cachedPrefs
            // would keep serving (and writing through) the evicted instance.
            cachedPrefs = null
            // Evicts the process-level cache and removes BOTH <name>.xml and <name>.xml.bak.
            if (!context.deleteSharedPreferences(ENCRYPTED_PREFS_FILE)) {
                Log.w(TAG, "deleteSharedPreferences reported incomplete deletion — continuing restore")
            }
            val target = prefsFile()
            target.parentFile?.mkdirs()
            if (!tmp.renameTo(target)) {
                // rename-over-existing can fail on some filesystems: delete-then-rename.
                target.delete()
                if (!tmp.renameTo(target)) {
                    Log.w(TAG, "Failed to move restored snapshot into place")
                    tmp.delete()
                    return false
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to restore encrypted prefs from backup", e)
            false
        }
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            ENCRYPTED_PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    /**
     * Recovers from corrupted Android Keystore / EncryptedSharedPreferences.
     *
     * This handles AEADBadTagException and similar crypto failures by:
     * 1. Deleting the corrupted SharedPreferences file
     * 2. Removing the master key from Android Keystore
     * 3. Recreating both from scratch
     *
     * The stored token is lost - user will need to re-authenticate.
     */
    private fun recoverCorruptedStorage(): SharedPreferences? {
        Log.w(TAG, "Recovering corrupted encrypted storage - stored token will be lost")

        try {
            // 1. Delete the corrupted SharedPreferences file
            context.deleteSharedPreferences(ENCRYPTED_PREFS_FILE)
            Log.d(TAG, "Deleted corrupted SharedPreferences file")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete corrupted prefs file", e)
        }

        // 1b. #320 Phase 2: the snapshot did not help (or matches the corrupt
        // state) — delete it (and any restore staging leftover, #359) so a later
        // corruption can't restore stale garbage.
        deleteBackupArtifacts()

        try {
            // 2. Remove the corrupted master key from Android Keystore
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            if (keyStore.containsAlias(MASTER_KEY_ALIAS)) {
                keyStore.deleteEntry(MASTER_KEY_ALIAS)
                Log.d(TAG, "Deleted corrupted master key from Keystore")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete master key from Keystore", e)
        }

        // 3. Recreate fresh EncryptedSharedPreferences
        return try {
            createEncryptedPrefs().also {
                cachedPrefs = it
                Log.i(TAG, "Successfully recovered encrypted storage - user must re-authenticate")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Recovery failed - encrypted storage unavailable", e)
            null
        }
    }

    /**
     * Classified read (#320 Phase 2): distinguishes Absent (healthy storage, no token)
     * from the failure kinds. A value-level decrypt failure is classified the same way
     * as an open-time failure.
     */
    override fun getTokenResult(): TokenStorageResult<String> {
        val prefs = getOrCreateEncryptedPrefs()
            ?: return lastOpenFailure.toFailureResult()
        // If THIS open just performed a destructive corruption recovery, surface it
        // once as a Failure instead of a silent Absent. The init-time path is unaffected
        // (TokenManager.init consumes the signal before any token read); this covers a
        // read-time open after an earlier transient failure left the cache empty (codex P2).
        consumeRecoveredCryptoFailure()?.let { failure ->
            return TokenStorageResult.Failure(TokenStorageFailureKind.CRYPTO_CORRUPTION, failure)
        }
        return try {
            when (val token = prefs.getString(KEY_AUTH_TOKEN, null)) {
                null -> TokenStorageResult.Absent
                else -> {
                    // Seed the snapshot for existing users whose token predates the
                    // backup feature: they only READ until the next login, and without
                    // a snapshot restore-instead-of-wipe could never protect them
                    // (codex P2). One-time per process, guarded by the flag.
                    seedBackupIfMissing()
                    TokenStorageResult.Present(token)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve token", e)
            val kind = classifyFailure(e)
            if (kind == TokenStorageFailureKind.CRYPTO_CORRUPTION) {
                // The store OPENED but the value itself is corrupt — try the snapshot
                // before surfacing the failure, mirroring the open-time path (codex P2).
                restoreAndRereadToken()?.let { return it }
            }
            TokenStorageResult.Failure(kind, e)
        }
    }

    /**
     * Value-level corruption recovery: restores the snapshot, reopens the store and
     * re-reads the token. Returns null when no backup exists or the retry fails —
     * the caller then surfaces the original CRYPTO_CORRUPTION failure.
     */
    private fun restoreAndRereadToken(): TokenStorageResult<String>? = synchronized(this) {
        // #359: the whole restore + reopen + re-read is one critical section. Many
        // threads read tokens concurrently (every OkHttp request); two racing restores
        // would interleave the destructive evict/rename and could convert a recoverable
        // corruption into a wipe. cachedPrefs is invalidated inside the restore itself.
        if (!restorePrefsFileFromBackup()) return null
        val restored = getOrCreateEncryptedPrefs() ?: return null
        // The restored snapshot may itself be corrupt: the reopen above can have gone
        // through destructive recovery. Surface that as CRYPTO_CORRUPTION instead of
        // letting the freshly wiped store read as a silent Absent (codex P2).
        consumeRecoveredCryptoFailure()?.let { failure ->
            return TokenStorageResult.Failure(TokenStorageFailureKind.CRYPTO_CORRUPTION, failure)
        }
        return try {
            when (val token = restored.getString(KEY_AUTH_TOKEN, null)) {
                null -> TokenStorageResult.Absent
                else -> {
                    Log.i(TAG, "Restored token from backup after value-level corruption")
                    TokenStorageResult.Present(token)
                }
            }
        } catch (retryError: Exception) {
            Log.w(TAG, "Value re-read after backup restore still failing", retryError)
            null
        }
    }

    /**
     * Classified write (#320 Phase 2). A successful commit refreshes the ciphertext
     * snapshot used for restore-instead-of-wipe.
     */
    override fun saveTokenResult(token: String): TokenStorageResult<Unit> = synchronized(this) {
        // #359: writes hold the storage monitor so a commit can never interleave with
        // the destructive restore eviction — a write through an evicted (orphaned)
        // SharedPreferencesImpl would rewrite the freshly restored file from a stale
        // in-memory map.
        val prefs = getOrCreateEncryptedPrefs()
            ?: return lastOpenFailure.toFailureResult()
        return try {
            // Tombstone-first (#359 codex P1): the snapshot must die BEFORE the commit.
            // The monitor cannot protect across process death — a crash after the
            // commit but before the snapshot refresh would otherwise leave a backup
            // holding the PREVIOUS token, which a later corruption restore (now that
            // it actually works) would pair with the freshly saved server state.
            // Worst case of the new order (crash between delete and commit): token
            // unchanged, no snapshot → restore degrades to the wipe, never resurrects.
            deleteBackupArtifacts()
            val committed = prefs.edit()
                .putString(KEY_AUTH_TOKEN, token)
                .commit()
            if (committed) {
                // Fail closed (codex P2): if the snapshot refresh fails, DELETE the old
                // backup — a later restore must never resurrect a PREVIOUS token and
                // pair it with the freshly saved server state.
                if (!backupCurrentPrefsFile()) {
                    deleteBackupArtifacts()
                }
                // A freshly committed token supersedes any pending corruption signal:
                // the user just (re-)authenticated, so the next read must return THIS
                // token instead of a stale CRYPTO_CORRUPTION failure (codex P2).
                lastRecoveredCryptoFailure = null
                TokenStorageResult.Present(Unit)
            } else {
                TokenStorageResult.Failure(TokenStorageFailureKind.IO_ERROR)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save token", e)
            TokenStorageResult.Failure(classifyFailure(e), e)
        }
    }

    private fun Pair<TokenStorageFailureKind, Throwable>?.toFailureResult(): TokenStorageResult.Failure =
        this?.let { TokenStorageResult.Failure(it.first, it.second) }
            ?: TokenStorageResult.Failure(TokenStorageFailureKind.UNEXPECTED)

    /**
     * Saves the authentication token securely.
     *
     * @param token The API token to store (will be encrypted)
     * @return true if save was successful, false otherwise
     */
    @Deprecated(
        "Collapses all failure kinds to false — use saveTokenResult() (#320)",
        ReplaceWith("saveTokenResult(token)"),
    )
    override fun saveToken(token: String): Boolean =
        saveTokenResult(token) is TokenStorageResult.Present

    /**
     * Retrieves the stored authentication token.
     *
     * @return The decrypted token, or null if not stored or decryption fails
     */
    @Deprecated(
        "Collapses Absent and Failure to null — use getTokenResult() (#320)",
        ReplaceWith("getTokenResult()"),
    )
    override fun getToken(): String? =
        (getTokenResult() as? TokenStorageResult.Present)?.value

    /**
     * Checks if a token is stored.
     *
     * @return true if a token exists in secure storage
     */
    fun hasToken(): Boolean {
        return try {
            getOrCreateEncryptedPrefs()?.contains(KEY_AUTH_TOKEN) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check token existence", e)
            false
        }
    }

    /**
     * Removes the stored token.
     *
     * @return true if removal was successful
     */
    override fun clearToken(): Boolean = synchronized(this) {
        // #359: see saveTokenResult — writes must not race the restore eviction.
        return try {
            // Tombstone-first (#359 codex P1): kill the snapshot BEFORE the commit —
            // a crash between commit and snapshot refresh must never leave a backup
            // from which a later restore could resurrect the credential the user
            // just cleared (logout!).
            deleteBackupArtifacts()
            val committed = getOrCreateEncryptedPrefs()?.edit()
                ?.remove(KEY_AUTH_TOKEN)
                ?.commit() ?: false
            if (committed) {
                // #320: refresh the snapshot with the CLEARED state. Fail closed: if
                // the refresh fails, DELETE the old backup — a stale snapshot with the
                // cleared credential must never survive (codex P2).
                if (!backupCurrentPrefsFile()) {
                    deleteBackupArtifacts()
                }
            }
            committed
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear token", e)
            false
        }
    }

    /**
     * Checks if migration from plaintext storage has been completed.
     */
    override fun isMigrationCompleted(): Boolean {
        return try {
            getOrCreateEncryptedPrefs()?.getBoolean(KEY_MIGRATION_COMPLETED, false) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check migration status", e)
            false
        }
    }

    /**
     * Marks migration as completed.
     */
    override fun setMigrationCompleted(): Boolean = synchronized(this) {
        // #359: see saveTokenResult — writes must not race the restore eviction.
        return try {
            getOrCreateEncryptedPrefs()?.edit()
                ?.putBoolean(KEY_MIGRATION_COMPLETED, true)
                ?.commit() ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set migration completed", e)
            false
        }
    }

    /**
     * Clears all secure storage data.
     * Use with caution - this will require user to re-authenticate.
     */
    fun clearAll(): Boolean = synchronized(this) {
        // #359: see saveTokenResult — writes must not race the restore eviction.
        return try {
            // Tombstone-first (#359 codex P1): kill the snapshot BEFORE the commit —
            // see clearToken.
            deleteBackupArtifacts()
            val committed = getOrCreateEncryptedPrefs()?.edit()
                ?.clear()
                ?.commit() ?: false
            if (committed) {
                // #320: refresh the snapshot with the CLEARED state. Fail closed: if
                // the refresh fails, DELETE the old backup (codex P2).
                if (!backupCurrentPrefsFile()) {
                    deleteBackupArtifacts()
                }
            }
            committed
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear all secure storage", e)
            false
        }
    }
}
