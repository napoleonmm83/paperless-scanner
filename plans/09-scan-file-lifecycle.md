# [plan-09] Scan Draft File Lifecycle — draft-aware cleanup of shared_images

## Defect

`ScanViewModel` writes `shared_images/cropped_*.jpg` and `rotated_*.jpg` files when users crop pages or apply rotation-on-upload. Cropped pages become the persistent backing URIs of in-progress scan drafts (via `ScanViewModel.KEY_PAGE_URIS` persisted in SavedStateHandle — survives process death). On app startup, `PaperlessApp.cleanupOldCacheFiles()` sweeps aged files from the cache, including `shared_images/`. An age-only sweep risks deleting a `cropped_*.jpg` that a delayed-restored draft still needs: if the device is rebooted shortly after cropping, a process-death restore can occur seconds after startup (before the user reopens the Scan screen), and the cache sweep will have already deleted the page's backing file.

This is a data-loss hole: a user crops pages, the app crashes/reboots, and their draft is silently corrupted (pages show as broken images).

The fix already exists on local-only unmerged branch `fix/scan-shared-images-cleanup` (commit 1bec4db7, 9 commits behind origin/main). It introduces:
1. `ScanDraftCache` — SharedPreferences-backed registry that mirrors the draft's `shared_images` file names into an App-readable store that survives process death.
2. `SharedFileCache.cleanupAgedUnprotected()` — pure, unit-testable helper that sweeps aged files but never deletes names in a protected set.
3. `PaperlessApp` updated to pass protected names from `ScanDraftCache` to the sweep.
4. `ScanViewModel` updated to keep `ScanDraftCache` in sync whenever pages change.

## Children

- **#307** — ScanViewModel.cropAndSaveImage writes shared_images/cropped_*.jpg persisted in KEY_PAGE_URIS; the startup cache sweep must not delete still-referenced draft files; cropped_/rotated_ images otherwise accumulate (status: **done-on-branch**, implementation complete at commit 1bec4db7)

## Fix sequence

1. **Rebase & validate** — `git rebase origin/main` to move fix/scan-shared-images-cleanup onto current main (9 version-bump commits intervene). Verify tests pass.

2. **Create PR** — Open PR with title `fix(scan): draft-aware shared_images cache cleanup (#307)`, body describing the data-loss hole and the SharedPreferences + pure-function strategy.

3. **Land & merge** — Merge PR to main after review.

4. **Verify end-to-end** — Manual test:
   - Start Scan screen, add 2–3 pages, crop one page (generates `cropped_<ts>.jpg`).
   - Verify `cropped_<ts>.jpg` appears in `/data/data/com.paperless.scanner/cache/shared_images/`.
   - Verify file name is recorded in SharedPreferences key `scan_draft_cache.protected_shared_image_names`.
   - Kill the app (or trigger immediate app restart via `adb shell am crash`).
   - Reopen the app and immediately check cache dir: `cropped_<ts>.jpg` must still exist.
   - Reopen Scan screen: draft pages must render correctly (not broken images).
   - Rotate a page (generates `rotated_<ts>.jpg`); this is upload-only, never persisted, so it will be swept on next startup.

## Test matrix

| Axis | Case | Required behavior |
|---|---|---|
| **Startup sweep** | Draft exists, cropped file is old (>1h) | File is NOT deleted (protected by ScanDraftCache) |
| **Startup sweep** | No active draft, rotated file is old (>1h) | File IS deleted (not in protected set) |
| **Startup sweep** | Draft exists, but file name not in ScanDraftCache | File IS deleted (stale/orphaned) |
| **Draft save** | `syncPagesToSavedState(pages)` called | `ScanDraftCache.setProtectedFileNames(pages.map.uri)` is called atomically in same method |
| **Page removal** | `removePage(pageId)` called | Draft sync removes file name from protected set; file may be swept on next startup |
| **Draft clear** | `clearPages()` called | Protected set is cleared; all remaining `cropped_*.jpg` become eligible for sweep |
| **Draft restore** | Process death + `restorePagesFromSavedState()` | Protected set is re-asserted after SavedStateHandle restore (idempotent) |

## Reusable seams

- **`SharedFileCache.cleanupAgedUnprotected()`** — Pure function over filesystem, no Android/Hilt deps; directly unit-testable. Reuse for any cache sweep that must protect a set of live file names (e.g., widget cache, offline queue spill).

- **`ScanDraftCache`** — SharedPreferences-backed draft registry; can be reused if other draft-aware features need boot-time visibility (e.g., resume paused uploads, restore incomplete multi-part uploads).

- **Separation of concerns** — `PaperlessApp.onCreate` cleanup calls `SharedFileCache.cleanupAgedUnprotected()` (delegate to pure helper); `ScanViewModel` owns the protected-names sync (not spread across lifecycle callbacks or ViewModel teardown).

## Out of scope

- **Per-file lifecycle hooks** — Not adding `onPageRemoved(pageId)` callbacks to delete cropped files immediately; age-based sweep on startup is sufficient and simpler.
- **Rotated page cleanup** — `rotated_*.jpg` are never persisted (created fresh on upload), so they are intentionally excluded from the protected set and will age out normally.
- **Widget cache or other offline features** — This plan fixes Scan only; other features may have similar data-loss holes (tracked separately).
- **Merge strategy for persisted drafts** — If a user keeps the app installed across multiple upgrade cycles, `ScanDraftCache` is not migrated/merged; latest boot wins (acceptable: drafts are ephemeral, seconds to hours, not critical user data).

---

## Implementation checklist

- [ ] Rebase fix/scan-shared-images-cleanup onto origin/main
- [ ] Run full test suite (unit + instrumentation)
- [ ] Open PR with #307 closure
- [ ] Land PR
- [ ] Manual verification: draft persists across crash, pages render correctly, unrelated images age out

