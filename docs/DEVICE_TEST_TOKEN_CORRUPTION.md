# Manual Device Test: Encrypted Token Storage Corruption Recovery (#320)

The real `AEADBadTagException` decrypt path **cannot be reproduced under JVM/Robolectric**
(the Keystore shadow does not actually encrypt/decrypt). The classification logic and the
signal threading are unit-tested (`SecureTokenStorageClassificationTest`, `TokenManagerTest`);
this manual test covers the end-to-end path on a real device.

**When to run:** before closing #320 (plan-02), and after any change to
`SecureTokenStorage.getOrCreateEncryptedPrefs` / `recoverCorruptedStorage`.

## Prerequisites

- Real device (or emulator with Google Play image), **debug build** installed
  (`./gradlew installDebug` — `run-as` requires a debuggable app)
- Logged-in state: server configured, token stored

## Steps

> Debug builds use the `.debug` applicationId suffix — all commands below target
> `com.paperless.scanner.debug`. (For a release-signed build drop the suffix, but
> `run-as` then requires a rooted device.)

1. **Verify logged-in baseline:** open the app, confirm documents load.
2. **Corrupt the encrypted store IN PLACE.** Do NOT replace the file wholesale — that
   removes the keysets entirely and EncryptedSharedPreferences would just create fresh
   ones (token reads as Absent, no AEAD path). Instead mangle the base64 payloads while
   keeping the XML structure intact, so the AES-GCM-wrapped keysets exist but fail to
   unwrap → `AEADBadTagException` at open time:
   ```bash
   adb shell am force-stop com.paperless.scanner.debug
   adb shell run-as com.paperless.scanner.debug sh -c \
     "sed -i 's/A/B/g; s/Q/R/g' shared_prefs/paperless_secure_prefs.xml"
   ```
3. **Relaunch the app** and capture logs:
   ```bash
   adb logcat -c && adb shell monkey -p com.paperless.scanner.debug 1 && adb logcat | grep -E "SecureTokenStorage|TokenManager"
   ```

## Expected results

| Check | Expected |
|---|---|
| Logcat `SecureTokenStorage` | `Crypto corruption detected, recovering (stored token will be lost)` — NOT the transient-failure message |
| Logcat `TokenManager` | `Encrypted token storage was corrupted and recovered — user must re-authenticate` |
| App state | Starts logged-out (Welcome/Login), no crash |
| Re-login | Succeeds; documents load again |
| `TokenManager.storageCorruptionDetected` | `true` after start (observable once the login flow consumes it; until UI wiring lands, the TokenManager log line above is the proof) |

## Failure modes to watch

- **Crash on launch** → recovery path broken.
- **Silent logged-out state with the transient-failure log line** → classification wrongly
  treats `AEADBadTagException` as transient (would previously have wiped; now must wipe ONLY here).
- **App logged-in with garbage state** → corruption not detected at open time.
