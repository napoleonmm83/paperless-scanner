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

    /**
     * Returns and clears the crypto-corruption signal recorded when a confirmed
     * AEADBadTagException forced a destructive recovery (stored token wiped). null
     * when no corruption-recovery happened since the last call. [TokenManager.init]
     * consumes this once so the login flow can tell "corrupted and wiped — please
     * re-authenticate" apart from "never logged in" (#320 Phase 1).
     */
    fun consumeRecoveredCryptoFailure(): Exception? = null
}
