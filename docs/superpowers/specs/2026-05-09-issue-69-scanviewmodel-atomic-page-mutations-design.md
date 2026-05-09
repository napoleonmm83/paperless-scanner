# Issue #69 — ScanViewModel atomic page-mutation refactor

**Issue:** [napoleonmm83/paperless-scanner#69](https://github.com/napoleonmm83/paperless-scanner/issues/69) (HIGH, area:architecture)
**Effort label:** M
**Date:** 2026-05-09
**Related finding:** F-044, F-045

## Problem

`undoRemovePage` (L547-L563) and `movePage` (L569-L587) in `ScanViewModel.kt` invoke side-effects from inside the `_uiState.update { ... }` lambda:

- `syncPagesToSavedState(...)` — disk I/O against `SavedStateHandle` (in both methods)
- `analyticsService.trackEvent(AnalyticsEvent.ScanPagesReordered)` — Firebase Analytics dispatch (in `movePage` only)

`MutableStateFlow.update { transform }` allows the `transform` lambda to be invoked multiple times under CAS contention. The lambda is contractually expected to be pure. With the current code, contention would cause:

- The disk write to fire 2+ times (wasteful, but probably idempotent — the write is overwriting with the same final value).
- The analytics event to fire 2+ times for a single user-initiated reorder (real metric pollution).

The Issue body's literal AC-1 ("No `.toMutableList` outside `.update {}`") is vacuously true in the current code — every `.toMutableList()` is already inside the lambda. The actual fix the AC describes ("Single helper updates state + syncs SavedStateHandle atomically") targets the side-effect-in-lambda problem, not the mutable-list construction.

## Goal

Make the page-mutation lambda pure. Move side-effects (`syncPagesToSavedState`, `analyticsService.trackEvent`) outside the lambda so they execute exactly once after the CAS commits, with the correct final state.

## Non-goals

- Refactoring other page-mutation methods (`rotatePage` at L589, `removePage` around L530, etc.) that exhibit the same pattern. Per scope decision: stay within Issue #69's stated targets (`undoRemovePage`, `movePage`). Mention the others as a candidate follow-up in the PR body.
- Changing the `ScannedPage` data class or the `ScanUiState` shape.
- Touching `SavedStateHandle` plumbing in any way other than where it's already invoked.
- Anything in `ScanScreen.kt` or other consumers — the public method signatures (`undoRemovePage()`, `movePage(fromIndex, toIndex)`) are unchanged.

## Design

### Helper: `mutatePagesAndSync`

A new private inline helper in `ScanViewModel` that accepts a pure `transform: (ScanUiState) -> ScanUiState`, applies it via `updateAndGet` (atomic CAS, returns final committed state), and then runs `syncPagesToSavedState` exactly once with the post-commit pages:

```kotlin
private inline fun mutatePagesAndSync(
    crossinline transform: (ScanUiState) -> ScanUiState
): ScanUiState {
    val updated = _uiState.updateAndGet { state -> transform(state) }
    syncPagesToSavedState(updated.pages)
    return updated
}
```

`updateAndGet` returns the post-CAS state, regardless of how many times the transform was retried. The sync runs once, against the final state. The helper returns the new state so the caller can compare against a captured "before" snapshot when it needs to detect "did anything change?" (e.g., for conditional analytics).

### `undoRemovePage` rewrite

```kotlin
fun undoRemovePage() {
    mutatePagesAndSync { state ->
        val info = state.lastRemovedPage ?: return@mutatePagesAndSync state
        val newPages = state.pages.toMutableList()
            .apply { add(info.originalIndex, info.page) }
            .mapIndexed { i, p -> p.copy(pageNumber = i + 1) }
        state.copy(pages = newPages, lastRemovedPage = null)
    }
}
```

The lambda is pure. If `lastRemovedPage` is null, return the unchanged state — `updateAndGet` will commit the same value, and the post-helper sync still runs but writes the same pages list (idempotent, low-cost).

### `movePage` rewrite

`movePage` has TWO side-effects to handle: the sync (handled by the helper) and the analytics event (specific to this method). For analytics we need to know "did the move actually happen?" — out-of-bounds indices result in a no-op return, and we must NOT fire analytics in that case. We use reference inequality between the captured pre-state pages and the post-update pages:

```kotlin
fun movePage(fromIndex: Int, toIndex: Int) {
    val pagesBefore = _uiState.value.pages
    val updated = mutatePagesAndSync { state ->
        if (fromIndex !in state.pages.indices || toIndex !in state.pages.indices) {
            return@mutatePagesAndSync state
        }
        val newPages = state.pages.toMutableList()
            .apply { add(toIndex, removeAt(fromIndex)) }
            .mapIndexed { i, p -> p.copy(pageNumber = i + 1) }
        state.copy(pages = newPages)
    }
    if (updated.pages !== pagesBefore) {
        analyticsService.trackEvent(AnalyticsEvent.ScanPagesReordered)
    }
}
```

The `pages !== pagesBefore` check is reference-identity, not structural equality. The lambda's no-op path returns the same `state` reference (which carries the same `state.pages` reference), so the post-helper `updated.pages` is identical-by-reference to `pagesBefore`. A successful move constructs a new list, so `updated.pages` is a different reference. This is exact: it correctly distinguishes "no-op" from "moved". (Note: `pagesBefore` is captured outside the CAS, so under genuine concurrent calls there is a TOCTOU window. In practice, `ScanViewModel` methods are invoked from the UI thread on the Main dispatcher and cannot interleave; this is consistent with how the code already treats the rest of the surface.)

### Loading + error semantics

Unchanged. No state field other than `pages` and `lastRemovedPage` is touched by either method.

## Acceptance Criteria mapping

| AC from #69 | Covered by |
| --- | --- |
| No `.toMutableList` outside `.update {}` | Already true today; preserved (calls remain inside the lambda passed to `updateAndGet`). |
| Single helper updates state + syncs SavedStateHandle atomically | `mutatePagesAndSync` is that helper. Both target methods route through it. |
| Tests verify atomic mutation | Three tests in `ScanViewModelTest.kt` (see Test plan). |

## Test plan

Three tests added to the existing `app/src/test/java/com/paperless/scanner/ui/screens/scan/ScanViewModelTest.kt` (no new file). Stack: MockK + JUnit 4 + `kotlinx.coroutines.test.runTest` + `Dispatchers.setMain(StandardTestDispatcher())` per project precedent.

1. **`undoRemovePage updates state and syncs SavedStateHandle exactly once`** — Seed VM with a `lastRemovedPage`. Call `undoRemovePage`. Assert the page is back in `uiState.pages` at the correct index AND `savedStateHandle["pageUris"]` (or whatever key `syncPagesToSavedState` writes) reflects the restored list. Verify only one update emission (not two) — proves the sync isn't running twice, and the lambda was committed once.
2. **`movePage out-of-bounds is a no-op and does not fire analytics`** — Seed VM with two pages. Call `movePage(fromIndex = 5, toIndex = 0)`. Assert `uiState.pages` is identical (reference-equal) to before, and `coVerify(exactly = 0) { analyticsService.trackEvent(any()) }`. Proves the reference-equality short-circuit is wired correctly.
3. **`movePage in-bounds fires analytics exactly once`** — Seed VM with two pages. Call `movePage(0, 1)`. Assert pages are reordered AND `coVerify(exactly = 1) { analyticsService.trackEvent(AnalyticsEvent.ScanPagesReordered) }`. Proves analytics moved out of the lambda and fires once per user action.

Approximately 60-90 LOC of test, no new fixture infrastructure.

## Out of scope (won't be in the PR)

- Same pattern in `rotatePage` / `removePage` / other page-mutation methods. Acknowledged in PR body; can be a follow-up issue if anyone files one.
- Any change to the public API or to `ScanScreen.kt`.
- Migration of `_uiState` to a different StateFlow type.
- CRUD-path tests beyond the three above.

## Risk & open questions

- **Risk: `updateAndGet` not in the project's coroutines version.** Mitigation: kotlinx-coroutines `updateAndGet` has been stable since 1.6.x; this repo's HomeViewModel and other callers already rely on equivalent flow APIs. Verify in implementation by checking imports — if it's not available, fall back to `update { ... }` followed by reading `_uiState.value`, which has the same effect under single-threaded Main dispatcher.
- **Open: should `syncPagesToSavedState` be skipped when the lambda was a no-op?** Current design always calls it (idempotent overwrite). Skipping is a micro-optimization at the cost of a reference-equality check inside the helper. Decision: don't skip — simpler, idempotent, no observable difference. If later profiling shows it matters, add a `pages !== state.pages` guard inside the helper.
