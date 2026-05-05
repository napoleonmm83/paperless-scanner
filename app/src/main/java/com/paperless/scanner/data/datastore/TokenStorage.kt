package com.paperless.scanner.data.datastore

/**
 * Abstraction over secure token persistence used by [TokenManager].
 *
 * Implemented by [SecureTokenStorage] (Android Keystore + EncryptedSharedPreferences).
 * Tests can supply a fake without touching the Android Keystore APIs (which are
 * unavailable in unit tests).
 *
 * Methods are intentionally narrow — only what [TokenManager] actually needs
 * (Interface Segregation).
 */
interface TokenStorage {
    fun getToken(): String?
    fun saveToken(token: String): Boolean
    fun clearToken(): Boolean
    fun isMigrationCompleted(): Boolean
    fun setMigrationCompleted(): Boolean
}
