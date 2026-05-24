package com.paperless.scanner.data.network

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.security.KeyStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * EncryptedSharedPreferences-backed [CertPinStorage] (Issue #36, AC: "pinned hash
 * stored encrypted").
 *
 * Each preference entry is `host -> SPKI pin`. The Android-Keystore master key is
 * device-bound and never leaves the device, so even if the prefs file is included
 * in a backup it cannot be decrypted elsewhere. Recovery from a corrupted keystore
 * mirrors [com.paperless.scanner.data.datastore.SecureTokenStorage]; on permanent
 * failure the store degrades to in-memory-only (pins re-captured via TOFU on next
 * connection) rather than crashing.
 */
@Singleton
class EncryptedCertPinStorage @Inject constructor(
    @ApplicationContext private val context: Context
) : CertPinStorage {

    companion object {
        private const val TAG = "EncryptedCertPinStorage"
        private const val PREFS_FILE = "paperless_cert_pins"
        private const val MASTER_KEY_ALIAS = "_androidx_security_master_key_"
    }

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    private fun prefs(): SharedPreferences? {
        cachedPrefs?.let { return it }
        synchronized(this) {
            cachedPrefs?.let { return it }
            return try {
                create().also { cachedPrefs = it }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to open encrypted pin storage, attempting recovery", e)
                recover()
            }
        }
    }

    private fun create(): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun recover(): SharedPreferences? {
        try {
            context.deleteSharedPreferences(PREFS_FILE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete corrupted pin prefs", e)
        }
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            if (keyStore.containsAlias(MASTER_KEY_ALIAS)) {
                keyStore.deleteEntry(MASTER_KEY_ALIAS)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete master key from Keystore", e)
        }
        return try {
            create().also { cachedPrefs = it }
        } catch (e: Exception) {
            Log.e(TAG, "Pin storage recovery failed - pinning degrades to in-memory only", e)
            null
        }
    }

    override fun loadAll(): Map<String, String> {
        return try {
            prefs()?.all
                ?.mapNotNull { (k, v) -> (v as? String)?.let { k to it } }
                ?.toMap()
                ?: emptyMap()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load pins", e)
            emptyMap()
        }
    }

    override fun put(host: String, pin: String) {
        try {
            prefs()?.edit()?.putString(host, pin)?.apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist pin", e)
        }
    }

    override fun remove(host: String) {
        try {
            prefs()?.edit()?.remove(host)?.apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to remove pin", e)
        }
    }

    override fun clear() {
        try {
            prefs()?.edit()?.clear()?.apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear pins", e)
        }
    }
}
