package com.paperless.scanner.data.network

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import com.paperless.scanner.util.AppLogger

/**
 * EncryptedSharedPreferences-backed [CertPinStorage] (Issue #36, AC: "pinned hash
 * stored encrypted").
 *
 * Each preference entry is `host -> SPKI pin`. The Android-Keystore master key is
 * device-bound and never leaves the device, so even if the prefs file is included
 * in a backup it cannot be decrypted elsewhere. An unreadable pin store is kept
 * intact and blocks new TOFU pins: deleting it would let an already accepted host
 * silently pin a changed certificate as though this were first contact.
 */
@Singleton
class EncryptedCertPinStorage @Inject constructor(
    @ApplicationContext private val context: Context
) : CertPinStorage {

    companion object {
        private const val TAG = "EncryptedCertPinStorage"
        private const val PREFS_FILE = "paperless_cert_pins"
        // Dedicated alias — NOT the AndroidX default that SecureTokenStorage uses.
        private const val MASTER_KEY_ALIAS = "paperless_cert_pin_master_key"
    }

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    @Volatile
    private var pinLoadFailed = false

    private fun prefs(): SharedPreferences? {
        cachedPrefs?.let { return it }
        synchronized(this) {
            cachedPrefs?.let { return it }
            return try {
                create().also { cachedPrefs = it }
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to open encrypted pin storage", e)
                null
            }
        }
    }

    private fun create(): SharedPreferences {
        val masterKey = MasterKey.Builder(context, MASTER_KEY_ALIAS)
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

    override fun loadAll(): Map<String, String> {
        return try {
            val preferences = prefs() ?: throw IOException("Encrypted pin storage unavailable")
            preferences.all
                .map { (k, v) ->
                    k to (v as? String ?: throw IOException("Invalid certificate pin entry"))
                }
                .toMap()
        } catch (e: Exception) {
            pinLoadFailed = true
            AppLogger.e(TAG, "Failed to load pins", e)
            emptyMap()
        }
    }

    override fun put(host: String, pin: String) {
        if (pinLoadFailed) throw IOException("Existing certificate pins unavailable")
        try {
            val preferences = prefs() ?: throw IOException("Encrypted pin storage unavailable")
            if (!preferences.edit().putString(host, pin).commit()) {
                throw IOException("Failed to commit certificate pin")
            }
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to persist pin", e)
            throw if (e is IOException) e else IOException("Failed to persist certificate pin", e)
        }
    }

    override fun remove(host: String) {
        try {
            prefs()?.edit()?.remove(host)?.apply()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to remove pin", e)
        }
    }

    override fun clear() {
        try {
            prefs()?.edit()?.clear()?.apply()
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to clear pins", e)
        }
    }
}
