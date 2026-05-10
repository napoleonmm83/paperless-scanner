# Issue #70 — UploadViewModel atomic init from nav-arg — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate the `setDocumentUris(uris)` post-init mutator on `UploadViewModel`. The VM reads the encoded `documentUris` navigation argument from its own `SavedStateHandle` synchronously in `init {}`, URL-decodes the segments, exposes them as a `MutableStateFlow<List<Uri>>` whose initial value is already the decoded list. No subscriber ever observes the empty-then-populated sequence that today races against `setDocumentUris`.

**Architecture:** Replace `getStateFlow<String?> → .map → .stateIn(initialValue = emptyList())` with `MutableStateFlow<List<Uri>>(parseFromSavedState())` + a private `parseFromSavedState()` helper that handles both URL-encoded (initial nav arg) and unencoded (post-process-death restoration) segments. Process-death writes go through the same helper; the format inside the VM `SavedStateHandle` becomes unencoded after the first parse so subsequent restorations are idempotent. The Navigation-side `BackStackEntry.savedStateHandle` write (the AppLock sync at `MultiPageUploadScreen.kt:108-117`) keeps its existing unencoded format — that path is unchanged. The `LaunchedEffect(documentUris)` block at `MultiPageUploadScreen.kt:97-101` is deleted because it has nothing left to do.

**Tech Stack:** Kotlin 2.0, kotlinx.coroutines (`MutableStateFlow`, `StateFlow.value`), Turbine 1.x for "single emission" assertion, MockK + JUnit4 + `kotlinx.coroutines.test.runTest`, JDK 21.

**Spec / finding:** [`docs/code-reviews/findings/F-046-uploadviewmodel-race-setdocumenturis-vs-reactive-documenturi.md`](../../code-reviews/findings/F-046-uploadviewmodel-race-setdocumenturis-vs-reactive-documenturi.md)

**Branch:** `refactor/issue-70-uploadviewmodel-atomic-init` (already created — this plan is the spec commit on it)

---

## Conventions used throughout

- **JDK 21 must be exported on every Bash invocation:**

  ```bash
  export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.9.10-hotspot"
  export PATH="$JAVA_HOME/bin:$PATH"
  ```

- **Use Release variants:** `testReleaseUnitTest`, NOT `testDebugUnitTest`. GitHub Actions uses Release.
- **One commit per task.** Each task ends with one `git commit`.
- **NEVER** use `--no-verify` to skip pre-commit hooks.
- Test idiom for "first emission is populated, no empty-then-populated race": Turbine `awaitItem()` once and assert; do NOT `expectMostRecentItem()` because the property under test is *that there is only one item*.

---

## File Structure

| File | Type | Responsibility |
| --- | --- | --- |
| `app/src/main/java/com/paperless/scanner/ui/screens/upload/UploadViewModel.kt` | **Modify** | Replace `documentUrisStateFlow` cold pipeline with `MutableStateFlow` + `init {}` parse. Remove `setDocumentUris`. |
| `app/src/main/java/com/paperless/scanner/ui/screens/upload/MultiPageUploadScreen.kt` | **Modify** | Delete the `LaunchedEffect(documentUris)` init block at L94-103 (the one that calls `setDocumentUris`). Keep the AppLock sync `LaunchedEffect(observedDocumentUris)` at L108-117 untouched. Keep the `activeDocumentUris` parameter-fallback at L120 because tests rely on the parameter for the very first frame before `documentUris.collectAsState()` re-renders. |
| `app/src/test/java/com/paperless/scanner/ui/screens/upload/UploadViewModelTest.kt` | **Modify** (append 4 tests) | Sync-init from URL-encoded arg / sync-init from unencoded arg / empty-when-no-arg / Turbine single-emission. |
| `docs/superpowers/plans/2026-05-10-issue-70-uploadviewmodel-atomic-init-plan.md` | **Add** (this file) | Plan + spec doc. |

No DI, no resources, no string changes.

---

## Task 0: Commit this plan as the spec on the branch

- [ ] **Step 0.1: Verify branch**

  ```bash
  git branch --show-current
  ```

  Expected: `refactor/issue-70-uploadviewmodel-atomic-init`

- [ ] **Step 0.2: Commit the plan**

  ```bash
  git add docs/superpowers/plans/2026-05-10-issue-70-uploadviewmodel-atomic-init-plan.md
  git commit -m "$(cat <<'EOF'
  docs: design plan for UploadViewModel atomic init refactor (Refs #70)

  Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
  EOF
  )"
  ```

---

## Task 1: Test — VM constructed with URL-encoded nav arg in SavedStateHandle exposes decoded URIs synchronously

This is the AC #1 + AC #2 pin: "URIs set during VM construction (no post-init mutator)" and "no race between manual set and reactive read". Should FAIL against current code (current code's `documentUrisStateFlow` initial value is `emptyList()` until the cold flow boots up) and PASS post-refactor.

**Files:**
- Modify: `app/src/test/java/com/paperless/scanner/ui/screens/upload/UploadViewModelTest.kt`

- [ ] **Step 1.1: Add the test**

  Locate the existing test that exercises `setDocumentUris` (search the file for `setDocumentUris` to find the right region) and add the new test alongside it.

  ```kotlin
  @Test
  fun `init parses URL-encoded documentUris nav arg into documentUris StateFlow synchronously`() = runTest {
      val uri1 = Uri.parse("content://media/external/images/media/123")
      val uri2 = Uri.parse("content://media/external/images/media/456")
      val encoded = listOf(uri1, uri2).joinToString("|") { Uri.encode(it.toString()) }
      val savedStateWithNavArg = SavedStateHandle(mapOf(UploadViewModel.KEY_DOCUMENT_URIS to encoded))

      val viewModel = buildViewModel(savedStateHandle = savedStateWithNavArg)

      // No advanceUntilIdle, no collect-then-await: documentUris.value MUST be populated synchronously
      // by init {}. This is the contract that breaks the LaunchedEffect race.
      assertEquals(listOf(uri1, uri2), viewModel.documentUris.value)
  }
  ```

  If `buildViewModel(savedStateHandle = ...)` doesn't already exist as a helper in the test file, extract one from the existing `@Before`/`setUp` block — search for `UploadViewModel(` to find the construction site and copy/parameterize it.

- [ ] **Step 1.2: Run the test**

  ```bash
  export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.9.10-hotspot"
  export PATH="$JAVA_HOME/bin:$PATH"
  ./gradlew :app:testReleaseUnitTest --tests "com.paperless.scanner.ui.screens.upload.UploadViewModelTest.init parses URL-encoded documentUris nav arg into documentUris StateFlow synchronously" --no-daemon
  ```

  Expected: **FAIL** against current code (the StateFlow value is `emptyList()` because `stateIn` hasn't started collecting yet). This is correct — the test pins the post-refactor contract.

- [ ] **Step 1.3: Commit (red)**

  ```bash
  git add app/src/test/java/com/paperless/scanner/ui/screens/upload/UploadViewModelTest.kt
  git commit -m "$(cat <<'EOF'
  test: pin synchronous URL-encoded nav-arg parse on UploadViewModel init (Refs #70)

  Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
  EOF
  )"
  ```

---

## Task 2: Test — VM constructed with unencoded SavedStateHandle (post-process-death) exposes URIs synchronously

Pins the idempotency claim: once the VM has run `init {}` once and re-written the SavedStateHandle in unencoded form, a second VM instance restored from that exact SavedStateHandle still produces the same `documentUris.value`.

**Files:**
- Modify: `app/src/test/java/com/paperless/scanner/ui/screens/upload/UploadViewModelTest.kt`

- [ ] **Step 2.1: Add the test**

  ```kotlin
  @Test
  fun `init parses unencoded documentUris from process-death SavedStateHandle synchronously`() = runTest {
      val uri1 = Uri.parse("content://media/external/images/media/123")
      val uri2 = Uri.parse("content://media/external/images/media/456")
      val unencoded = listOf(uri1, uri2).joinToString("|") { it.toString() }
      val savedStateAfterDeath = SavedStateHandle(mapOf(UploadViewModel.KEY_DOCUMENT_URIS to unencoded))

      val viewModel = buildViewModel(savedStateHandle = savedStateAfterDeath)

      assertEquals(listOf(uri1, uri2), viewModel.documentUris.value)
  }
  ```

- [ ] **Step 2.2: Run the test**

  ```bash
  export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.9.10-hotspot"
  export PATH="$JAVA_HOME/bin:$PATH"
  ./gradlew :app:testReleaseUnitTest --tests "com.paperless.scanner.ui.screens.upload.UploadViewModelTest.init parses unencoded documentUris from process-death SavedStateHandle synchronously" --no-daemon
  ```

  Expected: **FAIL** against current code (`stateIn` initialValue = `emptyList()`).

- [ ] **Step 2.3: Commit**

  ```bash
  git add app/src/test/java/com/paperless/scanner/ui/screens/upload/UploadViewModelTest.kt
  git commit -m "$(cat <<'EOF'
  test: pin process-death SavedStateHandle parse on UploadViewModel init (Refs #70)

  Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
  EOF
  )"
  ```

---

## Task 3: Test — VM constructed with empty SavedStateHandle exposes empty URIs

Negative case. Should pass before AND after refactor.

**Files:**
- Modify: `app/src/test/java/com/paperless/scanner/ui/screens/upload/UploadViewModelTest.kt`

- [ ] **Step 3.1: Add the test**

  ```kotlin
  @Test
  fun `init with empty SavedStateHandle exposes empty documentUris`() = runTest {
      val emptySavedState = SavedStateHandle()

      val viewModel = buildViewModel(savedStateHandle = emptySavedState)

      assertEquals(emptyList<Uri>(), viewModel.documentUris.value)
  }
  ```

- [ ] **Step 3.2: Run + commit**

  ```bash
  export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.9.10-hotspot"
  export PATH="$JAVA_HOME/bin:$PATH"
  ./gradlew :app:testReleaseUnitTest --tests "com.paperless.scanner.ui.screens.upload.UploadViewModelTest.init with empty SavedStateHandle exposes empty documentUris" --no-daemon
  ```

  Expected: PASS against both current and refactored code.

  ```bash
  git add app/src/test/java/com/paperless/scanner/ui/screens/upload/UploadViewModelTest.kt
  git commit -m "$(cat <<'EOF'
  test: empty SavedStateHandle yields empty documentUris (Refs #70)

  Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
  EOF
  )"
  ```

---

## Task 4: Test — Turbine subscriber sees exactly one populated emission, no empty-then-populated race (AC #3)

This is the integration assertion the user explicitly asked for. It does at the StateFlow contract level what a Compose UI integration test would do at the LaunchedEffect level: it proves a fresh subscriber on a freshly-constructed VM sees the populated list as the **first** emission, never an empty one followed by a populated one.

**Files:**
- Modify: `app/src/test/java/com/paperless/scanner/ui/screens/upload/UploadViewModelTest.kt`

- [ ] **Step 4.1: Verify Turbine import**

  Search the file for `import app.cash.turbine`. If absent, add `import app.cash.turbine.test`.

- [ ] **Step 4.2: Add the test**

  ```kotlin
  @Test
  fun `documentUris first emission to a fresh subscriber is the populated list, never empty`() = runTest {
      val uri = Uri.parse("content://media/external/images/media/789")
      val encoded = Uri.encode(uri.toString())
      val savedStateWithNavArg = SavedStateHandle(mapOf(UploadViewModel.KEY_DOCUMENT_URIS to encoded))

      val viewModel = buildViewModel(savedStateHandle = savedStateWithNavArg)

      viewModel.documentUris.test {
          // First emission MUST be the populated list. If we ever see an empty one first,
          // the LaunchedEffect(observedDocumentUris) in MultiPageUploadScreen would fire twice
          // and dependent flows would race.
          assertEquals(listOf(uri), awaitItem())
          // No further emissions: the StateFlow has reached its terminal value.
          expectNoEvents()
          cancelAndConsumeRemainingEvents()
      }
  }
  ```

- [ ] **Step 4.3: Run + commit**

  ```bash
  export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.9.10-hotspot"
  export PATH="$JAVA_HOME/bin:$PATH"
  ./gradlew :app:testReleaseUnitTest --tests "com.paperless.scanner.ui.screens.upload.UploadViewModelTest.documentUris first emission to a fresh subscriber is the populated list, never empty" --no-daemon
  ```

  Expected: **FAIL** against current code (Turbine sees `emptyList()` first, then the populated list, because of the `stateIn(initialValue = emptyList())` default). This is the canonical race-existence proof.

  ```bash
  git add app/src/test/java/com/paperless/scanner/ui/screens/upload/UploadViewModelTest.kt
  git commit -m "$(cat <<'EOF'
  test: Turbine — single populated documentUris emission, no empty-first race (Refs #70)

  Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
  EOF
  )"
  ```

---

## Task 5: Refactor — UploadViewModel reads nav arg in init, exposes MutableStateFlow, removes setDocumentUris

This is the production-code change that turns the four red tests green.

**Files:**
- Modify: `app/src/main/java/com/paperless/scanner/ui/screens/upload/UploadViewModel.kt`

- [ ] **Step 5.1: Replace the `documentUrisStateFlow` block (currently L73-L98) with**

  ```kotlin
  // Reactive documentUris with synchronous initialization from SavedStateHandle.
  //
  // The SavedStateHandle is populated either:
  //   (a) by Navigation Compose with the URL-encoded `documentUris` route argument
  //       (initial navigation; segments need URL-decoding), OR
  //   (b) by process-death restoration with the unencoded form we wrote here last time
  //       (segments don't need decoding).
  // parseDocumentUrisFromSavedState() handles both. After init {} returns, SavedStateHandle
  // holds the unencoded canonical form so subsequent restorations are idempotent.
  private val _documentUris = MutableStateFlow(parseDocumentUrisFromSavedState())
  val documentUris: StateFlow<List<Uri>> = _documentUris.asStateFlow()

  init {
      // Canonicalise the SavedStateHandle to the unencoded form, so process-death
      // restoration uses the same parse path as fresh starts and so the Screen's
      // BackStackEntry-sync LaunchedEffect (which writes unencoded) stays consistent.
      val canonical = _documentUris.value.joinToString("|") { it.toString() }
      savedStateHandle[KEY_DOCUMENT_URIS] = canonical.takeIf { it.isNotEmpty() }
  }

  private fun parseDocumentUrisFromSavedState(): List<Uri> {
      val raw = savedStateHandle.get<String>(KEY_DOCUMENT_URIS) ?: return emptyList()
      if (raw.isEmpty()) return emptyList()
      return raw.split("|").mapNotNull { segment ->
          try {
              // URL-decode is idempotent for already-unencoded `content://` URIs:
              // they contain no '%' triplets, so Uri.decode is a no-op.
              Uri.parse(Uri.decode(segment))
          } catch (e: Exception) {
              Log.e(TAG, "Failed to parse URI segment: $segment", e)
              null
          }
      }
  }
  ```

  CRITICAL: the existing `init { observeTagsReactively(); ... }` block at L184 must be **merged** with the new `init {}` above, not duplicated. Either move the canonicalisation lines into the existing block, or delete the existing block and put both bodies in one merged `init {}`. Keep the order: canonicalise FIRST, then the `observeXReactively()` calls.

- [ ] **Step 5.2: Delete the `setDocumentUris` function (currently L100-L110)**

  Just remove L100-L110 entirely. There must be no remaining caller in the codebase except the one in `MultiPageUploadScreen.kt` which Task 6 deletes.

- [ ] **Step 5.3: Verify no other callers of setDocumentUris exist**

  ```bash
  grep -rn "setDocumentUris" app/src
  ```

  Expected: only `MultiPageUploadScreen.kt:100` (which Task 6 will remove). If anything else, stop and add to plan.

- [ ] **Step 5.4: Run all four red tests**

  ```bash
  export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.9.10-hotspot"
  export PATH="$JAVA_HOME/bin:$PATH"
  ./gradlew :app:testReleaseUnitTest --tests "com.paperless.scanner.ui.screens.upload.UploadViewModelTest" --no-daemon
  ```

  Expected: all four new tests now PASS, plus all pre-existing UploadViewModelTest tests still pass. If any pre-existing test that exercised `setDocumentUris` directly fails, update it to inject the URIs via the SavedStateHandle constructor argument instead — that is the new contract.

- [ ] **Step 5.5: Commit**

  ```bash
  git add app/src/main/java/com/paperless/scanner/ui/screens/upload/UploadViewModel.kt app/src/test/java/com/paperless/scanner/ui/screens/upload/UploadViewModelTest.kt
  git commit -m "$(cat <<'EOF'
  refactor: UploadViewModel atomic init from nav arg, drop setDocumentUris (Closes #70)

  Replaces the cold getStateFlow + map + stateIn(initialValue = emptyList()) pipeline
  with a MutableStateFlow whose initial value is parsed synchronously in init {} from
  the SavedStateHandle nav-arg. The post-init setDocumentUris mutator is gone, so a
  fresh subscriber to documentUris sees the populated list as the first emission and
  the LaunchedEffect race in MultiPageUploadScreen disappears.

  Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
  EOF
  )"
  ```

---

## Task 6: Refactor MultiPageUploadScreen — remove the setDocumentUris init LaunchedEffect

Now that the VM populates `documentUris` synchronously, the Screen has nothing to do at startup.

**Files:**
- Modify: `app/src/main/java/com/paperless/scanner/ui/screens/upload/MultiPageUploadScreen.kt`

- [ ] **Step 6.1: Delete L94-L103**

  These lines:

  ```kotlin
  // Initialize ViewModel with navigation arguments (survives process death)
  // CRITICAL: Only initialize from navigation args if ViewModel doesn't already have state
  // This prevents stale route arguments from overwriting correct SavedStateHandle data after AppLock
  LaunchedEffect(documentUris) {
      if (documentUris.isNotEmpty() && observedDocumentUris.isEmpty()) {
          // ViewModel is empty → initialize from navigation arguments
          viewModel.setDocumentUris(documentUris)
      }
      // If ViewModel already has URIs (e.g., after AppLock unlock), trust SavedStateHandle as source of truth
  }
  ```

  Delete them outright.

- [ ] **Step 6.2: Keep the AppLock-sync `LaunchedEffect(observedDocumentUris)` at L108-117 untouched**

  This block writes the VM state to the BackStackEntry SavedStateHandle for AppLock route reconstruction. It is unrelated to the race and must continue to fire on every change.

- [ ] **Step 6.3: Keep the `activeDocumentUris` parameter-fallback at L120 untouched**

  Reasoning: this is the very-first-frame display fallback. With the new VM contract `observedDocumentUris` is *immediately* the right value, so the fallback never triggers — but keeping it is defence in depth and costs nothing.

- [ ] **Step 6.4: Verify**

  ```bash
  grep -n "setDocumentUris" app/src/main/java/com/paperless/scanner/ui/screens/upload/MultiPageUploadScreen.kt
  ```

  Expected: no matches.

- [ ] **Step 6.5: Commit**

  ```bash
  git add app/src/main/java/com/paperless/scanner/ui/screens/upload/MultiPageUploadScreen.kt
  git commit -m "$(cat <<'EOF'
  refactor: drop setDocumentUris call site in MultiPageUploadScreen (Refs #70)

  Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
  EOF
  )"
  ```

---

## Task 7: Local CI gauntlet (full Release variants, exactly like GitHub Actions)

- [ ] **Step 7.1: Run the project's CI script**

  ```bash
  export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.9.10-hotspot"
  export PATH="$JAVA_HOME/bin:$PATH"
  ./scripts/validate-ci.sh
  ```

  Or, if the script is not executable on this machine, run the equivalent commands:

  ```bash
  ./gradlew testReleaseUnitTest --no-daemon
  ./gradlew lintRelease --no-daemon
  ./gradlew assembleRelease --no-daemon
  ```

  All must be green. If lintRelease finds anything in the touched files, fix it before pushing.

---

## Task 8: Push, open PR, drive CodeRabbit to green

- [ ] **Step 8.1: Pre-create the changelog files**

  After the GitHub Actions auto-bump, the new version code will be `MAJOR * 10000 + MINOR * 100 + PATCH` of whatever main is at when the PR merges. Pre-create both files at `fastlane/metadata/android/{de-DE,en-US}/changelogs/<versionCode>.txt` only if you can determine the versionCode deterministically; otherwise add the changelog as part of the un-draft commit as we did for PR #226.

  Suggested EN body (≤500 chars, ≤60 char/bullet):

  ```text
  Version <X>:

  🔧 Improvements:
  - Multi-page upload screen: faster, race-free init
  - More reliable state restore after AppLock unlock

  🛠️ Internal:
  - UploadViewModel atomic init from navigation args
  ```

- [ ] **Step 8.2: Push**

  ```bash
  git push -u origin refactor/issue-70-uploadviewmodel-atomic-init
  ```

- [ ] **Step 8.3: Open the PR**

  ```bash
  gh pr create --title "refactor: UploadViewModel atomic init, drop setDocumentUris (Closes #70)" --body "$(cat <<'EOF'
  ## Summary

  - Replace `documentUrisStateFlow` (cold `getStateFlow + map + stateIn(initialValue = emptyList())`) on `UploadViewModel` with a `MutableStateFlow<List<Uri>>` whose initial value is parsed synchronously from the `SavedStateHandle` nav arg in `init {}`.
  - Drop the `setDocumentUris` post-init mutator.
  - Drop the `LaunchedEffect(documentUris)` init block in `MultiPageUploadScreen` that called it.
  - 4 new VM tests (synchronous init from URL-encoded arg / unencoded arg / empty / Turbine single-emission) pin the new contract.

  ## Test plan

  - [x] `./gradlew testReleaseUnitTest` green (4 new + all pre-existing)
  - [x] `./gradlew lintRelease` green
  - [x] `./gradlew assembleRelease` green
  - [ ] Manual: scan or pick multiple images → upload screen opens → URIs appear immediately, no flash of empty grid
  - [ ] Manual: scan, lock app, unlock → upload screen restores URIs (AppLock path)

  ## Acceptance criteria mapping

  - **AC #1 — URIs set during VM construction (no post-init mutator):** ✅ `setDocumentUris` removed; `init {}` parses from `SavedStateHandle`.
  - **AC #2 — No race between manual set and reactive read:** ✅ no manual set anymore; `documentUris.value` is the parsed list from frame zero.
  - **AC #3 — Tests verify URI availability before any dependent flow emits:** ✅ Task 4 Turbine test asserts the first emission to a fresh subscriber is the populated list.

  ## Out of scope (tracked elsewhere)

  - The `documentUris: List<Uri>` parameter on `MultiPageUploadScreen` is still passed in from `PaperlessNavGraph`. We could drop it in a follow-up because the VM is now self-sufficient, but that is a separate refactor with its own PR.

  Closes #70
  EOF
  )" --base main
  ```

- [ ] **Step 8.4: Drive CodeRabbit to green**

  Same workflow as PR #228 / PR #227: address each actionable comment, decline with explicit reasoning if it pulls in scope this PR shouldn't touch (and open a follow-up issue for the declined item if it has merit). Always check `.coderabbit.yaml` `path_instructions` before declining anything.

---

## Acceptance Criteria mapping (cross-ref)

| AC | Status | Where proven |
| --- | --- | --- |
| URIs set during VM construction (no post-init mutator) | ✅ | Task 5 deletes `setDocumentUris`; Tasks 1+2 prove init populates `documentUris.value` synchronously |
| No race between manual set and reactive read | ✅ | Task 4 Turbine assertion; no manual set exists post-refactor |
| Tests verify URI availability before any dependent flow emits | ✅ | Task 4 Turbine `awaitItem()` + `expectNoEvents()` |

## Out of scope

- Dropping the `documentUris: List<Uri>` parameter from `MultiPageUploadScreen` — separate PR.
- Compose UI / instrumented integration test — the project's CI does not run `connectedAndroidTest`, so the integration concern is proven at the StateFlow contract level via Turbine.
- AppLock-restoration regression test — the existing AppLock interceptor unit tests cover that path; this refactor doesn't touch it.

## Self-review checklist (run before "ready for review")

- [ ] No remaining caller of `setDocumentUris` in `app/src/**`
- [ ] No remaining caller of the deleted `documentUrisStateFlow` private val
- [ ] `init {}` block is single (not duplicated) and the canonicalisation runs before `observeXReactively()`
- [ ] All four new tests have descriptive backtick names that read top-to-bottom as a spec
- [ ] PR body's AC mapping is accurate
