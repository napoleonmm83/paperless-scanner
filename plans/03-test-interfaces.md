# [plan-03] Test-Double Foundation — extract collaborator interfaces to enable fakes

## Defect

The 9 critical collaborators powering UploadWorker, SyncWorker, TrashDeleteWorker, WidgetUpdateWorker, and ServerHealthViewModel are concrete `@Inject` classes with **zero public interfaces**. This forces all unit tests to use `mockk(relaxed = true)`, which:

1. **Silently accepts any method call** — tests don't fail when the mock's contract changes
2. **Hides real Flow/StateFlow semantics** — relaxed mocks emit nothing by default; tests pass false negatives
3. **Blocks migration to typed Fake* test doubles** — fakes must implement interfaces, not wrap concrete types
4. **Enables false-pass tests** — a test can pass without exercising the actual API surface (e.g., missing DAO queries)

Current state: **UploadWorkerTest** already uses selective stubs (`coEvery`, `every`) to work around relaxed mock defects; **SyncWorkerTest**, **TrashDeleteWorkerTest**, **WidgetUpdateWorkerTest** still rely on full relaxed-mode defaults. **ServerHealthViewModelTest** uses relaxed mocks for 6 collaborators including `AnalyticsService` (never verified).

The root cause is architectural: all collaborators were built as concrete singleton services without interface contracts. Interface extraction is the prerequisite for test-double migration (#239, #202).

## Children

- #239 — Migrate ServerHealthViewModel tests (esp. flow-producing collaborators: NetworkMonitor, ServerHealthMonitor, UploadQueueRepository, SyncManager, SyncHistoryRepository) from `mockk(relaxed)` to typed Fake* doubles with AnalyticsService recording sink; use Turbine for flow assertions. (_status: open_)
- #202 — Replace relaxed mocks with fakes in 4 worker tests (UploadWorkerTest, SyncWorkerTest, TrashDeleteWorkerTest, WidgetUpdateWorkerTest) for all 9 collaborators; verify `fix/missing-worker-tests` branch doesn't already cover this. (_status: open_)

## Fix sequence

### Phase 1: Interface Extraction (reviewable, reusable foundation)

1. **Extract 9 collaborator interfaces** in `di/` folder as `*Contract.kt`:
   - `UploadQueueRepository` → `UploadQueueRepositoryContract` (5 methods: getNextPendingUpload, markAsUploading, resetToPending, markAsCompleted, markAsFailed)
   - `SyncHistoryRepository` → `SyncHistoryRepositoryContract` (3 methods: recordSuccess, recordFailure, observeFailedCount)
   - `DocumentRepository` → `DocumentRepositoryContract` (3 methods: uploadDocument, uploadMultiPageDocument + signature preservation)
   - `SyncManager` → `SyncManagerContract` (3 methods: performFullSync, pendingChangesCount StateFlow, stop)
   - `TrashRepository` → `TrashRepositoryContract` (2 methods: deleteDocument, permanentlyDeleteDocuments; needed by TrashDeleteWorker)
   - `NetworkMonitor` → `NetworkMonitorContract` (2 properties: isOnline StateFlow, hasValidatedInternet())
   - `ServerHealthMonitor` → `ServerHealthMonitorContract` (3 properties: serverStatus StateFlow, isServerReachable StateFlow, checkServerHealth())
   - `TokenManager` → `TokenManagerContract` (1 method: getTokenSync; already has interface-like API)
   - `CrashlyticsHelper` → `CrashlyticsHelperContract` (1 method: recordException)
   
   **Scope:** Extract interfaces only; no implementation changes. Use `interface` keyword, not sealed class. Place contracts adjacent to implementations for now.

2. **Update AppModule.kt** to wire `@Binds` for each interface → concrete impl:
   ```kotlin
   @Module
   @InstallIn(SingletonComponent::class)
   abstract class AppModuleBindings {
       @Binds abstract fun bindUploadQueueRepository(impl: UploadQueueRepository): UploadQueueRepositoryContract
       @Binds abstract fun bindSyncHistoryRepository(impl: SyncHistoryRepository): SyncHistoryRepositoryContract
       // ... etc
   }
   ```
   Keep existing `@Provides` methods in `AppModule` unchanged; add abstract `@Binds` in separate module.

3. **Update all production code** to inject interfaces, not concrete classes:
   - UploadWorker, SyncWorker, TrashDeleteWorker, WidgetUpdateWorker constructor params
   - ServerHealthViewModel constructor params
   - AppModule provider signatures (e.g., `provideNetworkMonitor` returns `NetworkMonitorContract`)
   - Any DAO/Flow consumers (e.g., SyncManager.pendingChangesCount property type)

4. **Verify Hilt compilation** and run full test suite to ensure contracts match implementations exactly.

### Phase 2a: ViewModel Fakes (lighter, uses Turbine already in use)

5. **Create `FakeAnalyticsService`** in `app/src/test/.../testing/fakes/`:
   ```kotlin
   class FakeAnalyticsService : AnalyticsServiceContract {
       val recordedExceptions = mutableListOf<Throwable>()
       override fun recordException(t: Throwable) { recordedExceptions.add(t) }
       // ... other methods as no-ops
   }
   ```

6. **Create flow-producing fakes** for ServerHealthViewModelTest:
   - `FakeNetworkMonitor` (isOnline StateFlow, hasValidatedInternet returns true)
   - `FakeServerHealthMonitor` (serverStatus, isServerReachable StateFlows, checkServerHealth returns Success)
   - `FakeUploadQueueRepository` (pendingCount StateFlow(0))
   - `FakeSyncManager` (pendingChangesCount StateFlow(0), performFullSync returns success, stop is no-op)
   - `FakeSyncHistoryRepository` (observeFailedCount returns MutableStateFlow(0), recordSuccess/recordFailure are no-ops)

7. **Migrate ServerHealthViewModelTest** (#239):
   - Replace `mockk(relaxed = true)` calls with `Fake*` instantiations
   - Use Turbine's `.test()` for existing flow assertions (already present in test)
   - Add assertion for AnalyticsService: verify no exception recorded on normal operation
   - Add assertion for AnalyticsService: verify exception recorded on error path (if one exists)

### Phase 2b: Worker Fakes (full, 4 test files)

8. **Create worker fakes** for #202 (4 worker tests):
   - Remaining fakes: `FakeDocumentRepository`, `FakeTrashRepository` (for TrashDeleteWorker)
   - `FakeTaskRepository` (if needed by UploadWorker; check existing tests)
   - `FakeCrashlyticsHelper` with exception recording (reuse from AnalyticsService pattern)

9. **Migrate UploadWorkerTest** (#202):
   - Replace all `mockk(relaxed = true)` with Fake* constructors
   - Replace `coEvery { repo.method(...) } returns X` with Fake's internal state setters (e.g., `fakeUploadQueueRepo.nextUploadToReturn = ...`)
   - Add assertions on Fake's recorded calls (e.g., `fakeUploadQueueRepo.recordedCalls.contains(...)`)
   - Verify all existing tests pass without behavior change

10. **Migrate SyncWorkerTest, TrashDeleteWorkerTest, WidgetUpdateWorkerTest** (#202):
    - Same pattern as UploadWorkerTest
    - Before redoing TrashDeleteWorker tests: **investigate `fix/missing-worker-tests` branch** (commit 54e7d583) to ensure no duplicate work

11. **Verify test count and coverage:**
    - UploadWorkerTest: 30+ tests (already heavy; should pass as-is)
    - SyncWorkerTest: ~5 tests (check branch coverage)
    - TrashDeleteWorkerTest: 5 tests (check if fix/missing-worker-tests covers)
    - WidgetUpdateWorkerTest: ? tests (to be surveyed)
    - Total: ~40-50 tests across 4 files

## Test matrix

| Axis | Case | Required behavior |
|---|---|---|
| **Interface scope** | UploadQueueRepository.getNextPendingUpload() | Returns `PendingUpload?`; nullable, matches DAO |
| **Interface scope** | SyncManager.pendingChangesCount | StateFlow<Int>, emits count changes |
| **Interface scope** | NetworkMonitor.isOnline | StateFlow<Boolean>, reflects connectivity |
| **Fake implementation** | FakeUploadQueueRepository | Stores `nextUploadToReturn`, records method calls, returns as configured |
| **Fake implementation** | FakeAnalyticsService | Records `recordedExceptions`, no-op trackEvent |
| **Test migration** | UploadWorkerTest with FakeUploadQueueRepository | All 30+ existing tests pass without code change (except mock setup) |
| **Test migration** | ServerHealthViewModelTest with FakeNetworkMonitor | Flow combines correctly, no false-pass from relaxed defaults |
| **Test migration** | SyncWorkerTest with FakeSyncManager | Sync success/failure/retry paths verified with typed returns, not defaults |
| **Collaborator surface** | CrashlyticsHelper.recordException verifiability | Fake records calls; UploadWorkerTest asserts non-fatals logged correctly |
| **Hilt wiring** | @Binds UploadQueueRepository → UploadQueueRepositoryContract | Compilation succeeds; real impl injected to workers |

## Reusable seams

- `/games/Git/paperless client/app/src/test/java/com/paperless/scanner/testing/BaseRoomRepositoryTest.kt` — Already provides real in-memory Room for DAO-level tests; fakes will sit adjacent in same `testing/` folder for shared visibility.
- `/games/Git/paperless client/app/src/test/java/com/paperless/scanner/worker/UploadWorkerTest.kt` — Existing selective stubs (`coEvery`, `every`) show the contract; interfaces will formalize this pattern.
- `/games/Git/paperless client/app/src/main/java/com/paperless/scanner/di/AppModule.kt` — Single @Module location; interface extraction wiring updates confined to one module pair (AppModule + new AppModuleBindings).
- `/games/Git/paperless client/app/src/test/java/com/paperless/scanner/ui/screens/home/ServerHealthViewModelTest.kt` — Already uses Turbine (app.cash.turbine); no new dependency required for #239.
- `app/src/test/java/com/paperless/scanner/worker/` — 4 worker tests; `fix/missing-worker-tests` branch exists; survey before redoing.

## Out of scope

- **Hilt @IntoSet/Map multi-binding** — Not needed; each collaborator is a single impl per contract.
- **MockWebServer seams** — Already used in some integration tests (e.g., API layer); fakes don't replace those; they replace in-memory unit test mocks.
- **Coroutine dispatcher injection** — Existing tests use `StandardTestDispatcher` via `Dispatchers.setMain`; no changes needed.
- **DAO interface extraction** — DAOs are already interface-like (abstract classes); fakes will use real in-memory Room via BaseRoomRepositoryTest pattern.
- **Storage/Battery constraint mocking** — NetworkMonitor constraints are WorkManager-level; out of scope for unit test fakes.
- **Other architectural refactors** — This plan is a pure test-support change; it does NOT refactor collaborator lifetimes, scopes, or coroutine ownership (see issue #81 for lifecycle work).

