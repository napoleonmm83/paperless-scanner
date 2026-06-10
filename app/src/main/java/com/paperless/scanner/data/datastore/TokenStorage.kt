package com.paperless.scanner.data.datastore

/**
 * Classified outcome of a secure-storage operation (#320 Phase 2). Mirrors the
 * sealed-result pattern of ServerHealthResult: callers can distinguish "no token
 * stored" from "storage failed" — and WHICH way it failed — instead of collapsing
 * everything to null/false.
 */
sealed class TokenStorageResult<out T> {
    /** The operation succeeded and produced [value]. */
    data class Present<T>(val value: T) : TokenStorageResult<T>()

    /** The storage is healthy but holds no token (user never logged in / logged out). */
    data object Absent : TokenStorageResult<Nothing>()

    /** The operation failed; [kind] says whether retrying or re-authenticating helps. */
    data class Failure(
        val kind: TokenStorageFailureKind,
        val cause: Throwable? = null,
    ) : TokenStorageResult<Nothing>()
}

/**
 * Why a secure-storage operation failed (#320 Phase 2).
 */
enum class TokenStorageFailureKind {
    /** Confirmed AEADBadTagException — the ciphertext/keyset is corrupt; data was wiped or restored. */
    CRYPTO_CORRUPTION,

    /** Android Keystore temporarily unavailable — retry may succeed, data is intact. */
    KEYSTORE_UNAVAILABLE,

    /** Disk read/write error — retry may succeed, data is intact. */
    IO_ERROR,

    /** Anything else — data left intact, no destructive recovery. */
    UNEXPECTED,
}

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
    @Deprecated(
        "Collapses Absent and Failure to null — use getTokenResult() (#320)",
        ReplaceWith("getTokenResult()"),
    )
    fun getToken(): String?

    @Deprecated(
        "Collapses all failure kinds to false — use saveTokenResult() (#320)",
        ReplaceWith("saveTokenResult(token)"),
    )
    fun saveToken(token: String): Boolean

    fun clearToken(): Boolean
    fun isMigrationCompleted(): Boolean
    fun setMigrationCompleted(): Boolean

    /**
     * Classified read (#320 Phase 2). Default delegates to the legacy [getToken] so
     * simple fakes keep working; [SecureTokenStorage] overrides with the real taxonomy.
     */
    @Suppress("DEPRECATION")
    fun getTokenResult(): TokenStorageResult<String> = when (val token = getToken()) {
        null -> TokenStorageResult.Absent
        else -> TokenStorageResult.Present(token)
    }

    /**
     * Classified write (#320 Phase 2). Default delegates to the legacy [saveToken];
     * [SecureTokenStorage] overrides with the real taxonomy.
     */
    @Suppress("DEPRECATION")
    fun saveTokenResult(token: String): TokenStorageResult<Unit> =
        if (saveToken(token)) {
            TokenStorageResult.Present(Unit)
        } else {
            TokenStorageResult.Failure(TokenStorageFailureKind.UNEXPECTED)
        }

    /**
     * Returns and clears the crypto-corruption signal recorded when a confirmed
     * AEADBadTagException forced a destructive recovery (stored token wiped). null
     * when no corruption-recovery happened since the last call. [TokenManager.init]
     * consumes this once so the login flow can tell "corrupted and wiped — please
     * re-authenticate" apart from "never logged in" (#320 Phase 1).
     */
    fun consumeRecoveredCryptoFailure(): Exception? = null
}
