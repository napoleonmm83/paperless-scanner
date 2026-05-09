# Issue #69 — ScanViewModel atomic page-mutation refactor — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move side-effects (`syncPagesToSavedState`, `analyticsService.trackEvent`) out of `_uiState.update { … }` lambdas in `ScanViewModel.undoRemovePage` and `ScanViewModel.movePage`, so they run exactly once after the CAS commit instead of being retried under contention.

**Architecture:** Introduce `private inline fun mutatePagesAndSync(transform: (ScanUiState) -> ScanUiState): ScanUiState` that wraps `_uiState.updateAndGet { transform(it) }` and then invokes `syncPagesToSavedState(updated.pages)` exactly once. Analytics in `movePage` moves outside the lambda, conditional on a reference-inequality "did anything change" check.

**Tech Stack:** Kotlin 2.0, kotlinx.coroutines (`MutableStateFlow.updateAndGet`), MockK + Robolectric + JUnit4 + `kotlinx.coroutines.test.runTest` for tests, JDK 21.

**Spec:** [`docs/superpowers/specs/2026-05-09-issue-69-scanviewmodel-atomic-page-mutations-design.md`](../specs/2026-05-09-issue-69-scanviewmodel-atomic-page-mutations-design.md)

**Branch:** `refactor/issue-69-scanviewmodel-atomic-page-mutations` (already created with the spec commit `12c57cb`)

---

## Conventions used throughout

- **JDK 21 must be exported on every Bash invocation:**
  ```
  export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.9.10-hotspot"
  export PATH="$JAVA_HOME/bin:$PATH"
  ```
- **Use Release variants:** `testReleaseUnitTest`, NOT `testDebugUnitTest`. GitHub Actions uses Release.
- **One commit per task.** Per-step bite-sized work but each task ends with one `git commit`.
- **NEVER** use `--no-verify` to skip pre-commit hooks.

---

## File Structure

| File | Type | Responsibility |
| --- | --- | --- |
| `app/src/main/java/com/paperless/scanner/ui/screens/scan/ScanViewModel.kt` | **Modify** | Add private `mutatePagesAndSync` helper. Rewrite `undoRemovePage` + `movePage` bodies. |
| `app/src/test/java/com/paperless/scanner/ui/screens/scan/ScanViewModelTest.kt` | **Modify** (append 3 tests) | Verify SavedStateHandle sync + analytics-exactly-once + analytics-not-on-no-op. |

No other files touched. No DI, no UI, no resources.

---

## Task 1: Test — `undoRemovePage` syncs SavedStateHandle correctly

This is regression coverage — should pass against current code AND post-refactor. Locks in the SavedStateHandle write side of the helper.

**Files:**
- Modify: `app/src/test/java/com/paperless/scanner/ui/screens/scan/ScanViewModelTest.kt`

- [ ] **Step 1.1: Add the test**

Append inside the class body (after the existing `undoRemovePage` tests block, around the existing `// ==================== movePage ====================` boundary so it lives next to similar tests):

```kotlin
    @Test
    fun `undoRemovePage updates SavedStateHandle pageUris with restored page`() = runTest {
        val viewModel = viewModelWithPages(listOf("a", "b", "c"))
        advanceUntilIdle()

        // Capture the original three URIs in the SavedStateHandle.
        val urisBefore = savedStateHandle.get<String>(ScanViewModel.KEY_PAGE_URIS)
        assertNotNull(urisBefore)

        // Remove the middle page.
        val midId = viewModel.uiState.value.pages[1].id
        viewModel.removePage(midId)
        advanceUntilIdle()

        val urisAfterRemove = savedStateHandle.get<String>(ScanViewModel.KEY_PAGE_URIS)
        assertNotNull(urisAfterRemove)
        // After removal, two URIs remain.
        assertEquals(2, urisAfterRemove!!.split("|").size)

        // Undo the removal.
        viewModel.undoRemovePage()
        advanceUntilIdle()

        val urisAfterUndo = savedStateHandle.get<String>(ScanViewModel.KEY_PAGE_URIS)
        assertNotNull(urisAfterUndo)
        // After undo, three URIs are back.
        assertEquals(3, urisAfterUndo!!.split("|").size)
    }
```

- [ ] **Step 1.2: Run the test**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.9.10-hotspot"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:testReleaseUnitTest --tests "com.paperless.scanner.ui.screens.scan.ScanViewModelTest.undoRemovePage updates SavedStateHandle pageUris with restored page" --no-daemon
```

Expected: `PASSED` against current code (sync runs inside the lambda; on Main dispatcher with no contention, it runs once and writes the correct value).

- [ ] **Step 1.3: Commit**

```bash
git add app/src/test/java/com/paperless/scanner/ui/screens/scan/ScanViewModelTest.kt
git commit -m "$(cat <<'EOF'
test: undoRemovePage syncs SavedStateHandle pageUris (Refs #69)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Test — `movePage` out-of-bounds does not fire analytics

Verifies the reference-inequality short-circuit that we'll install in the refactor. Currently the analytics line at L576 is INSIDE the bounds-check, so this test ALSO passes today — but the test pins the invariant for the refactor where analytics moves outside the lambda.

**Files:**
- Modify: `app/src/test/java/com/paperless/scanner/ui/screens/scan/ScanViewModelTest.kt`

- [ ] **Step 2.1: Add the test**

Append inside the class body, in the `// ==================== movePage ====================` block:

```kotlin
    @Test
    fun `movePage out-of-bounds does not fire analytics`() = runTest {
        val viewModel = viewModelWithPages(listOf("a", "b"))
        advanceUntilIdle()

        viewModel.movePage(fromIndex = 0, toIndex = 10)
        advanceUntilIdle()

        io.mockk.verify(exactly = 0) {
            analyticsService.trackEvent(any())
        }
    }
```

- [ ] **Step 2.2: Run the test**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.9.10-hotspot"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:testReleaseUnitTest --tests "com.paperless.scanner.ui.screens.scan.ScanViewModelTest.movePage out-of-bounds does not fire analytics" --no-daemon
```

Expected: `PASSED`.

- [ ] **Step 2.3: Commit**

```bash
git add app/src/test/java/com/paperless/scanner/ui/screens/scan/ScanViewModelTest.kt
git commit -m "$(cat <<'EOF'
test: movePage out-of-bounds does not fire analytics (Refs #69)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Test — `movePage` in-bounds fires analytics exactly once

Verifies the analytics-exactly-once guarantee. This is the most important new test: a regression that re-introduces the side-effect inside `_uiState.update { … }` could (under contention) fire analytics multiple times. With analytics outside the update lambda, it fires exactly once per public method call regardless of CAS retries.

**Files:**
- Modify: `app/src/test/java/com/paperless/scanner/ui/screens/scan/ScanViewModelTest.kt`

- [ ] **Step 3.1: Add the test**

Add immediately after the test from Task 2:

```kotlin
    @Test
    fun `movePage in-bounds fires analytics exactly once`() = runTest {
        val viewModel = viewModelWithPages(listOf("a", "b", "c"))
        advanceUntilIdle()

        viewModel.movePage(fromIndex = 0, toIndex = 2)
        advanceUntilIdle()

        io.mockk.verify(exactly = 1) {
            analyticsService.trackEvent(com.paperless.scanner.data.analytics.AnalyticsEvent.ScanPagesReordered)
        }
    }
```

- [ ] **Step 3.2: Run the test**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.9.10-hotspot"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:testReleaseUnitTest --tests "com.paperless.scanner.ui.screens.scan.ScanViewModelTest.movePage in-bounds fires analytics exactly once" --no-daemon
```

Expected: `PASSED`. (Currently passes because under StandardTestDispatcher there is no contention, so the lambda runs once and analytics fires once.)

- [ ] **Step 3.3: Commit**

```bash
git add app/src/test/java/com/paperless/scanner/ui/screens/scan/ScanViewModelTest.kt
git commit -m "$(cat <<'EOF'
test: movePage in-bounds fires analytics exactly once (Refs #69)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Refactor — introduce helper, rewrite `undoRemovePage` and `movePage`

This is the core change. Single file, ~30 LOC modified. The helper plus two method rewrites land together in one commit so the file always compiles.

**Files:**
- Modify: `app/src/main/java/com/paperless/scanner/ui/screens/scan/ScanViewModel.kt`

- [ ] **Step 4.1: Add `updateAndGet` import if not already present**

Check the top imports of `ScanViewModel.kt`. If `kotlinx.coroutines.flow.update` is already imported, the package already exposes `updateAndGet` from the same module — but the explicit symbol still needs an import. Add:

```kotlin
import kotlinx.coroutines.flow.updateAndGet
```

If the import block has alphabetical-order convention (it does in this file), insert in the right place between the existing `update` import and other `.flow.*` imports.

- [ ] **Step 4.2: Add `mutatePagesAndSync` helper**

Find the `syncPagesToSavedState` method (around L356):

```kotlin
    private fun syncPagesToSavedState(pages: List<ScannedPage>) {
        if (pages.isEmpty()) {
            savedStateHandle[KEY_PAGE_URIS] = null
        } else {
            …
            savedStateHandle[KEY_PAGE_URIS] = urisString
        }
    }
```

Immediately AFTER `syncPagesToSavedState`, insert the new helper:

```kotlin
    /**
     * Atomically applies [transform] to the UI state via `updateAndGet`, then
     * syncs the resulting pages to [SavedStateHandle] exactly once.
     *
     * The [transform] lambda is required to be pure: `MutableStateFlow.update`-
     * family operators are allowed to invoke it multiple times under CAS
     * contention, so any side effect inside the lambda would fire multiple
     * times. The sync side-effect runs here, after the CAS commits, with the
     * final committed state.
     *
     * Returns the post-commit state so callers can compare against a captured
     * pre-state for "did anything change?" checks (e.g. movePage analytics).
     */
    private inline fun mutatePagesAndSync(
        crossinline transform: (ScanUiState) -> ScanUiState
    ): ScanUiState {
        val updated = _uiState.updateAndGet { state -> transform(state) }
        syncPagesToSavedState(updated.pages)
        return updated
    }
```

- [ ] **Step 4.3: Rewrite `undoRemovePage` (currently L547-L563)**

Find the current body:
```kotlin
    fun undoRemovePage() {
        _uiState.update { state ->
            val removedPageInfo = state.lastRemovedPage ?: return@update state

            val mutablePages = state.pages.toMutableList()
            mutablePages.add(removedPageInfo.originalIndex, removedPageInfo.page)

            val renumberedPages = mutablePages.mapIndexed { index, page ->
                page.copy(pageNumber = index + 1)
            }
            syncPagesToSavedState(renumberedPages)
            state.copy(
                pages = renumberedPages,
                lastRemovedPage = null
            )
        }
    }
```

Replace with:
```kotlin
    fun undoRemovePage() {
        mutatePagesAndSync { state ->
            val removedPageInfo = state.lastRemovedPage ?: return@mutatePagesAndSync state

            val mutablePages = state.pages.toMutableList()
            mutablePages.add(removedPageInfo.originalIndex, removedPageInfo.page)

            val renumberedPages = mutablePages.mapIndexed { index, page ->
                page.copy(pageNumber = index + 1)
            }
            state.copy(
                pages = renumberedPages,
                lastRemovedPage = null
            )
        }
    }
```

The `syncPagesToSavedState(renumberedPages)` call is gone — `mutatePagesAndSync` does it post-commit.

- [ ] **Step 4.4: Rewrite `movePage` (currently L569-L587)**

Find the current body:
```kotlin
    fun movePage(fromIndex: Int, toIndex: Int) {
        _uiState.update { state ->
            if (fromIndex < 0 || fromIndex >= state.pageCount ||
                toIndex < 0 || toIndex >= state.pageCount) {
                return@update state
            }

            analyticsService.trackEvent(AnalyticsEvent.ScanPagesReordered)
            val mutablePages = state.pages.toMutableList()
            val page = mutablePages.removeAt(fromIndex)
            mutablePages.add(toIndex, page)

            val renumberedPages = mutablePages.mapIndexed { index, p ->
                p.copy(pageNumber = index + 1)
            }
            syncPagesToSavedState(renumberedPages)
            state.copy(pages = renumberedPages)
        }
    }
```

Replace with:
```kotlin
    fun movePage(fromIndex: Int, toIndex: Int) {
        val pagesBefore = _uiState.value.pages
        val updated = mutatePagesAndSync { state ->
            if (fromIndex < 0 || fromIndex >= state.pageCount ||
                toIndex < 0 || toIndex >= state.pageCount) {
                return@mutatePagesAndSync state
            }

            val mutablePages = state.pages.toMutableList()
            val page = mutablePages.removeAt(fromIndex)
            mutablePages.add(toIndex, page)

            val renumberedPages = mutablePages.mapIndexed { index, p ->
                p.copy(pageNumber = index + 1)
            }
            state.copy(pages = renumberedPages)
        }
        if (updated.pages !== pagesBefore) {
            analyticsService.trackEvent(AnalyticsEvent.ScanPagesReordered)
        }
    }
```

Both side-effects (analytics + sync) now live OUTSIDE the lambda. Analytics is gated by reference-inequality between the captured pre-state pages and the post-update pages — a no-op (out-of-bounds) returns `state` unchanged, so `updated.pages === pagesBefore`, and no analytics event fires.

- [ ] **Step 4.5: Run the full ScanViewModelTest suite to verify nothing regresses**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.9.10-hotspot"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:testReleaseUnitTest --tests "com.paperless.scanner.ui.screens.scan.ScanViewModelTest" --no-daemon
```

Expected: ALL tests pass — both the existing tests (regression coverage for `removePage`, `undoRemovePage`, `movePage`, etc.) AND the three new tests added in Tasks 1-3.

If anything fails, the most likely cause is that the `updateAndGet` import was missed (Step 4.1) — the file would not compile.

- [ ] **Step 4.6: Commit**

```bash
git add app/src/main/java/com/paperless/scanner/ui/screens/scan/ScanViewModel.kt
git commit -m "$(cat <<'EOF'
refactor: extract mutatePagesAndSync helper for atomic page mutations

Move syncPagesToSavedState and analyticsService.trackEvent side-effects out
of _uiState.update {} lambdas in undoRemovePage and movePage. The helper
uses updateAndGet, then runs sync once with the post-commit pages. movePage
analytics is gated by reference-inequality between the captured pre-state
pages and the post-update pages so it does not fire on no-op (out-of-bounds)
calls.

Closes #69

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Local CI gauntlet (full Release variants)

- [ ] **Step 5.1: Run the full local CI script**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.9.10-hotspot"
export PATH="$JAVA_HOME/bin:$PATH"
./scripts/validate-ci.sh
```

Expected: all phases pass — Validation Checks, `testReleaseUnitTest`, `assembleRelease`, `lintRelease`. If any phase fails, fix the underlying issue and re-run. Do NOT use `--no-verify`.

If `validate-ci.sh` is unavailable, run the equivalent commands directly:

```bash
./gradlew testReleaseUnitTest --no-daemon
./gradlew lintRelease --no-daemon
./gradlew assembleRelease --no-daemon
```

All three must produce `BUILD SUCCESSFUL`.

- [ ] **Step 5.2: No commit** — validation only.

---

## Task 6: Push and open PR

- [ ] **Step 6.1: Push the branch**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.9.10-hotspot"
export PATH="$JAVA_HOME/bin:$PATH"
git push -u origin refactor/issue-69-scanviewmodel-atomic-page-mutations
```

The pre-push hook auto-rebases against origin/main if needed. Expected: green push.

- [ ] **Step 6.2: Open the PR**

```bash
gh pr create --title "refactor: ScanViewModel atomic page mutations — pure update lambdas (Closes #69)" --body "$(cat <<'EOF'
## Summary

Closes #69. Moves side-effects (`syncPagesToSavedState`, `analyticsService.trackEvent`) out of the `_uiState.update { … }` lambdas in `ScanViewModel.undoRemovePage` and `ScanViewModel.movePage`. `MutableStateFlow.update` is allowed to invoke its transform multiple times under CAS contention, so any side-effect inside the lambda would fire multiple times. The new pattern uses `updateAndGet` for the atomic state mutation and runs side-effects once afterwards with the post-commit state.

## Changes

- `ScanViewModel.kt`: new private inline `mutatePagesAndSync(transform)` helper. `undoRemovePage` and `movePage` rewritten to route through it. `movePage` analytics moves outside the lambda, gated by a reference-inequality "did anything change?" check that distinguishes the no-op (out-of-bounds) path from a real reorder.
- `ScanViewModelTest.kt`: 3 new tests — SavedStateHandle sync after `undoRemovePage`, `movePage` out-of-bounds does NOT fire analytics, `movePage` in-bounds fires analytics exactly once.

## Acceptance Criteria mapping

- [x] No `.toMutableList` outside `.update {}` (already true; preserved through the helper).
- [x] Single helper updates state + syncs SavedStateHandle atomically (`mutatePagesAndSync`).
- [x] Tests verify atomic mutation (3 tests cover sync, no-op-no-analytics, single-emission analytics).

## Out of scope (tracked for follow-up)

The same side-effect-inside-update pattern exists in `rotatePage` (L589) and `removePage` (around L530). Per Issue #69's explicit Location and Target lines, only `undoRemovePage` and `movePage` are changed here. Cleaning up the other page-mutation methods to use the same helper is a candidate follow-up issue if anyone files one.

## Test plan

- [x] `ScanViewModelTest.kt` — 3 new tests pass; existing tests still pass on Release variant
- [x] `validate-ci.sh` full run: ALL CI CHECKS PASSED
- [ ] CI on this PR
- [ ] CodeRabbit review

## Spec + plan

- Spec: `docs/superpowers/specs/2026-05-09-issue-69-scanviewmodel-atomic-page-mutations-design.md`
- Plan: `docs/superpowers/plans/2026-05-09-issue-69-scanviewmodel-atomic-page-mutations-plan.md`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 6.3: Wait for CI + CodeRabbit, then squash-merge**

```bash
gh pr checks --watch
```

After all checks green and CodeRabbit has reviewed (and any findings resolved), squash-merge:

```bash
gh pr merge --squash --delete-branch
```

Caveat from a recent observation in this repo: `gh pr merge --auto --squash` falls through to immediate merge because there is no required-checks branch protection. To wait for CI green, do not use `--auto` — call the merge after `gh pr checks --watch` completes successfully.

---

## Self-review (already performed; for re-runners)

- ✅ Spec coverage:
  - Helper definition → Task 4 Step 4.2
  - `undoRemovePage` rewrite → Task 4 Step 4.3
  - `movePage` rewrite (with reference-equality analytics gate) → Task 4 Step 4.4
  - SavedStateHandle sync test → Task 1
  - Out-of-bounds no-analytics test → Task 2
  - In-bounds exactly-one-analytics test → Task 3

- ✅ No placeholders: every code block is complete; no "TBD"; no "similar to above".

- ✅ Type consistency: `mutatePagesAndSync(transform: (ScanUiState) -> ScanUiState): ScanUiState` is referenced consistently in Task 4 Steps 4.3 and 4.4. Test names are byte-exact between Tasks 1-3 and the gradle `--tests` filters.

- ✅ Imports check: Task 4 Step 4.1 explicitly handles `updateAndGet` import. No other new imports required (`AnalyticsEvent.ScanPagesReordered` was already in use).
