# [plan-06] Coroutine & Flow Hygiene — shared read cache, bounded reads, lifecycle teardown (3 INDEPENDENT slices)

## Defect

The Paperless client exhibits a **coroutine scope lifecycle mismatch** across three independent architectural layers:

1. **Scope-not-cancelled problem (app lifecycle):** `BillingManager`, `NetworkMonitor`, and `ServerHealthMonitor` each own a `CoroutineScope(SupervisorJob() + Dispatcher)` that is never cancelled. When the process dies, the scopes are abandoned in-flight, leaving reconnect jobs, health-check polling, and other long-lived coroutines dangling. The `BillingManager.destroy()` method exists (L838) and correctly cancels `reconnectJob` and pending purchase continuations, but is never called from `PaperlessApp`.

2. **Unbounded read latency problem (VM/repo boundary):** Repositories (`TagRepository`, `CorrespondentRepository`, etc.) expose cold `getTags(forceRefresh)` accessors that directly query the API with no timeout boundary. The shared OkHttp client has global `connect=30s` + `read=30s` (NetworkConfig), and `AdaptiveWriteTimeoutInterceptor` protects uploads, but these cold suspend reads at the repository boundary are vulnerable to indefinite hangs. A client-global `callTimeout` is unsafe because uploads already compute adaptive per-request timeouts.

3. **Re-collection re-queries DB problem (data layer):** Hot `observeTags()` flows are collected repeatedly—each collection re-executes the underlying Room query. `TagSuggestionsViewModel` already wraps `observeTags()` in `stateIn(WhileSubscribed(5000))` (L88–89), but the `CorrespondentRepository.observeCorrespondents()` and `DocumentTypeRepository.observeDocumentTypes()` are not cached. Meanwhile, cold consumers like `SuggestionOrchestrator` call `.first()` on uncached flows (L70–72), triggering duplicate DB room queries every time suggestions are computed. The shared stateIn cache **must preserve** cold `.first()` accessors for AI/upload-metadata consumers (`SuggestionOrchestrator`, `PaperlessSuggestionsService`, `UploadMetadataUseCase`) because they re-query on-demand instead of subscribing once.

Current reality: `NetworkMonitor.offlineDebounceJob` and `ServerHealthMonitor.pollingJob` have no cancellation path; `BillingManager.scope` is never cancelled; repositories leak redundant database queries; and no read timeout exists at the suspend/API boundary.

## Children

- #50 — repositories lack stateIn/shareIn — re-collection re-queries DB; partially-done
- #82 — ViewModel coroutines lack read timeouts; open
- #142 — BillingManager scope no lifecycle cleanup; open

## Fix sequence

### Slice #142: App Layer — Lifecycle Teardown (BillingManager, ServerHealthMonitor, NetworkMonitor)

1. Wire `BillingManager.destroy()` call to `PaperlessApp` via a new `DefaultLifecycleObserver` that observes `ProcessLifecycleOwner` for app `onDestroy()`.
2. Add `destroy()` method to `NetworkMonitor` to cancel `offlineDebounceJob` and stop monitoring.
3. Add `destroy()` method to `ServerHealthMonitor` to cancel `pollingJob` and cancel the init-block collector (via scope cancellation).
4. Register all three destroy calls in the same app-level observer (onDestroy block).
5. Add a **teardown leak test** (`BillingManagerLeakTest.kt`, `NetworkMonitorLeakTest.kt`, `ServerHealthMonitorLeakTest.kt`) that verifies:
   - All jobs are cancelled post-destroy.
   - Scope is cancelled (JobCancellationException propagates on re-use).
   - No exception is logged when destroy is called during in-flight operations.

### Slice #82: VM/Repo Boundary — Read Timeout Wrapper

1. Create a new suspend extension function `withReadTimeout(timeoutMs: Long)` in `ApiExtensions.kt` that:
   - Wraps the API call in `withTimeout(timeoutMs)`.
   - Re-throws `CancellationException` unchanged (no catch-and-suppress).
   - Applies only to read-only GET endpoints.
2. Add a new constant `READ_TIMEOUT_SECONDS = 30L` (matching OkHttp's read timeout) to `NetworkConfig.kt`.
3. Update repository cold accessors to use the timeout wrapper:
   - `TagRepository.getTags()` → wrap internal `api.getTags()` calls in `withReadTimeout(30000)`.
   - `CorrespondentRepository.getCorrespondents()` → same pattern.
   - `DocumentTypeRepository.getDocumentTypes()` → same pattern.
   - `CustomFieldRepository.getCustomFields()` → same pattern.
4. **Critical constraint:** The timeout applies at the repository boundary **only**, never to the OkHttp client itself (no `callTimeout` on the builder). Upload/scan coroutines are not touched—they have their own adaptive timeout interceptor.
5. Add unit tests (`RepositoryReadTimeoutTest.kt`) verifying:
   - `withReadTimeout` aborts slow API calls.
   - `CancellationException` propagates (is not swallowed).
   - Upload endpoints are not affected (no timeout wrapper applied).

### Slice #50: Data Layer — Shared stateIn Cache (Repositories)

1. Add a `shareIn(scope, SharingStarted.WhileSubscribed(5000))` wrapper to the remaining hot `observe*()` flows in repositories:
   - `CorrespondentRepository.observeCorrespondents()` → wrap with `shareIn`.
   - `DocumentTypeRepository.observeDocumentTypes()` → wrap with `shareIn`.
   - `CustomFieldRepository.observeCustomFields()` → wrap with `shareIn`.
   - Keep `TagRepository.observeTags()` unchanged (already has `stateIn` cache at the ViewModel layer; moving to repo is low-impact).
2. **Preserve cold accessors:** All `get*()` suspend methods (e.g., `getCorrespondents(forceRefresh)`) remain unchanged—they are cold and not wrapped in `stateIn`/`shareIn`.
3. Add stateIn-wrapped variants accessible via ViewModel-layer composition:
   - Update `SuggestionOrchestrator` to accept injected `coroutineScope` (for `stateIn` context).
   - Update calls to `.first()` to remain on the shared flow (no additional wrapping needed; `first()` on a `StateFlow` is efficient).
4. Verify AI/upload-metadata consumers still work:
   - `SuggestionOrchestrator.getSuggestions()` calls `tagRepository.observeTags().first()` — now hits the shared cache instead of re-querying.
   - `PaperlessSuggestionsService` uses the same `tagRepository` — confirm no regressions.
   - `UploadMetadataUseCase` reads correspondents and document types — confirm no latency regression.
5. Add integration tests (`RepositoryShareInTest.kt`) verifying:
   - Multiple collectors of the same `observe*()` flow share the same Room query (via Wiremock or MockK spy).
   - `.first()` on a `StateFlow` does not re-subscribe after the first collection.
   - Cold `get*()` accessors still fetch fresh data on-demand.

## Test matrix

| Axis | Case | Required behavior |
|---|---|---|
| **Slice #142: Lifecycle** | App transitions to background | `ServerHealthMonitor.pollingJob` is cancelled. |
| | App process destroyed | All manager scopes are cancelled; no exception logged. |
| | `BillingManager.destroy()` called mid-reconnect | `reconnectJob` is cancelled; continuation resumes with error. |
| | `NetworkMonitor.destroy()` called mid-debounce | `offlineDebounceJob` is cancelled. |
| | `ServerHealthMonitor.destroy()` called during init-block collector | Scope cancellation stops the collector. |
| **Slice #82: Read Timeout** | API read hangs >30s | `withReadTimeout` cancels the call; `CancellationException` propagates. |
| | API read completes <30s | Result is returned unchanged. |
| | Upload request hangs >30s | **No timeout** (adaptive interceptor controls upload timeout, not this wrapper). |
| | Cancellation mid-read | `CancellationException` propagates (is not swallowed). |
| **Slice #50: SharedIn Cache** | Two VMs observe `observeCorrespondents()` simultaneously | Only one Room query is executed; both VMs see the same result. |
| | `SuggestionOrchestrator.getSuggestions()` calls `.first()` twice in sequence | Only one Room query is executed per call (StateFlow cache hits). |
| | `getCorrespondents(forceRefresh=true)` while cache is active | Cold accessor still fetches fresh API data; cache is not used. |
| | ViewModel destroyed before cache timeout | Cache remains active for 5 seconds; new VMs reuse cached data. |

## Reusable seams

- `/games/Git/paperless client/app/src/main/java/com/paperless/scanner/util/NetworkConfig.kt` — add `READ_TIMEOUT_SECONDS` constant; reuse for all repository read timeouts.
- `/games/Git/paperless client/app/src/main/java/com/paperless/scanner/data/api/ApiExtensions.kt` — add `withReadTimeout()` extension; reuse across all cold repository accessors.
- `/games/Git/paperless client/app/src/main/java/com/paperless/scanner/PaperlessApp.kt` — add `DefaultLifecycleObserver` in `initializeServerHealthMonitoring()` pattern to wire all three `destroy()` calls; reuse ProcessLifecycleOwner registration.
- `/games/Git/paperless client/app/src/main/java/com/paperless/scanner/data/api/AdaptiveWriteTimeoutInterceptor.kt` — already protects uploads; **do NOT add global callTimeout**.
- `/games/Git/paperless client/app/src/main/java/com/paperless/scanner/data/billing/BillingManager.kt` (L838) — `destroy()` method already exists; only needs to be called.
- `/games/Git/paperless client/app/src/main/java/com/paperless/scanner/data/repository/TagRepository.kt` (L88–91) — `observeTags().stateIn()` pattern already validated in ViewModel; replicate for Correspondent/DocumentType repositories.

## Out of scope

- **Issue #307** (scan-shared-images lifecycle cleanup) — separate image-file-deletion problem; not a coroutine scope leak.
- **Issue #241** (FileProvider cache scoping) — already merged in PR #306; security fix for cache directory access.
- **Issue #126** (paginated list truncation) — already fixed via `fetchAllPages()` in PR #289; not a coroutine issue.
- **Global OkHttp `callTimeout`** — explicitly unsafe for adaptive upload timeouts; do not add.
- **Shutdown hooks for WorkManager tasks** — `UploadWorker` and `SyncWorker` are decoupled from app lifecycle; WorkManager manages their teardown.

