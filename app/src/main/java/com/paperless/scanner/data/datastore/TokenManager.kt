package com.paperless.scanner.data.datastore

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "paperless_settings")

class TokenManager(private val context: Context) {

    companion object {
        private val TOKEN_KEY = stringPreferencesKey("auth_token")
        private val SERVER_URL_KEY = stringPreferencesKey("server_url")
        private val BIOMETRIC_ENABLED_KEY = booleanPreferencesKey("biometric_enabled")
        private val UPLOAD_NOTIFICATIONS_KEY = booleanPreferencesKey("upload_notifications")
        private val UPLOAD_QUALITY_KEY = stringPreferencesKey("upload_quality")
    }

    val token: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[TOKEN_KEY]
    }

    val serverUrl: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[SERVER_URL_KEY]
    }

    val biometricEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[BIOMETRIC_ENABLED_KEY] ?: false
    }

    val uploadNotificationsEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[UPLOAD_NOTIFICATIONS_KEY] ?: true
    }

    val uploadQuality: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[UPLOAD_QUALITY_KEY] ?: "auto"
    }

    suspend fun saveCredentials(serverUrl: String, token: String) {
        context.dataStore.edit { preferences ->
            preferences[SERVER_URL_KEY] = serverUrl.trimEnd('/')
            preferences[TOKEN_KEY] = token
        }
    }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[BIOMETRIC_ENABLED_KEY] = enabled
        }
    }

    suspend fun setUploadNotificationsEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[UPLOAD_NOTIFICATIONS_KEY] = enabled
        }
    }

    suspend fun setUploadQuality(quality: String) {
        context.dataStore.edit { preferences ->
            preferences[UPLOAD_QUALITY_KEY] = quality
        }
    }

    suspend fun clearCredentials() {
        context.dataStore.edit { preferences ->
            preferences.clear()
        }
    }

    fun getTokenSync(): String? = runBlocking {
        context.dataStore.data.first()[TOKEN_KEY]
    }

    fun getServerUrlSync(): String? = runBlocking {
        context.dataStore.data.first()[SERVER_URL_KEY]
    }

    fun isBiometricEnabledSync(): Boolean = runBlocking {
        context.dataStore.data.first()[BIOMETRIC_ENABLED_KEY] ?: false
    }

    fun hasStoredCredentials(): Boolean {
        return getTokenSync() != null && getServerUrlSync() != null
    }
}
