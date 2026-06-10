# Manual Device Test: Encrypted Token Storage Corruption Recovery (#320, #359)

The real `AEADBadTagException` decrypt path **cannot be reproduced under JVM/Robolectric**
(the Keystore shadow does not actually encrypt/decrypt). The classification logic and the
signal threading are unit-tested (`SecureTokenStorageClassificationTest`, `TokenManagerTest`);
the SharedPreferences cache-eviction mechanics of the restore are unit-tested
(`SecureTokenStorageRestoreEvictionTest`, #359); this manual test covers the end-to-end
path on a real device.

**When to run:** before closing #320 (plan-02), and after any change to
`SecureTokenStorage.getOrCreateEncryptedPrefs` / `restorePrefsFileFromBackup` /
`recoverCorruptedStorage`.

## Prerequisites

- Real device (or emulator with Google Play image), **debug build** installed
  (`./gradlew installDebug` — `run-as` requires a debuggable app)
- Logged-in state: server configured, token stored

## Common setup

> Debug builds use the `.debug` applicationId suffix — all commands below target
> `com.paperless.scanner.debug`. (For a release-signed build drop the suffix, but
> `run-as` then requires a rooted device.)

1. **Verify logged-in baseline:** open the app, confirm documents load.
2. **Verify the ciphertext snapshot exists** (it is seeded on login/first read):
   ```bash
   adb shell run-as com.paperless.scanner.debug ls no_backup/
   # expect: paperless_secure_prefs.backup.xml
   ```
3. **Corrupt the encrypted store IN PLACE.** Do NOT replace the file wholesale — that
   removes the keysets entirely and EncryptedSharedPreferences would just create fresh
   ones (token reads as Absent, no AEAD path). Instead mangle the base64/hex payloads
   while keeping the XML structure intact, so the AES-GCM-wrapped keysets exist but fail
   to unwrap → `AEADBadTagException` at open time. NOTE: the keyset values are
   **lowercase hex** — uppercase-only substitutions miss them:
   ```bash
   adb shell am force-stop com.paperless.scanner.debug
   adb shell run-as com.paperless.scanner.debug sh -c \
     "sed -i 's/a/b/g; s/3/4/g' shared_prefs/paperless_secure_prefs.xml"
   ```
4. **Relaunch the app** and capture logs:
   ```bash
   adb logcat -c && adb shell monkey -p com.paperless.scanner.debug 1 && adb logcat | grep -E "SecureTokenStorage|TokenManager"
   ```

## Scenario A — restore success (#359, primary)

Corrupt ONLY the prefs file (common setup as-is, snapshot left intact).

| Check | Expected |
|---|---|
| Logcat `SecureTokenStorage` | `Crypto corruption detected on open`, then `Restored encrypted storage from backup — token preserved` |
| Must NOT appear | `Recovering by wipe (stored token will be lost)`, `Backup restore did not open either`, any `TokenManager` corruption line |
| App state | **Still logged in**, documents load, no re-auth prompt, no crash |

## Scenario B — wipe when no backup exists

Common setup, plus delete the snapshot before relaunch:
```bash
adb shell run-as com.paperless.scanner.debug rm no_backup/paperless_secure_prefs.backup.xml
```

| Check | Expected |
|---|---|
| Logcat `SecureTokenStorage` | `Crypto corruption detected on open`, then `Recovering by wipe (stored token will be lost)`, then `Successfully recovered encrypted storage - user must re-authenticate` |
| Logcat `TokenManager` | `Encrypted token storage was corrupted and recovered — user must re-authenticate` |
| App state | Starts logged-out (Welcome/Login), no crash |
| Re-login | Succeeds; documents load again (this also re-seeds the snapshot) |

## Scenario C — wipe when the backup is ALSO corrupt (master-key fall-through)

Common setup, plus mangle the snapshot the same way:
```bash
adb shell run-as com.paperless.scanner.debug sh -c \
  "sed -i 's/a/b/g; s/3/4/g' no_backup/paperless_secure_prefs.backup.xml"
```

| Check | Expected |
|---|---|
| Logcat `SecureTokenStorage` | `Crypto corruption detected on open`, then `Backup restore did not open either, falling back to wipe`, then the wipe lines as in Scenario B |
| App state | Logged-out, no crash; re-login succeeds |

Run order A → B → C on the same install is fine: A leaves a valid logged-in state;
B/C end in re-login, which refreshes the snapshot via `saveTokenResult`.

## Failure modes to watch

- **Crash on launch** → recovery path broken.
- **Silent logged-out state with the transient-failure log line** → classification wrongly
  treats `AEADBadTagException` as transient (would previously have wiped; now must wipe ONLY here).
- **App logged-in with garbage state** → corruption not detected at open time.
- **Scenario A ends logged-out with `Backup restore did not open either`** → the
  SharedPreferences cache eviction regressed (#359: the restore MUST go through
  `deleteSharedPreferences`, a disk-only overwrite is invisible to the process cache).
- **Scenario A restore log appears but the app is logged out** → crash-window between
  evict and rename (silent-Absent, accepted two-syscall window) or snapshot seeding gap.
- **Second corruption immediately after a restore wipes** → check the snapshot was
  refreshed after the restore (`saveTokenResult`/`seedBackupIfMissing`).
