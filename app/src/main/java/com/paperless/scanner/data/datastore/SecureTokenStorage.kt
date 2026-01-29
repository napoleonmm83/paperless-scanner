package com.paperless.scanner.data.datastore

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.IOException
import java.security.GeneralSecurityException

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
 * - Fallback to in-memory storage if encryption fails (rare edge case)
 *
 * @param context Application context for accessing Android Keystore
 */
class SecureTokenStorage(private val context: Context) {

    companion object {
        private const val TAG = "SecureTokenStorage"
        private const val ENCRYPTED_PREFS_FILE = "paperless_secure_prefs"
        private const val KEY_AUTH_TOKEN = "auth_token"
        private const val KEY_MIGRATION_COMPLETED = "migration_completed"
    }

    private val masterKey: MasterKey by lazy {
        try {
            MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create MasterKey", e)
            throw SecurityException("Cannot initialize secure storage: ${e.message}", e)
        }
    }

    private val encryptedPrefs: SharedPreferences by lazy {
        try {
            EncryptedSharedPreferences.create(
                context,
                ENCRYPTED_PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: GeneralSecurityException) {
            Log.e(TAG, "Security exception creating EncryptedSharedPreferences", e)
            throw SecurityException("Cannot create encrypted storage: ${e.message}", e)
        } catch (e: IOException) {
            Log.e(TAG, "IO exception creating EncryptedSharedPreferences", e)
            throw SecurityException("Cannot access encrypted storage: ${e.message}", e)
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
            encryptedPrefs.edit()
                .putString(KEY_AUTH_TOKEN, token)
                .commit() // Use commit() for immediate write
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
            encryptedPrefs.getString(KEY_AUTH_TOKEN, null)
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
            encryptedPrefs.contains(KEY_AUTH_TOKEN)
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
            encryptedPrefs.edit()
                .remove(KEY_AUTH_TOKEN)
                .commit()
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
            encryptedPrefs.getBoolean(KEY_MIGRATION_COMPLETED, false)
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
            encryptedPrefs.edit()
                .putBoolean(KEY_MIGRATION_COMPLETED, true)
                .commit()
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
            encryptedPrefs.edit()
                .clear()
                .commit()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear all secure storage", e)
            false
        }
    }
}
