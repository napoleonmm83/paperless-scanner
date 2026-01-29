package com.paperless.scanner.data.datastore

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.paperless.scanner.domain.model.DocumentFilter
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "paperless_settings")

class TokenManager(private val context: Context) {

    companion object {
        private const val TAG = "TokenManager"

        // Legacy key - kept for migration only
        private val TOKEN_KEY_LEGACY = stringPreferencesKey("auth_token")
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

        // Paperless-GPT Preferences
        private val PAPERLESS_GPT_URL_KEY = stringPreferencesKey("paperless_gpt_url")
        private val PAPERLESS_GPT_ENABLED_KEY = booleanPreferencesKey("paperless_gpt_enabled")
        private val PAPERLESS_GPT_OCR_AUTO_KEY = booleanPreferencesKey("paperless_gpt_ocr_auto")

        // Theme Preferences
        private val THEME_MODE_KEY = stringPreferencesKey("theme_mode")

        // Onboarding Preferences
        private val ONBOARDING_COMPLETED_KEY = booleanPreferencesKey("onboarding_completed")

        // SSL Certificate Preferences
        private val ACCEPTED_SSL_HOSTS_KEY = stringPreferencesKey("accepted_ssl_hosts")

        // App-Lock Preferences
        private val APP_LOCK_ENABLED_KEY = booleanPreferencesKey("app_lock_enabled")
        private val APP_LOCK_PASSWORD_HASH_KEY = stringPreferencesKey("app_lock_password_hash")
        private val APP_LOCK_BIOMETRIC_ENABLED_KEY = booleanPreferencesKey("app_lock_biometric_enabled")
        private val APP_LOCK_TIMEOUT_KEY = stringPreferencesKey("app_lock_timeout")
        private val APP_LOCK_FAILED_ATTEMPTS_KEY = intPreferencesKey("app_lock_failed_attempts")
        private val APP_LOCK_LOCKOUT_UNTIL_KEY = longPreferencesKey("app_lock_lockout_until")

        // Document Filter Preferences
        private val DOCUMENT_FILTER_KEY = stringPreferencesKey("document_filter")

        // Trash Pending Delete Preferences (survives AppLock)
        private val TRASH_PENDING_DELETES_KEY = stringPreferencesKey("trash_pending_deletes")

        // Server Configuration Detection
        private val SERVER_USES_CLOUDFLARE_KEY = booleanPreferencesKey("server_uses_cloudflare")

        // HTTP Fallback Acceptance (user accepted insecure connection for these domains)
        private val ACCEPTED_HTTP_HOSTS_KEY = stringPreferencesKey("accepted_http_hosts")
    }

    /**
     * Encrypted storage for sensitive authentication data (token).
     * Uses Android Keystore backed AES256 encryption.
     */
    private val secureStorage: SecureTokenStorage by lazy {
        SecureTokenStorage(context)
    }

    init {
        // Perform migration from plaintext DataStore to encrypted storage
        migrateTokenToSecureStorage()
    }

    /**
     * Migrates token from plaintext DataStore to EncryptedSharedPreferences.
     * This is a one-time migration that runs on first access after update.
     *
     * Migration flow:
     * 1. Check if migration already completed
     * 2. Check if plaintext token exists in DataStore
     * 3. Copy token to encrypted storage
     * 4. Remove plaintext token from DataStore
     * 5. Mark migration as completed
     */
    private fun migrateTokenToSecureStorage() {
        // Skip if already migrated
        if (secureStorage.isMigrationCompleted()) {
            return
        }

        try {
            runBlocking {
                val plaintextToken = context.dataStore.data.first()[TOKEN_KEY_LEGACY]

                if (plaintextToken != null) {
                    Log.i(TAG, "Migrating token from plaintext to encrypted storage...")

                    // Save to encrypted storage
                    val saved = secureStorage.saveToken(plaintextToken)
                    if (saved) {
                        // Remove from plaintext storage
                        context.dataStore.edit { preferences ->
                            preferences.remove(TOKEN_KEY_LEGACY)
                        }
                        Log.i(TAG, "Token migration completed successfully")
                    } else {
                        Log.e(TAG, "Failed to save token to encrypted storage")
                        return@runBlocking
                    }
                } else {
                    Log.d(TAG, "No plaintext token found - nothing to migrate")
                }

                // Mark migration as completed (even if no token existed)
                secureStorage.setMigrationCompleted()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Token migration failed", e)
            // Don't mark as completed - will retry on next app start
        }
    }

    /**
     * Token flow - reads from encrypted storage.
     * Emits the current token value and updates when token changes.
     */
    val token: Flow<String?> = flow {
        emit(secureStorage.getToken())
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

    /** Whether onboarding flow has been completed */
    val onboardingCompleted: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[ONBOARDING_COMPLETED_KEY] ?: false // Default: not completed
    }

    // Paperless-GPT Settings

    /** Paperless-GPT service URL (optional, null = use Paperless-ngx URL) */
    val paperlessGptUrl: Flow<String?> = context.dataStore.data.map { preferences ->
        preferences[PAPERLESS_GPT_URL_KEY]
    }

    /** Whether Paperless-GPT features are enabled */
    val paperlessGptEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PAPERLESS_GPT_ENABLED_KEY] ?: false // Default: disabled
    }

    /** Whether automatic OCR improvement is enabled for low-quality scans */
    val paperlessGptOcrAutoEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[PAPERLESS_GPT_OCR_AUTO_KEY] ?: true // Default: enabled (if Paperless-GPT enabled)
    }

    // App-Lock Settings

    /** Whether app-lock is enabled */
    val appLockEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[APP_LOCK_ENABLED_KEY] ?: false // Default: disabled
    }

    /** Whether biometric unlock is enabled for app-lock */
    val appLockBiometricEnabled: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[APP_LOCK_BIOMETRIC_ENABLED_KEY] ?: false // Default: disabled
    }

    suspend fun saveCredentials(serverUrl: String, token: String) {
        // Save token to encrypted storage
        val tokenSaved = secureStorage.saveToken(token)
        if (!tokenSaved) {
            Log.e(TAG, "Failed to save token to encrypted storage")
        }

        // Save server URL to DataStore (not sensitive)
        context.dataStore.edit { preferences ->
            preferences[SERVER_URL_KEY] = serverUrl.trimEnd('/')
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
        // Clear encrypted token storage
        secureStorage.clearToken()

        // Clear DataStore preferences
        context.dataStore.edit { preferences ->
            preferences.clear()
        }
        // Note: Cloudflare detection flag is also cleared since we clear ALL preferences
    }

    /**
     * Get token synchronously from encrypted storage.
     * Safe to call from any thread (blocking).
     */
    fun getTokenSync(): String? {
        return secureStorage.getToken()
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

    /**
     * Check if AI suggestions are enabled (sync version).
     */
    fun getAiSuggestionsEnabledSync(): Boolean = runBlocking {
        context.dataStore.data.first()[AI_SUGGESTIONS_ENABLED_KEY] ?: true
    }

    /**
     * Check if AI new tags are enabled (sync version).
     */
    fun getAiNewTagsEnabledSync(): Boolean = runBlocking {
        context.dataStore.data.first()[AI_NEW_TAGS_ENABLED_KEY] ?: true
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

    // Onboarding Settings

    suspend fun setOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[ONBOARDING_COMPLETED_KEY] = completed
        }
    }

    // Paperless-GPT Settings

    suspend fun setPaperlessGptUrl(url: String?) {
        context.dataStore.edit { preferences ->
            if (url.isNullOrBlank()) {
                preferences.remove(PAPERLESS_GPT_URL_KEY)
            } else {
                preferences[PAPERLESS_GPT_URL_KEY] = url.trimEnd('/')
            }
        }
    }

    suspend fun setPaperlessGptEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PAPERLESS_GPT_ENABLED_KEY] = enabled
        }
    }

    suspend fun setPaperlessGptOcrAutoEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[PAPERLESS_GPT_OCR_AUTO_KEY] = enabled
        }
    }

    /** Get Paperless-GPT URL synchronously (null = use Paperless-ngx URL) */
    fun getPaperlessGptUrlSync(): String? = runBlocking {
        context.dataStore.data.first()[PAPERLESS_GPT_URL_KEY]
    }

    /** Get Paperless-GPT enabled state synchronously */
    fun isPaperlessGptEnabledSync(): Boolean = runBlocking {
        context.dataStore.data.first()[PAPERLESS_GPT_ENABLED_KEY] ?: false
    }

    /** Get Paperless-GPT OCR auto enabled state synchronously */
    fun isPaperlessGptOcrAutoEnabledSync(): Boolean = runBlocking {
        context.dataStore.data.first()[PAPERLESS_GPT_OCR_AUTO_KEY] ?: true
    }

    // SSL Certificate Settings

    /** Check if a host has been accepted for self-signed SSL certificates */
    fun isHostAcceptedForSsl(host: String): Boolean = runBlocking {
        val acceptedHosts = context.dataStore.data.first()[ACCEPTED_SSL_HOSTS_KEY] ?: ""
        acceptedHosts.split(",").map { it.trim() }.contains(host)
    }

    /** Accept a host for self-signed SSL certificates */
    suspend fun acceptSslForHost(host: String) {
        context.dataStore.edit { preferences ->
            val currentHosts = preferences[ACCEPTED_SSL_HOSTS_KEY] ?: ""
            val hostList = currentHosts.split(",").map { it.trim() }.filter { it.isNotBlank() }.toMutableList()
            if (!hostList.contains(host)) {
                hostList.add(host)
            }
            preferences[ACCEPTED_SSL_HOSTS_KEY] = hostList.joinToString(",")
        }
    }

    /** Remove a host from accepted SSL hosts */
    suspend fun removeAcceptedSslHost(host: String) {
        context.dataStore.edit { preferences ->
            val currentHosts = preferences[ACCEPTED_SSL_HOSTS_KEY] ?: ""
            val hostList = currentHosts.split(",").map { it.trim() }.filter { it.isNotBlank() && it != host }
            preferences[ACCEPTED_SSL_HOSTS_KEY] = hostList.joinToString(",")
        }
    }

    /** Get all accepted SSL hosts */
    fun getAcceptedSslHosts(): List<String> = runBlocking {
        val hosts = context.dataStore.data.first()[ACCEPTED_SSL_HOSTS_KEY] ?: ""
        hosts.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }

    // App-Lock Settings

    /** Check if app-lock is enabled (returns Flow for reactive access) */
    fun isAppLockEnabled(): Flow<Boolean> = appLockEnabled

    /** Check if app-lock is enabled (sync version) */
    fun isAppLockEnabledSync(): Boolean = runBlocking {
        context.dataStore.data.first()[APP_LOCK_ENABLED_KEY] ?: false
    }

    /** Set app-lock enabled state */
    suspend fun setAppLockEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[APP_LOCK_ENABLED_KEY] = enabled
        }
    }

    /** Get app-lock password hash (null if not set) */
    suspend fun getAppLockPasswordHash(): String? {
        return context.dataStore.data.first()[APP_LOCK_PASSWORD_HASH_KEY]
    }

    /** Set app-lock password hash (null to clear) */
    suspend fun setAppLockPassword(passwordHash: String?) {
        context.dataStore.edit { preferences ->
            if (passwordHash == null) {
                preferences.remove(APP_LOCK_PASSWORD_HASH_KEY)
            } else {
                preferences[APP_LOCK_PASSWORD_HASH_KEY] = passwordHash
            }
        }
    }

    /** Check if biometric unlock is enabled for app-lock */
    fun isAppLockBiometricEnabled(): Boolean = runBlocking {
        context.dataStore.data.first()[APP_LOCK_BIOMETRIC_ENABLED_KEY] ?: false
    }

    /** Check if biometric unlock is enabled for app-lock (Flow for reactive access) */
    fun isAppLockBiometricEnabledFlow(): Flow<Boolean> {
        return context.dataStore.data.map { preferences ->
            preferences[APP_LOCK_BIOMETRIC_ENABLED_KEY] ?: false
        }
    }

    /** Set biometric unlock enabled state for app-lock */
    suspend fun setAppLockBiometricEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[APP_LOCK_BIOMETRIC_ENABLED_KEY] = enabled
        }
    }

    /** Get app-lock timeout */
    suspend fun getAppLockTimeout(): com.paperless.scanner.util.AppLockTimeout {
        val timeoutString = context.dataStore.data.first()[APP_LOCK_TIMEOUT_KEY]
        return try {
            com.paperless.scanner.util.AppLockTimeout.valueOf(timeoutString ?: "IMMEDIATE")
        } catch (e: IllegalArgumentException) {
            com.paperless.scanner.util.AppLockTimeout.IMMEDIATE // Default fallback
        }
    }

    /** Get app-lock timeout (Flow for reactive access) */
    fun getAppLockTimeoutFlow(): Flow<com.paperless.scanner.util.AppLockTimeout> {
        return context.dataStore.data.map { preferences ->
            val timeoutString = preferences[APP_LOCK_TIMEOUT_KEY]
            try {
                com.paperless.scanner.util.AppLockTimeout.valueOf(timeoutString ?: "IMMEDIATE")
            } catch (e: IllegalArgumentException) {
                com.paperless.scanner.util.AppLockTimeout.IMMEDIATE // Default fallback
            }
        }
    }

    /** Set app-lock timeout */
    suspend fun setAppLockTimeout(timeout: com.paperless.scanner.util.AppLockTimeout) {
        context.dataStore.edit { preferences ->
            preferences[APP_LOCK_TIMEOUT_KEY] = timeout.name
        }
    }

    // App-Lock Lockout Persistence (survives app restart)

    /** Get persisted app-lock failed attempts count */
    fun getAppLockFailedAttemptsSync(): Int = runBlocking {
        context.dataStore.data.first()[APP_LOCK_FAILED_ATTEMPTS_KEY] ?: 0
    }

    /** Get persisted app-lock lockout until timestamp */
    fun getAppLockLockoutUntilSync(): Long = runBlocking {
        context.dataStore.data.first()[APP_LOCK_LOCKOUT_UNTIL_KEY] ?: 0L
    }

    /** Persist app-lock lockout state */
    suspend fun setAppLockLockoutState(failedAttempts: Int, lockoutUntil: Long) {
        context.dataStore.edit { preferences ->
            preferences[APP_LOCK_FAILED_ATTEMPTS_KEY] = failedAttempts
            preferences[APP_LOCK_LOCKOUT_UNTIL_KEY] = lockoutUntil
        }
    }

    /** Clear app-lock lockout state (on successful unlock or disable) */
    suspend fun clearAppLockLockoutState() {
        context.dataStore.edit { preferences ->
            preferences[APP_LOCK_FAILED_ATTEMPTS_KEY] = 0
            preferences[APP_LOCK_LOCKOUT_UNTIL_KEY] = 0L
        }
    }

    // Document Filter Settings

    /**
     * Reactive Flow for document filter state.
     * Emits DocumentFilter.empty() if no filter is stored.
     */
    val documentFilter: Flow<DocumentFilter> = context.dataStore.data.map { preferences ->
        val filterJson = preferences[DOCUMENT_FILTER_KEY]
        DocumentFilter.fromJson(filterJson)
    }

    /**
     * Save document filter to persistent storage.
     * Filter is serialized to JSON for compact storage.
     */
    suspend fun saveDocumentFilter(filter: DocumentFilter) {
        context.dataStore.edit { preferences ->
            if (filter.isEmpty()) {
                preferences.remove(DOCUMENT_FILTER_KEY)
            } else {
                preferences[DOCUMENT_FILTER_KEY] = filter.toJson()
            }
        }
    }

    /**
     * Clear stored document filter.
     */
    suspend fun clearDocumentFilter() {
        context.dataStore.edit { preferences ->
            preferences.remove(DOCUMENT_FILTER_KEY)
        }
    }

    /**
     * Get document filter synchronously (for ViewModel initialization).
     * Returns DocumentFilter.empty() if no filter is stored.
     */
    fun getDocumentFilterSync(): DocumentFilter = runBlocking {
        val filterJson = context.dataStore.data.first()[DOCUMENT_FILTER_KEY]
        DocumentFilter.fromJson(filterJson)
    }

    // Trash Pending Delete Settings (survives AppLock back stack clear)

    /**
     * Save pending trash deletes to DataStore.
     * Format: "docId:startTime,docId:startTime,..."
     * This data survives AppLock because DataStore is independent of NavBackStackEntry.
     */
    suspend fun savePendingTrashDeletes(pendingDeletes: String?) {
        context.dataStore.edit { preferences ->
            if (pendingDeletes.isNullOrBlank()) {
                preferences.remove(TRASH_PENDING_DELETES_KEY)
            } else {
                preferences[TRASH_PENDING_DELETES_KEY] = pendingDeletes
            }
        }
    }

    /**
     * Get pending trash deletes synchronously (for ViewModel initialization).
     * Returns null if no pending deletes are stored.
     */
    fun getPendingTrashDeletesSync(): String? = runBlocking {
        context.dataStore.data.first()[TRASH_PENDING_DELETES_KEY]
    }

    /**
     * Clear pending trash deletes.
     */
    suspend fun clearPendingTrashDeletes() {
        context.dataStore.edit { preferences ->
            preferences.remove(TRASH_PENDING_DELETES_KEY)
        }
    }

    // Server Configuration Detection

    /**
     * Whether the server uses Cloudflare (detected via cf-ray header).
     * This information is used to show timeout warnings for large uploads.
     */
    val serverUsesCloudflare: Flow<Boolean> = context.dataStore.data.map { preferences ->
        preferences[SERVER_USES_CLOUDFLARE_KEY] ?: false
    }

    /**
     * Set whether server uses Cloudflare (detected automatically during health checks).
     */
    suspend fun setServerUsesCloudflare(usesCloudflare: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[SERVER_USES_CLOUDFLARE_KEY] = usesCloudflare
        }
    }

    /**
     * Get Cloudflare detection state synchronously.
     */
    fun isServerUsingCloudflareSync(): Boolean = runBlocking {
        context.dataStore.data.first()[SERVER_USES_CLOUDFLARE_KEY] ?: false
    }

    // HTTP Fallback Acceptance Settings
    // These track domains where user explicitly accepted insecure HTTP connection

    /** Check if a host has been accepted for insecure HTTP connections */
    fun isHostAcceptedForHttp(host: String): Boolean = runBlocking {
        val acceptedHosts = context.dataStore.data.first()[ACCEPTED_HTTP_HOSTS_KEY] ?: ""
        acceptedHosts.split(",").map { it.trim().lowercase() }.contains(host.lowercase())
    }

    /** Accept a host for insecure HTTP connections (user acknowledged warning) */
    suspend fun acceptHttpForHost(host: String) {
        context.dataStore.edit { preferences ->
            val currentHosts = preferences[ACCEPTED_HTTP_HOSTS_KEY] ?: ""
            val hostList = currentHosts.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }.toMutableList()
            if (!hostList.contains(host.lowercase())) {
                hostList.add(host.lowercase())
            }
            preferences[ACCEPTED_HTTP_HOSTS_KEY] = hostList.joinToString(",")
        }
    }

    /** Remove a host from accepted HTTP hosts */
    suspend fun removeAcceptedHttpHost(host: String) {
        context.dataStore.edit { preferences ->
            val currentHosts = preferences[ACCEPTED_HTTP_HOSTS_KEY] ?: ""
            val hostList = currentHosts.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() && it.lowercase() != host.lowercase() }
            preferences[ACCEPTED_HTTP_HOSTS_KEY] = hostList.joinToString(",")
        }
    }

    /** Get all accepted HTTP hosts */
    fun getAcceptedHttpHosts(): List<String> = runBlocking {
        val hosts = context.dataStore.data.first()[ACCEPTED_HTTP_HOSTS_KEY] ?: ""
        hosts.split(",").map { it.trim() }.filter { it.isNotBlank() }
    }
}
