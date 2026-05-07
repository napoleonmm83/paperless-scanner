# Design — DocumentRepository Refactor (Issue #51, Phase 5)

**Date:** 2026-05-07
**Issue:** [#171 — Phase 5: Façade cleanup + ViewModel migration](https://github.com/napoleonmm83/paperless-scanner/issues/171) (sub of #51)
**Effort:** L (multi-PR umbrella)
**Predecessors:** Phases 1, 2.1, 2.2, 2.3, 3.1, 3.2, 3.3, 4 — all merged.
**Master plan:** `docs/code-reviews/REFACTOR_DOCUMENT_REPOSITORY.md`

---

## 1. Why

After Phases 1–4, `DocumentRepository.kt` is at 441 LOC, of which ~25 methods (≈300 LOC) are pure 1-line delegations to specialized sub-repositories (`DocumentCountRepository`, `DocumentMetadataRepository`, `DocumentListRepository`, `TrashRepository`, `AuditRepository`, `PermissionRepository`). Three constructor parameters (`cachedTaskDao`, `pendingChangeDao`, `serverHealthMonitor`) are now injected but unused — Phase 3.3's `DocumentSyncRepository` extraction obsoleted them.

The façade was deliberately preserved during the extraction phases to keep caller code stable (façade-only pattern). Phase 5 finishes the refactor by:

1. Removing the dead constructor parameters.
2. Migrating the 5 callers that consume delegated methods to inject the specialized sub-repos directly.
3. Deleting the now-unreachable delegation methods.
4. Leaving `DocumentRepository` as a focused upload/download repo (~120 LOC).

This closes Issue #51.

---

## 2. End-state architecture

After Phase 5:

| Component | Role | LOC |
|-----------|------|-----|
| `DocumentRepository` | Owns `uploadDocument`, `uploadMultiPageDocument`, `downloadDocument` only | ~120 |
| `DocumentCountRepository` | unchanged from Phase 2.2 | 82 |
| `DocumentMetadataRepository` | unchanged from Phase 2.3 | 199 |
| `DocumentListRepository` | unchanged from Phase 2.1 | 164 |
| `TrashRepository` | unchanged from Phase 3.1 | 288 |
| `AuditRepository` | unchanged from Phase 3.2 | 83 |
| `PermissionRepository` | unchanged from Phase 4 | 67 |
| `DocumentSyncRepository` | unchanged from Phase 3.3 | ~120 |

`DocumentRepository.kt` Ctor after Phase 5:

```kotlin
class DocumentRepository @Inject constructor(
    private val context: Context,
    private val api: PaperlessApi,
    private val cachedDocumentDao: CachedDocumentDao,
    private val cachedTagDao: CachedTagDao,
    private val networkMonitor: NetworkMonitor,
    private val gson: Gson,
    private val crashlyticsHelper: CrashlyticsHelper,
    private val imageProcessor: ImageProcessorService,
    private val pdfGenerator: PdfGeneratorService,
    private val serializer: DocumentSerializer,
)
```

(Sub-repos `count`/`metadata`/`list`/`trash`/`audit`/`permission` are gone — no delegations remain.)

### Caller bindings after Phase 5

| Caller | Injected dependencies after migration |
|--------|---------------------------------------|
| `UploadWorker` | `DocumentRepository` (unchanged — uses upload methods only) |
| `PdfViewerViewModel` | `DocumentRepository` (unchanged — uses `downloadDocument` only) |
| `TrashDeleteWorker` | `TrashRepository` (was `DocumentRepository`) |
| `TrashViewModel` | `TrashRepository` |
| `DocumentsViewModel` | `DocumentListRepository`, `DocumentCountRepository`, `TrashRepository` |
| `HomeViewModel` | `DocumentListRepository`, `DocumentCountRepository`, `TrashRepository`, `DocumentMetadataRepository` |
| `DocumentDetailViewModel` | `DocumentMetadataRepository`, `AuditRepository`, `TrashRepository`, `PermissionRepository` |

---

## 3. PR sequence (7 PRs)

PR ordering is **risk-ascending**: dead-param cleanup first, then workers (no UI), then simple VMs (single sub-repo), then complex VMs (multi sub-repo), then final façade-shrink.

### PR1 — Remove dead constructor params

**Goal:** Drop unused ctor parameters; no caller-touch.

**Changes:**
- `DocumentRepository.kt`: remove `cachedTaskDao: CachedTaskDao`, `pendingChangeDao: PendingChangeDao`, `serverHealthMonitor: ServerHealthMonitor` from primary constructor.
- `DocumentRepositoryTest.kt`: drop the corresponding `@MockK` declarations and `setup()` wiring.
- Imports: remove `CachedTaskDao`, `PendingChangeDao`, `ServerHealthMonitor`.

**Risk:** trivial. Hilt re-binds automatically. Sub-repos already inject these themselves where needed.

**Verification:** `./scripts/validate-ci.sh` (RELEASE).

### PR2 — Migrate `TrashDeleteWorker`

**Goal:** Replace `DocumentRepository` injection with `TrashRepository`.

**Calls migrated:** `permanentlyDeleteDocument` (1 call, line 77).

**Changes:**
- `TrashDeleteWorker.kt`: swap `@Inject documentRepository: DocumentRepository` → `@Inject trashRepository: TrashRepository`. Update single call site.
- Test (if exists): mock-swap.

**Risk:** low. Single call, no UI, Worker is stateless.

### PR3 — Migrate `TrashViewModel`

**Goal:** All trash operations via `TrashRepository` directly.

**Calls migrated (8):** `observeTrashedDocuments`, `getTrashDocuments`, `cleanupOrphanedTrashDocs`, `observeTrashedDocumentsCount`, `restoreDocument`, `permanentlyDeleteDocument`, `restoreDocuments`, `permanentlyDeleteDocuments`.

**Changes:**
- `TrashViewModel.kt`: swap injection; update 8 call sites (all 1:1 string replacement of receiver).
- `TrashViewModelTest.kt`: mock-swap (`mockk<DocumentRepository>` → `mockk<TrashRepository>`).

**Risk:** low. Single sub-repo, mechanical 1:1 mapping.

### PR4 — Migrate `DocumentsViewModel`

**Goal:** Documents list screen uses 3 sub-repos directly.

**Calls migrated (5):**
- `getDocumentsPaged` → `DocumentListRepository`
- `observeCountWithFilter` → `DocumentCountRepository`
- `getDocuments` → `DocumentListRepository`
- `deleteDocument` → `TrashRepository`
- `restoreDocument` → `TrashRepository`

**Changes:**
- `DocumentsViewModel.kt`: 3 new `@Inject` dependencies; update 5 call sites.
- `DocumentsViewModelTest.kt`: 3 mocks instead of 1.

**Risk:** medium. Pagination logic is non-trivial — Document state behavior must remain identical.

### PR5 — Migrate `HomeViewModel`

**Goal:** Home screen uses 4 sub-repos directly.

**Calls migrated (14):** spread across `list`, `count`, `trash`, `metadata`. See migration map in §6.

**Changes:**
- `HomeViewModel.kt`: 4 new `@Inject` dependencies; update 14 call sites.
- `HomeViewModelTest.kt`: 4 mocks instead of 1.

**Risk:** medium-high. Largest VM by call count; force-refresh patterns and dual `cleanupOrphanedTrashDocs` calls (lines 312, 594) must be preserved as-is.

**Note:** Pre-existing patterns (e.g., the duplicate cleanup call) are NOT refactored in this PR. VM-internal hygiene is out of scope.

### PR6 — Migrate `DocumentDetailViewModel`

**Goal:** Detail screen uses 4 sub-repos directly.

**Calls migrated (10):**
- `observeDocument`, `getDocument`, `updateDocument`, `addNote`, `deleteNote` → `DocumentMetadataRepository`
- `getDocumentHistory` → `AuditRepository`
- `deleteDocument` → `TrashRepository`
- `getUsers`, `getGroups`, `updateDocumentPermissions` → `PermissionRepository`

**Changes:**
- `DocumentDetailViewModel.kt`: 4 new `@Inject` dependencies; update 10 call sites.
- `DocumentDetailViewModelTest.kt`: 4 mocks instead of 1.

**Risk:** high. Most complex screen, includes Permission flow with DTO surface — preserve exact call signatures.

### PR7 — Façade-Shrink

**Goal:** Delete all delegations from `DocumentRepository`; reduce ctor; declare Issue #51 closed.

**Pre-checks (must pass before opening PR):**
- `grep -rn "documentRepository\." app/src/main` returns matches **only** for `uploadDocument`, `uploadMultiPageDocument`, `downloadDocument`.
- `grep -rn "documentRepository\." app/src/test` reveals only DocumentRepositoryTest references for upload/download.

**Changes:**
- `DocumentRepository.kt`:
  - Delete all delegation methods (lines ~233–440 in current file).
  - Remove sub-repo ctor parameters: `count`, `metadata`, `list`, `trash`, `audit`, `permission`.
  - Final ctor matches §2 specification (10 dependencies remaining).
- `DocumentRepositoryTest.kt`: delete all delegation tests; keep only upload/download tests.
- `docs/TECHNICAL.md`: update architecture section to reflect post-#51 layout.

**Acceptance:**
- LOC ≤ 200 (target ~120).
- DocumentRepositoryTest only covers upload/download.

**Risk:** low. Pure code deletion; previous PRs proved no caller depends on the deleted methods.

---

## 4. Test strategy

**Verification pyramid per migration PR:**

1. **Compile-time gate (Hilt):** `assembleRelease` fails fast if any `@Inject` binding is missing.
2. **Caller test:** the migrated VM/Worker test gets a mock-swap. Test assertions are unchanged — only the mock targets change.
3. **Sub-repo tests** (Phases 1–4 deliverables, 69 tests): unchanged. They verify the behavior of the now-direct-called methods.
4. **DocumentRepositoryTest:** stays green for PR1–PR6 (delegation code still exists). PR7 deletes the corresponding tests.
5. **Manual smoke test per PR:** Internal-Release → install → exercise the migrated screen once.

**Behavior parity guarantee:** Each delegation in the current façade is a 1-liner of the form `subRepo.method(args)`. Direct injection produces byte-identical behavior — there is no transformation, fallback, or wrapping in the façade to preserve.

---

## 5. Risks & mitigations

| Risk | Mitigation |
|------|-----------|
| Hilt-binding error | Caught by `assembleRelease` in `validate-ci.sh` before push |
| Behavioral drift | Façade delegations are pure 1-liners — no semantic difference between `documentRepository.x(a)` and `subRepo.x(a)` |
| Test mock complexity grows | Acceptable: VMs that use 4 sub-repos genuinely depend on 4 sub-repos. Mocks reflect real architecture |
| ≥5 sub-repos in a single VM | Signal that the VM itself needs decomposition. Not in Phase 5 scope. None of the 5 VMs hit this threshold (max 4) |
| Pre-existing latent bugs (7 in memory) | NOT addressed in Phase 5. Each VM-migration PR must be a pure migration — no behavior changes — to keep CodeRabbit reviews focused |
| Flaky `AppLockManagerTest` on CI | Known timing-based flake; use `gh run rerun <run-id> --failed` |

---

## 6. Caller migration map (per-call detail)

For implementation reference. File line numbers are current on 2026-05-07.

### `UploadWorker.kt`
- L169: `documentRepository.uploadMultiPageDocument(…)` → unchanged
- L187: `documentRepository.uploadDocument(…)` → unchanged

### `PdfViewerViewModel.kt`
- L49: `documentRepository.downloadDocument(…)` → unchanged

### `TrashDeleteWorker.kt`
- L77: `permanentlyDeleteDocument(documentId)` → `trashRepository.permanentlyDeleteDocument(documentId)`

### `TrashViewModel.kt`
- L104: `observeTrashedDocuments()` → `trashRepository.observeTrashedDocuments()`
- L292: `getTrashDocuments(page, pageSize)` → `trashRepository.…`
- L312: `cleanupOrphanedTrashDocs(…)` → `trashRepository.…`
- L341: `observeTrashedDocumentsCount()` → `trashRepository.…`
- L360: `restoreDocument(documentId)` → `trashRepository.…`
- L385: `permanentlyDeleteDocument(documentId)` → `trashRepository.…`
- L430: `restoreDocuments(documentIdsToRestore)` → `trashRepository.…`
- L470: `permanentlyDeleteDocuments(documentIds)` → `trashRepository.…`

### `DocumentsViewModel.kt`
- L137: `getDocumentsPaged(…)` → `documentListRepository.…`
- L205: `observeCountWithFilter(…)` → `documentCountRepository.…`
- L242: `getDocuments(…)` → `documentListRepository.…`
- L365: `deleteDocument(documentId)` → `trashRepository.…`
- L392: `restoreDocument(deletedDoc.id)` → `trashRepository.…`

### `HomeViewModel.kt`
- L268: `observeDocuments(…)` → `documentListRepository.…`
- L360: `observeUntaggedDocumentsCount()` → `documentCountRepository.…`
- L372: `observeTrashedDocumentsCount()` → `trashRepository.…`
- L384: `observeOldestDeletedTimestamp()` → `trashRepository.…`
- L427: `getUntaggedCount()` → `documentCountRepository.…`
- L432: `getDocuments(…)` → `documentListRepository.…`
- L440: `getTrashDocuments(…)` → `trashRepository.…`
- L504: `getDocument(docId, forceRefresh = true)` → `documentMetadataRepository.…`
- L553: `getUntaggedCount()` → `documentCountRepository.…`
- L558: `getDocuments(…)` → `documentListRepository.…`
- L581: `getTrashDocuments(…)` → `trashRepository.…`
- L594: `cleanupOrphanedTrashDocs(…)` → `trashRepository.…`
- L702: `getDocumentCount(forceRefresh)` → `documentCountRepository.…`
- L707: `getDocuments(…)` → `documentListRepository.…`
- L799: `deleteDocument(documentId)` → `trashRepository.…`
- L834: `restoreDocument(deletedDoc.id)` → `trashRepository.…`
- L918: `getUntaggedDocuments()` → `documentListRepository.…`
- L1135: `updateDocument(documentId, tags = tagIds)` → `documentMetadataRepository.…`

### `DocumentDetailViewModel.kt`
- L271: `observeDocument(documentId)` → `documentMetadataRepository.…`
- L336: `getDocument(documentId, forceRefresh = true)` → `documentMetadataRepository.…`
- L350: `getDocumentHistory(documentId)` → `auditRepository.…`
- L379: `deleteDocument(documentId)` → `trashRepository.…`
- L418: `updateDocument(…)` → `documentMetadataRepository.…`
- L467: `addNote(documentId, noteText)` → `documentMetadataRepository.…`
- L498: `deleteNote(documentId, noteId)` → `documentMetadataRepository.…`
- L539: `getUsers()` → `permissionRepository.…`
- L543: `getGroups()` → `permissionRepository.…`
- L570: `updateDocumentPermissions(…)` → `permissionRepository.…`

---

## 7. Acceptance

### Per-PR

- ✅ `./scripts/validate-ci.sh` green (testReleaseUnitTest, lintRelease, assembleRelease)
- ✅ CodeRabbit actionable findings addressed (deferred OK with rationale)
- ✅ Manual smoke test of the migrated screen
- ✅ Squash-merge after green CI
- ✅ Internal-release bump via GitHub Actions

### Phase 5 (after PR7)

- ✅ `DocumentRepository.kt` ≤ 200 LOC (target ~120)
- ✅ `grep "documentRepository\."` shows only upload/download calls in `app/src`
- ✅ Ctor contains no sub-repo dependencies
- ✅ `DocumentRepositoryTest.kt` covers only upload/download
- ✅ `docs/TECHNICAL.md` reflects post-#51 layout
- ✅ Two follow-up issues opened before close:
  1. `[refactor] Remove Context from data layer (typed errors + cacheDir abstraction)`
  2. `[refactor] DTO→Domain mapping for Permission/Trash`

### Issue #51 closure

PR7 closes #171 → closes #51 (parent). Memory pointer (`MEMORY.md`) updated; resume file renamed `issue_51_phase3_3_complete.md` → `issue_51_complete.md`.

---

## 8. Out of scope (explicit)

- **Context removal** from data layer (3× `context.getString(…)` in `uploadMultiPageDocument`, 1× `context.cacheDir` in `downloadDocument`). → separate follow-up issue.
- **DTO→Domain mapping** for `PermissionRepository.getUsers/getGroups` and `TrashRepository.observeTrashedDocuments`. → separate follow-up issue.
- **Pre-existing latent bugs** (7 documented in resume memory: list-offline-asymmetry, trash Instant.parse, stale serverTrashIds, partial-state risk, empty-list early-return, cascade rollback gap, non-mutation TOCTOU). → individual issues.
- **VM-internal hygiene** (e.g., duplicate `cleanupOrphanedTrashDocs` call in `HomeViewModel`). → captured in a separate VM-cleanup issue if/when needed.
- **Compose UI changes.** Phase 5 touches only ViewModels and Workers.
- **Renaming `DocumentRepository`.** Name still fits the upload/download responsibility; renaming would force every caller's import to change for no semantic gain.

---

## 9. Architectural decisions inherited from earlier phases

These remain in force for Phase 5:

- **Hybrid pragmatic API form** — no `suspend → Flow` migrations along the way.
- **PR order: smallest scope first** within multi-PR phases.
- **Test pattern:** `mockk<Context>(relaxed = true)` for unit tests (no `androidx.test.core` on the classpath).
- **CancellationException re-throw discipline** — preserved by the `executeOrQueue` chokepoint in `DocumentSyncRepository`. Phase 5 does not touch this code.
- **JDK 21 export** required for every Bash/Gradle invocation (system JAVA_HOME → JDK 25).
- **Pre-push hook** runs `validate-ci.sh` (RELEASE variants matching GitHub Actions).
