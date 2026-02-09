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
class SecureTokenStorage(private val context: Context) {

    companion object {
        private const val TAG = "SecureTokenStorage"
        private const val ENCRYPTED_PREFS_FILE = "paperless_secure_prefs"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_MIGRATION_COMPLETED = "migration_completed"
        private const val MASTER_KEY_ALIAS = "_androidx_security_master_key_"
    }

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    private fun getOrCreateEncryptedPrefs(): SharedPreferences? {
        cachedPrefs?.let { return it }

        synchronized(this) {
            cachedPrefs?.let { return it }

            return try {
                createEncryptedPrefs().also { cachedPrefs = it }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create EncryptedSharedPreferences, attempting recovery", e)
                recoverCorruptedStorage()
            }
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
     * Saves the authentication token securely.
     *
     * @param token The API token to store (will be encrypted)
     * @return true if save was successful, false otherwise
     */
    fun saveToken(token: String): Boolean {
        return try {
            getOrCreateEncryptedPrefs()?.edit()
                ?.putString(KEY_AUTH_TOKEN, token)
                ?.commit() ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save token", e)
            false
        }
    }

    /**
     * Retrieves the stored authentication token.
     *
     * @return The decrypted token, or null if not stored or decryption fails
     */
    fun getToken(): String? {
        return try {
            getOrCreateEncryptedPrefs()?.getString(KEY_AUTH_TOKEN, null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to retrieve token", e)
            null
        }
    }

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
    fun clearToken(): Boolean {
        return try {
            getOrCreateEncryptedPrefs()?.edit()
                ?.remove(KEY_AUTH_TOKEN)
                ?.commit() ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear token", e)
            false
        }
    }

    /**
     * Checks if migration from plaintext storage has been completed.
     */
    fun isMigrationCompleted(): Boolean {
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
    fun setMigrationCompleted(): Boolean {
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
    fun clearAll(): Boolean {
        return try {
            getOrCreateEncryptedPrefs()?.edit()
                ?.clear()
                ?.commit() ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear all secure storage", e)
            false
        }
    }
}
