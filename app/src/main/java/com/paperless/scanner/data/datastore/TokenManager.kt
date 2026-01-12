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
        private val ANALYTICS_CONSENT_KEY = booleanPreferencesKey("analytics_consent")
        private val ANALYTICS_CONSENT_ASKED_KEY = booleanPreferencesKey("analytics_consent_asked")

        // AI Feature Preferences
        private val AI_SUGGESTIONS_ENABLED_KEY = booleanPreferencesKey("ai_suggestions_enabled")
        private val AI_NEW_TAGS_ENABLED_KEY = booleanPreferencesKey("ai_new_tags_enabled")
        private val AI_WIFI_ONLY_KEY = booleanPreferencesKey("ai_wifi_only")
        private val AI_DEBUG_MODE_KEY = booleanPreferencesKey("ai_debug_mode")

        // Theme Preferences
        private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
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

    /** Whether user has granted analytics consent (null = not asked yet) */
    val analyticsConsent: Flow<Boolean?> = context.dataStore.data.map { preferences ->
        if (preferences[ANALYTICS_CONSENT_ASKED_KEY] == true) {
            preferences[ANALYTICS_CONSENT_KEY] ?: false
        } else {
            null // Not asked yet
        }
    }

    /** Whether consent dialog has been shown */
    val analyticsConsentAsked: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[ANALYTICS_CONSENT_ASKED_KEY] ?: false
    }

    /** Whether AI suggestions are enabled (user preference) */
    val aiSuggestionsEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[AI_SUGGESTIONS_ENABLED_KEY] ?: true // Default: enabled
    }

    /** Whether AI can suggest new tags that don't exist yet */
    val aiNewTagsEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[AI_NEW_TAGS_ENABLED_KEY] ?: true // Default: enabled
    }

    /** Whether AI features should only work on WiFi */
    val aiWifiOnly: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[AI_WIFI_ONLY_KEY] ?: false // Default: allow mobile data
    }

    /**
     * Whether AI debug mode is enabled (unlocked by 7x tap on version).
     * This allows testers to access AI features in release builds.
     */
    val aiDebugModeEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[AI_DEBUG_MODE_KEY] ?: false
    }

    /** Theme mode preference (system, light, dark) */
    val themeMode: Flow<String> = context.dataStore.data.map { preferences ->
        preferences[THEME_MODE_KEY] ?: "system" // Default: follow system
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

    /** Set analytics consent (also marks consent as asked) */
    suspend fun setAnalyticsConsent(granted: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[ANALYTICS_CONSENT_KEY] = granted
            preferences[ANALYTICS_CONSENT_ASKED_KEY] = true
        }
    }

    /** Check if analytics consent was granted (sync version for initialization) */
    fun isAnalyticsConsentGrantedSync(): Boolean = runBlocking {
        val asked = context.dataStore.data.first()[ANALYTICS_CONSENT_ASKED_KEY] ?: false
        if (asked) {
            context.dataStore.data.first()[ANALYTICS_CONSENT_KEY] ?: false
        } else {
            false
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

    // AI Feature Settings

    suspend fun setAiSuggestionsEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AI_SUGGESTIONS_ENABLED_KEY] = enabled
        }
    }

    suspend fun setAiNewTagsEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AI_NEW_TAGS_ENABLED_KEY] = enabled
        }
    }

    suspend fun setAiWifiOnly(wifiOnly: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AI_WIFI_ONLY_KEY] = wifiOnly
        }
    }

    /**
     * Enable or disable AI debug mode (unlocked by 7x tap on version).
     */
    suspend fun setAiDebugModeEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[AI_DEBUG_MODE_KEY] = enabled
        }
    }

    /**
     * Check if AI debug mode is enabled (sync version).
     */
    fun isAiDebugModeEnabledSync(): Boolean = runBlocking {
        context.dataStore.data.first()[AI_DEBUG_MODE_KEY] ?: false
    }

    // Theme Settings

    suspend fun setThemeMode(mode: String) {
        context.dataStore.edit { preferences ->
            preferences[THEME_MODE_KEY] = mode
        }
    }

    /** Get theme mode synchronously (for app initialization) */
    fun getThemeModeSync(): String = runBlocking {
        context.dataStore.data.first()[THEME_MODE_KEY] ?: "system"
    }
}
