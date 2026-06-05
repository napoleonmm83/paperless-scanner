# [plan-07] Build/CI & Release Tooling Hardening — pre-push origin/main-centric + ProGuard iText narrowing

## Defect

The pre-push hook (`/.githooks/pre-push`) is **ref-blind**: it hardcodes `origin/main` as the rebase target (lines 9, 14, 24, 42) regardless of which branch is being pushed. This breaks feature-branch workflows where developers push to `develop` or topic branches—the hook will rebase against the wrong upstream, creating merge conflicts or silent logic corruption. Additionally, the keyless-Lazy-item guard (lines 202–258 in `scripts/validate-ci.sh`) is duplicated inline and not reusable; extracting it into a standalone script would allow both the pre-commit local validation and the CI pipeline to invoke the same check atomically, eliminating logic drift.

The ProGuard rules (`app/proguard-rules.pro`, lines 46–61) over-keep all `com.itextpdf.**` classes and interfaces with no discrimination. iText is a large PDF library; keeping entire packages prevents meaningful code shrinking. Real entry points (method refs used by the app's PDF generation code) should be identified via `-printusage` and `-printseeds`, and APK size should be measured before/after to confirm actual savings.

Current reality: PR #300 (merged 2026-06-02, SHA 63a0ad2ee) added the pre-push hook and the keyless-Lazy check, but left both issues unfixed to unblock the main validation pipeline. Issue #302 tracks the pre-push ref-blindness; issue #145 tracks the ProGuard over-keep.

## Children

- #302 — pre-push hook rebases feature branches onto main; should read pushed ref from stdin and only rebase main ([open](https://github.com/YOUR_ORG/paperless-client/issues/302) — **READY**)
- #145 — ProGuard over-keeps com.itextpdf.** entirely; narrow to real entry points + measure APK shrink ([open](https://github.com/YOUR_ORG/paperless-client/issues/145) — **deferred-device-verify**)

## Fix sequence

### Slice #302: Pre-Push Hook Origin/Main-Centric Guard (READY)

1. **Extract keyless-Lazy check into standalone script** (`scripts/check-lazy-keys.sh`)
   - Copy lines 202–258 from `scripts/validate-ci.sh` into new file
   - Remove echo statements; keep return code and stdout listing (for CI parseable output)
   - Wrap in `main() { ... }` and call at script exit
   - Verify exitcode: 0 = no keyless found, 1 = keyless found

2. **Refactor `scripts/validate-ci.sh` to source the extracted script**
   - Replace inline check (lines 202–258) with: `./scripts/check-lazy-keys.sh || ERRORS=$((ERRORS + 1))`
   - Ensure no duplication; single source of truth for keyless detection

3. **Add `check-lazy-keys.sh` invocation to `.github/workflows/android-ci-optimized.yml`**
   - In `validate` job (after hardcoded-strings check, before XML validation)
   - Run: `chmod +x scripts/*.sh && ./scripts/check-lazy-keys.sh`
   - Mark as critical (fail the step if keyless found)

4. **Fix `.githooks/pre-push` to read pushed ref from stdin**
   - Read stdin line: `read -r local_ref local_sha remote_ref remote_sha < <(cat)`
   - Extract branch name: `PUSH_BRANCH="${local_ref#refs/heads/}"`
   - Only apply rebase logic if `PUSH_BRANCH == "main"`
   - For feature branches (develop, topic/*), skip rebase and proceed directly to push
   - Document the git pre-push hook protocol in a comment (hook receives: `<local-ref> <local-sha> <remote-ref> <remote-sha>`)

5. **Verify against local and CI gates**
   - Push to `develop` branch locally; verify pre-push does not rebase
   - Push to `main` branch locally; verify pre-push rebases against `origin/main`
   - Run `validate-ci.sh` locally; confirm keyless check still passes
   - Merge; GitHub Actions validates `check-lazy-keys.sh` in workflow

### Slice #145: ProGuard iText Narrowing (DEFERRED — device-verify)

1. **Generate ProGuard usage report**
   - Build release APK with current rules: `./gradlew assembleRelease`
   - Enable ProGuard reporting: add `-printusage build/proguard-usage.txt -printseeds build/proguard-seeds.txt` to `app/build.gradle.kts` `proguardFiles` or inline `-printusage` flag
   - Inspect `build/proguard-usage.txt` for iText classes actually kept vs. discarded

2. **Identify real entry points for iText**
   - Grep codebase for direct iText imports: `grep -r "import com.itextpdf" app/src/main/java`
   - List only classes/methods used (cross-reference with `-printseeds` output)
   - Replace lines 46–61 with targeted `-keep` rules (e.g., `-keep class com.itextpdf.kernel.pdf.PdfWriter { *; }` instead of `com.itextpdf.**`)

3. **Measure APK size delta**
   - Before: current `app/build/outputs/apk/release/app-release.apk` size
   - Apply narrowed rules
   - After: rebuild and compare APK size
   - Record in commit message (expected 5–15% savings if iText is large)

4. **On-device PDF smoke test (manual)**
   - Install APK on real device
   - Generate PDF from app UI
   - Verify PDF is valid, images/text render correctly
   - No crashes or silent corruption

5. **Mark as deferred in doc**
   - Requires manual device testing; not CI-automatable
   - Defer to next planning cycle or assign to release QA

## Test matrix

| Axis | Case | Required behavior |
|---|---|---|
| **#302: Ref parsing** | Push to `main` | Hook reads stdin, extracts `main`, applies rebase logic |
| | Push to `develop` | Hook reads stdin, extracts `develop`, skips rebase, proceeds to push |
| | Push to `feature/xyz` | Hook reads stdin, extracts `feature/xyz`, skips rebase, proceeds to push |
| **#302: Keyless check** | No keyless Lazy slots | `scripts/validate-ci.sh` exits 0, `check-lazy-keys.sh` exits 0 |
| | Keyless Lazy found | `validate-ci.sh` and `check-lazy-keys.sh` both exit 1, list offenders |
| **#302: CI workflow** | GitHub Actions runs on PR | `android-ci-optimized.yml` validate job invokes `check-lazy-keys.sh` and fails if keyless found |
| **#145: iText narrowing** | Before narrowing | Measure baseline APK size with current `com.itextpdf.**` keeps |
| | After narrowing | APK size reduced, measured delta |
| | On-device PDF gen | PDF generated and rendered without crash or corruption (manual) |

## Reusable seams

- `/games/Git/paperless client/scripts/validate-ci.sh` (lines 202–258) — keyless-Lazy detection logic; extract to `check-lazy-keys.sh` and source from both validate-ci.sh and CI workflow
- `/games/Git/paperless client/.githooks/pre-push` (lines 9–51) — git hook stdin parsing; reuse pattern for other pre-push hooks if needed
- `/games/Git/paperless client/.github/workflows/android-ci-optimized.yml` (validate job, lines 199–246) — CI validation template; add `check-lazy-keys.sh` step alongside existing hardcoded-strings and XML checks
- `/games/Git/paperless client/app/proguard-rules.pro` (lines 46–61) — iText keeps; replace with narrowed entry-point rules once APK delta is measured

## Out of scope

- Kotlin/Java code changes to the app itself (rules narrowing is ProGuard config only)
- Deploy pipeline changes (release.yml, auto-deploy-internal.yml remain unchanged)
- Other git hooks (.githooks/pre-commit, commit-msg) — only pre-push is fixed
- ProGuard R8 full-mode vs. compat-mode selection (assume current config is correct; only refine keep rules)
- Device firmware / Android OS version specifics (smoke test is on representative device; no multi-OS matrix)

See related architectural masters:
- Plan #6 (if exists) — Gradle/build performance hardening
- Plan #X — Release QA & device verification cadence (encompasses #145 smoke test)

