# [plan-02] SecureTokenStorage Failure Taxonomy — distinguish crypto-corruption vs absent vs transient

## Defect

**Current State:** `SecureTokenStorage.getToken()` and `saveToken()` swallow all exceptions into `null` or `false` (lines 118–141 in SecureTokenStorage.kt). When `getOrCreateEncryptedPrefs()` is called during token read/write at open time, any `Exception` — whether a transient network glitch in Android Keystore init, a permanent AES decryption corruption (AEADBadTagException), or Keystore unavailability — triggers `recoverCorruptedStorage()` unconditionally (line 48). Recovery *silently deletes* the stored token and rebuilds the keystore. This masks the failure from callers: a TokenManager or higher-level login flow cannot distinguish "token is absent (user never logged in)" from "token was corrupted and auto-wiped" from "Keystore temporarily unavailable, retry might succeed."

**Why it's broken:** 
1. **Missing Signal Path (#303):** The recovery is synchronous and non-atomic. If crypto-corruption is detected, it must surface to TokenManager.init() and then to the login UI *before* the recovery masks it as Absent. Currently there is no way to thread that signal out; TokenManager.getTokenSync() called during init always sees Absent (null) regardless of the underlying cause.
2. **Swallowed Exceptions (#37):** The `getToken()` and `saveToken()` methods wrap all exceptions in try-catch with no `Result<T>` wrapper or sealed failure taxonomy. A transient Keystore-unavailable error is indistinguishable from permanent corruption, so recovery runs unconditionally instead of only on genuine crypto-corruption (AEADBadTagException / bad-tag-from-decryption). Callers (repositories, ViewModels) cannot implement retry logic because they see only `null` / `false`, not the failure reason.

**Prior Refactors:** PR #158 established the narrow `TokenStorage` interface (getToken/saveToken/clearToken/isMigrationCompleted/setMigrationCompleted); `TokenManagerTest.kt` mocks this interface, enabling unit tests that bypass Android Keystore entirely. This plan leverages that foundation.

## Children

- **#303** — TokenManager.init() and getTokenSync() mask open-time crypto-corruption as Absent (open)
  - **Root symptom:** When `getOrCreateEncryptedPrefs()` encounters AEADBadTagException during `createEncryptedPrefs()`, recovery runs, token is deleted, but caller sees `null` with no indication corruption occurred.
  - **Target slice:** Decrypt-time only (first token read after app start). Fixes the signal path so a fresh login flag can be set if corruption is detected.

- **#37** — getToken/saveToken/recoverCorruptedStorage swallow exceptions, no Result<T> taxonomy (open)
  - **Root symptom:** All exceptions (transient Keystore-unavailable, crypto-corruption, unexpected disk errors) collapse to the same `null` / `false` return, triggering unconditional recovery.
  - **Target scope:** Full atomic recovery (backup snapshot on first open, restore on corruption detection, never wipe unless corruption confirmed).

## Fix sequence

### Phase 1: Signal Threading (#303, smaller, testable)
1. **Add a `@Volatile lastRecoveredCryptoFailure` field** to `SecureTokenStorage` — holds the most recent AEADBadTagException encountered during `createEncryptedPrefs()`.
2. **In `recoverCorruptedStorage()`** (line 77), catch only `AEADBadTagException` (crypto-corruption); transient errors (Keystore unavailable, disk IO) are re-thrown for the caller to handle.
3. **In `getOrCreateEncryptedPrefs()`** (line 38–51), extract the exception type before calling recovery. If it is AEADBadTagException, store it in the `@Volatile` field; otherwise rethrow.
4. **In `TokenManager.init()`** (line 84–136), after calling `secureStorage.isMigrationCompleted()`, poll `lastRecoveredCryptoFailure` once. If set, log a corruption-detection event and mark a flag that the login flow can read to show "authentication records corrupted, please log in again."
5. **Tests:** Robolectric shadow-based unit test that constructs a SecureTokenStorage with a corrupted keystore, calls `getToken()`, verifies that the `@Volatile` field is set to AEADBadTagException and the flag is visible to the next `getToken()` call. (See "Verification Constraint" below.)

### Phase 2: Atomic Recovery + Result Taxonomy (#37, full scope)
1. **Define a sealed `TokenStorageResult<T>`** in the same file:
   ```kotlin
   sealed class TokenStorageResult<out T> {
       data class Present<T>(val value: T) : TokenStorageResult<T>()
       data object Absent : TokenStorageResult<Nothing>()
       data class Failure<T>(val kind: TokenStorageFailureKind, val cause: Throwable?) : TokenStorageResult<T>()
   }

   enum class TokenStorageFailureKind {
       CRYPTO_CORRUPTION,        // AEADBadTagException
       KEYSTORE_UNAVAILABLE,     // temporary, retry might succeed
       IO_ERROR,                 // disk read/write
       UNEXPECTED,               // other exceptions
   }
   ```

2. **Refactor `TokenStorage` interface** to add:
   ```kotlin
   fun getTokenResult(): TokenStorageResult<String>
   fun saveTokenResult(token: String): TokenStorageResult<Unit>
   ```
   (Keep the old `getToken(): String?` and `saveToken(): Boolean` for backward compatibility; deprecated, delegate to the new methods.)

3. **Implement atomic recovery in SecureTokenStorage:**
   - On first `getOrCreateEncryptedPrefs()` call, snapshot the EncryptedSharedPreferences file and master key on disk (backup).
   - On AEADBadTagException, restore from backup (not wipe), attempt to re-read the token.
   - Return `Failure(CRYPTO_CORRUPTION, cause)` if restore fails or the re-read still fails.
   - Return `Failure(KEYSTORE_UNAVAILABLE, cause)` for transient Keystore errors; do NOT recover.
   - Return `Failure(IO_ERROR, cause)` for disk errors; do NOT recover.

4. **Update TokenManager.getTokenSync()** to call `secureStorage.getTokenResult()`, pattern-match on the result, and:
   - Return the token if `Present`.
   - Return null if `Absent`.
   - If `Failure(CRYPTO_CORRUPTION, ...)`, set the corruption flag and log an error event.
   - If `Failure(KEYSTORE_UNAVAILABLE, ...)`, log a warning and return null (caller should retry).
   - If `Failure(IO_ERROR, ...)` or `UNEXPECTED`, log an error and return null.

5. **Tests:**
   - Unit tests with Robolectric shadow that inject a corrupted keystore, verify `getTokenResult()` returns `Failure(CRYPTO_CORRUPTION, ...)`.
   - Unit test that injects a transient Keystore error, verify recovery does NOT run and `getTokenResult()` returns `Failure(KEYSTORE_UNAVAILABLE, ...)`.
   - Device test (or documented manual test) that corrupts the keystore on a real Android device and verifies the app can detect and signal the corruption instead of silently wiping the token.

## Test matrix

| Axis | Case | Required behavior |
|---|---|---|
| **Crypto-corruption (Phase 1)** | `getToken()` called; AEADBadTagException during decrypt | Signal stored in `@Volatile lastRecoveredCryptoFailure` and visible to next `getToken()` call without loss |
| | TokenManager.init() after corruption signal | Corruption flag set, login flow can read it and show "re-authenticate" UI |
| **Crypto-corruption (Phase 2)** | `getTokenResult()` called; AEADBadTagException during decrypt | Returns `Failure(CRYPTO_CORRUPTION, exception)` |
| | Backup/restore after corruption | Token backup exists on disk; restore succeeds; re-read succeeds or returns Failure if token is unrecoverable |
| **Transient Keystore-unavailable** | `getTokenResult()` called; Keystore not available (PKIX_VALIDATION error) | Returns `Failure(KEYSTORE_UNAVAILABLE, exception)`, recovery does NOT run |
| | Caller retries later | Keystore is available, re-read succeeds (or fails with a different Failure kind) |
| **Absent token** | App fresh-started, no token ever saved | `getTokenResult()` returns `Absent` |
| | `saveTokenResult(token)` called | `saveTokenResult()` returns `Present(Unit)` or `Failure(...)` depending on underlying cause; never silently loses the token |
| **IO error (disk)** | `getTokenResult()` called; disk read fails (corrupt EncryptedSharedPreferences file, not crypto) | Returns `Failure(IO_ERROR, exception)`, recovery does NOT run |
| **Unexpected error** | `getTokenResult()` called; unknown exception | Returns `Failure(UNEXPECTED, exception)` for observability |

## Reusable seams

- **`data/api/PaperlessException.kt` (lines 31–306)** — sealed exception hierarchy with @StringRes messageResId and fallback message. `TokenStorageFailureKind` should follow the same pattern: define the enum in SecureTokenStorage.kt or a sibling file, and have TokenManager translate `Failure(CRYPTO_CORRUPTION, ...)` into a `PaperlessException.UnknownError` or a new `PaperlessException.StorageCorrupted` subtype if UI needs to display it.

- **`data/health/ServerHealthMonitor.kt` (lines 58–99)** — sealed class `ServerHealthResult` with Success / NoInternet / Timeout / DnsFailure / ConnectionRefused / SslError / VpnRequired / Error. `TokenStorageResult<T>` should mirror this pattern: sealed class with distinct subtype per outcome, no boolean/null collapse.

- **`app/src/test/.../TokenManagerTest.kt` (lines 38–358)** — Robolectric `@RunWith(RobolectricTestRunner::class)` test harness with mocked `TokenStorage` via MockK. Phase 1 and Phase 2 tests will extend this file; the Robolectric shadow for Android Keystore corruption is available in `org.robolectric.shadows.ShadowKeyStore` (document or create a helper for injecting a corrupted keystore state).

## Out of scope

- **#38 (recoverCorruptedStorage atomicity on background thread):** Deferred to a separate plan. Phase 2 recovery adds the backup/restore snapshot mechanism, but the background-thread atomicity concern (multiple threads calling `getOrCreateEncryptedPrefs()` simultaneously, both triggering recovery) is a separate sync/lock concern.
- **Keystore hardware-back test on real device:** Phase 2 requires device testing for the open-time corruption path (AEADBadTagException can only be reliably triggered on real Android Keystore). Document the device test step in the PR so a human can verify; do NOT assume JVM unit tests can cover it.
- **UI layer translation of KEYSTORE_UNAVAILABLE / IO_ERROR:** TokenManager logs these; UI layer decides whether to show a user-facing error or silently retry. Out of scope for the storage layer.
- **Migration of existing callers to Result<T>:** Phase 1 adds the signal without breaking existing `getToken(): String?` callers. Phase 2 adds `getTokenResult()` and deprecates the old methods, but migration of all callsites (repositories, ViewModels) is a follow-up ticket.

## Verification constraint

**Critical:** The AEADBadTagException path is NOT JVM-unit-testable. Android Keystore is a hardware-backed system service that Robolectric shadows at a basic level (no decryption actually happens in tests; the shadow always succeeds). To verify Phase 1's signal path:
- Either: Use Robolectric's `ShadowKeyStore` to inject a specific exception state (document how if possible; Robolectric may not expose this).
- Or: Write a documented manual device test: provision a test app on a real Android device, save a token, manually corrupt the keystore file (e.g., via `adb shell`), restart the app, verify the corruption signal is set and the login flow shows "re-authenticate."
- Or: Implement a fake `CorruptableTokenStorage` test double that simulates AEADBadTagException on demand, unit-test the signal path against it, and document that the real corruption path requires device testing.

PR #158's existing tests use mockk to mock `TokenStorage`, so callsites (TokenManager, repositories) can be tested without touching Keystore. Phase 1 and Phase 2 tests should follow the same pattern: mock `SecureTokenStorage` as if it has already detected corruption, verify that TokenManager and callers handle the signal correctly.

