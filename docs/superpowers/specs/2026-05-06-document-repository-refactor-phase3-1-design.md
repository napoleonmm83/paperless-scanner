# Design — DocumentRepository Refactor (Issue #51, Phase 3.1)

**Date:** 2026-05-06
**Tracking issue:** [#51 — DocumentRepository God-class refactor](https://github.com/napoleonmm83/paperless-scanner/issues/51)
**Master plan reference:** [`docs/code-reviews/REFACTOR_DOCUMENT_REPOSITORY.md`](../../code-reviews/REFACTOR_DOCUMENT_REPOSITORY.md)
**Phase 1 spec:** [`docs/superpowers/specs/2026-05-05-document-repository-refactor-design.md`](./2026-05-05-document-repository-refactor-design.md)
**Phase 2 spec:** [`docs/superpowers/specs/2026-05-06-document-repository-refactor-phase2-design.md`](./2026-05-06-document-repository-refactor-phase2-design.md)
**Scope of this spec:** Phase 3.1 only — extract `TrashRepository` from `DocumentRepository`. Single PR. Façade-only pattern continues from Phase 2. Sub-issue #167.

---

## 1. Goal

Extract all trash-related operations and `deleteDocument` (the soft-delete that puts a document into trash) from `DocumentRepository.kt` (post-Phase-2: 935 LOC) into a dedicated `TrashRepository` under `data/repository/`. Apply two side-cleanups inside the same PR:

1. Delete two confirmed-dead methods (`observeTrashDocuments`, `observeTrashCount`) — duplicates of `observeTrashedDocuments`/`observeTrashedDocumentsCount` with zero callers in the codebase.
2. Internally consolidate the four single+bulk mutation pairs (`restoreDocument`/`restoreDocuments` and `permanentlyDeleteDocument`/`permanentlyDeleteDocuments`): single methods become 1-line wrappers that delegate to the bulk variant with a `listOf(id)`. The bulk variant is the canonical implementation.

`DocumentRepository` remains a 1:1 façade: every public/internal signature stays byte-identical and delegates to `trash`. No caller (ViewModels, Workers, SyncManager) is touched.

**Non-goals:**
- No public API changes; no caller migration (Phase 5 territory).
- No A/B parallel-call infrastructure (façade-only pattern proven in Phase 1 + 2).
- No `Context` removal from data layer (Phase 5 cleanup).
- No extraction of the offline-queue logic — `deleteDocument` and the restore/permanent-delete offline branches keep their inline `PendingChange` writes; they are flagged as Phase-3.3 debt for future `DocumentSyncRepository.executeOrQueue { ... }` extraction.
- No mapping `observeTrashedDocuments: Flow<List<CachedDocument>>` to a domain type — the entity escape is pre-existing and breaking it is a caller-breaking change (Phase 5).

---

## 2. Scope

Phase 3.1 covers sub-issue **#167** in a single PR.

**In:**
- New file `data/repository/TrashRepository.kt`
- New test file `data/repository/TrashRepositoryTest.kt` with 15 cases
- Modify `DocumentRepository.kt` to delegate
- Extend `AppModule.provideDocumentRepository`
- Update `DocumentRepositoryTest.kt` minimally (17th constructor arg)
- Delete two dead methods

**Out:**
- Phases 3.2 (#168), 3.3 (#169), 4 (#170), 5 (#171) — see Phase 2 spec §9 for the full scope boundaries.

---

## 3. Architecture

### 3.1 Package layout (post-Phase 3.1)

```
app/src/main/java/com/paperless/scanner/data/
├── repository/
│   ├── DocumentRepository.kt              (Façade, ≤ 585 LOC after Phase 3.1; target reduction ≥ 350 LOC)
│   ├── DocumentListRepository.kt          (Phase 2.1)
│   ├── DocumentCountRepository.kt         (Phase 2.2)
│   ├── DocumentMetadataRepository.kt      (Phase 2.3)
│   ├── TrashRepository.kt                 NEW (~280 LOC)
│   └── ...other repos unchanged
└── service/                               (Phase 1, unchanged)
```

### 3.2 Method-to-repository mapping

| Method (current line in DocumentRepository.kt) | Action | Notes |
|---|---|---|
| `deleteDocument` (L375) | → TrashRepository | Phase-3.3 debt: offline-queue branch + cascade-task-ack logic |
| `observeTrashDocuments` (L572) | 🗑️ **DELETE** | Dead code, 0 callers; duplicate of `observeTrashedDocuments` |
| `observeTrashCount` (L583) | 🗑️ **DELETE** | Dead code, 0 callers; duplicate of `observeTrashedDocumentsCount` |
| `getTrashDocuments` (L595) | → TrashRepository | network fetch + cache update |
| `restoreDocument` (L641) | → TrashRepository as 1-line wrapper | Delegates to `restoreDocuments(listOf(id))` |
| `restoreDocuments` (L699) | → TrashRepository (canonical) | Online API + offline `PendingChange` per id |
| `permanentlyDeleteDocument` (L759) | → TrashRepository as 1-line wrapper | Delegates to `permanentlyDeleteDocuments(listOf(id))` |
| `permanentlyDeleteDocuments` (L817) | → TrashRepository (canonical) | Online API + offline `PendingChange` per id |
| `getOldDeletedDocumentIds` (L877) | → TrashRepository | Retention helper |
| `observeTrashedDocuments` (L897) | → TrashRepository | Entity-typed Flow (preserve byte-identical) |
| `observeTrashedDocumentsCount` (L907) | → TrashRepository | Flow read |
| `observeOldestDeletedTimestamp` (L917) | → TrashRepository | Flow read for "Expires in X days" |
| `cleanupOrphanedTrashDocs` (L927) | → TrashRepository | Post-sync consistency |

**Counts:** 11 methods migrate, 2 dead-code methods deleted, 4 mutation methods consolidated single→bulk internally.

### 3.3 Façade strategy
- `DocumentRepository` retains its current public/internal signatures byte-identically.
- The bodies of moved methods become one-liners: `return trash.deleteDocument(id)`, etc.
- Constructor gains 1 field: `trash: TrashRepository` at position 17.
- All current callers (`HomeViewModel`, `DocumentsViewModel`, `DocumentDetailViewModel`, `TrashViewModel`, `SwipeableDocumentCardContainer`, `TrashDeleteWorker`, `SyncManager`, `DocumentDetailScreen`, `DocumentsScreen`, `TrashScreen`) inject `DocumentRepository` unchanged.

---

## 4. New Repository — Class Shape

```kotlin
// app/src/main/java/com/paperless/scanner/data/repository/TrashRepository.kt
@Singleton
class TrashRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: PaperlessApi,
    private val cachedDocumentDao: CachedDocumentDao,
    private val cachedTaskDao: CachedTaskDao,                  // for deleteDocument cascade
    private val pendingChangeDao: PendingChangeDao,            // PHASE-3.3 debt
    private val networkMonitor: NetworkMonitor,
) {

    // ===== Soft-delete (Phase-3.3 debt at offline branch) =====

    suspend fun deleteDocument(documentId: Int): Result<Unit>
    // online: cascade-task-ack → optimistic softDelete → API → rollback on fail
    // offline: PendingChange + cascade-task-ack + optimistic softDelete
    // // PHASE-3.3: extract via DocumentSyncRepository.executeOrQueue { ... }

    // ===== Reads =====

    fun observeTrashedDocuments(): Flow<List<CachedDocument>>
    fun observeTrashedDocumentsCount(): Flow<Int>
    fun observeOldestDeletedTimestamp(): Flow<Long?>
    suspend fun getTrashDocuments(page: Int = 1, pageSize: Int = 25): Result<DocumentsResponse>

    // ===== Restore (single delegates to bulk) =====

    suspend fun restoreDocument(documentId: Int): Result<Unit> =
        restoreDocuments(listOf(documentId))

    suspend fun restoreDocuments(documentIds: List<Int>): Result<Unit>
    // canonical: TrashBulkActionRequest + cache update + offline-queue fallback
    // // PHASE-3.3: extract via DocumentSyncRepository.executeOrQueue { ... }

    // ===== Permanent Delete (single delegates to bulk) =====

    suspend fun permanentlyDeleteDocument(documentId: Int): Result<Unit> =
        permanentlyDeleteDocuments(listOf(documentId))

    suspend fun permanentlyDeleteDocuments(documentIds: List<Int>): Result<Unit>
    // canonical: TrashBulkActionRequest + hard cache delete + offline-queue fallback
    // // PHASE-3.3: extract via DocumentSyncRepository.executeOrQueue { ... }

    // ===== Maintenance helpers =====

    suspend fun getOldDeletedDocumentIds(retentionDays: Int = 30): Result<List<Int>>
    suspend fun cleanupOrphanedTrashDocs(serverTrashIds: Set<Int>)
}
```

### 4.1 Constructor dependency rationale

| Dep | Why |
|---|---|
| `Context` | Error-string resolution (`R.string.error_offline`) — pre-existing pattern; Phase 5 cleanup |
| `PaperlessApi` | `api.deleteDocument`, `api.acknowledgeTasks`, `api.trashBulkAction`, `api.getTrash` |
| `CachedDocumentDao` | Trash Flow reads, `softDelete`, `restoreDocument(s)`, hard-delete pendant, `getOldDeletedDocumentIds`, `cleanupOrphanedTrashDocs` |
| `CachedTaskDao` | Only for `deleteDocument` cascade (`acknowledgeTasksForDocument` + `getAllTasks` filter) |
| `PendingChangeDao` | **PHASE-3.3 DEBT**: offline queue for delete/restore/permanentlyDelete |
| `NetworkMonitor` | online/offline branching in all mutations |

### 4.2 Phase-3.3 debt documentation
- Class-level KDoc explicitly notes the 3 offline-branch debt items.
- Per-field comments mark `pendingChangeDao` and the network/health-related deps as Phase-3.3 debt.
- Inline `// PHASE-3.3: extract via DocumentSyncRepository.executeOrQueue { ... }` markers at exactly 3 locations: `deleteDocument` offline branch, `restoreDocuments` offline branch, `permanentlyDeleteDocuments` offline branch.

This is consistent with the same pattern used in `DocumentMetadataRepository` (Phase 2.3) and lets Phase 3.3 (#169) locate every extraction point with `grep -rn 'PHASE-3.3:'`.

### 4.3 DocumentRepository façade (post-Phase 3.1)

```kotlin
class DocumentRepository @Inject constructor(
    // ...all 16 existing deps unchanged...
    private val list: DocumentListRepository,
    private val count: DocumentCountRepository,
    private val metadata: DocumentMetadataRepository,
    private val trash: TrashRepository,                    // +
)
```

Constructor grows by exactly 1 parameter (position 17). The 11 façade methods become one-line delegations to `trash.*`. Imports cleaned for types no longer referenced (`TrashBulkActionRequest`, `AcknowledgeTasksRequest` likely unused after the move; verify before removing).

---

## 5. PR Sequence and Dependencies

| # | Branch | Sub-issue | Approx LOC moved |
|---|---|---|---|
| 1 | `refactor/51-extract-trash-repository` | #167 | ~280 (move) + ~12 (delete dead code) + ~110 (consolidation savings) |

Single PR. The other Phase-3 sub-issues (#168 audit, #169 sync, #170 permission, #171 façade cleanup) are separate sessions with their own brainstorm + spec.

---

## 6. Hilt Wiring

`AppModule.kt:provideDocumentRepository` gains `trash: TrashRepository,` parameter (passed to constructor). `TrashRepository` itself has `@Inject constructor` + `@Singleton` — Hilt auto-discovery, no `@Provides` function needed.

```kotlin
@Provides
@Singleton
fun provideDocumentRepository(
    // ...all 16 existing args
    trash: TrashRepository,                             // NEW
): DocumentRepository = DocumentRepository(
    // ...existing args passed through
    trash,                                              // NEW
)
```

Add the import:
```kotlin
import com.paperless.scanner.data.repository.TrashRepository
```

---

## 7. Testing Strategy

### 7.1 Existing safety net

`DocumentRepositoryTest.kt` (currently 16 ctor args after Phase 2) extends to 17 args. Stays green via façade delegation. No tests added/removed there.

### 7.2 New test file: `TrashRepositoryTest.kt` — 15 cases

| # | Test name | Covers |
|---|---|---|
| 1 | `observeTrashedDocuments delegates to dao observeDeletedDocuments` | Flow read |
| 2 | `observeTrashedDocumentsCount delegates to dao observeDeletedCount` | Flow read |
| 3 | `observeOldestDeletedTimestamp delegates to dao` | Flow read |
| 4 | `getTrashDocuments online inserts cache and returns response` | suspend network |
| 5 | `getTrashDocuments offline returns NetworkError` | suspend offline |
| 6 | `deleteDocument online happy path soft-deletes locally then API succeeds` | delete online |
| 7 | `deleteDocument online API failure rolls back optimistic delete` | delete rollback |
| 8 | `deleteDocument online cascade-acknowledges tasks for document` | delete cascade |
| 9 | `deleteDocument offline writes PendingChange and softDeletes` | delete offline |
| 10 | `restoreDocument single delegates to bulk with one-element list` | consolidation contract |
| 11 | `restoreDocuments online happy path` | restore canonical |
| 12 | `restoreDocuments offline writes one PendingChange per id` | restore offline |
| 13 | `permanentlyDeleteDocument single delegates to bulk` | consolidation contract |
| 14 | `permanentlyDeleteDocuments online + offline branches` | permanent delete |
| 15 | `cleanupOrphanedTrashDocs removes locally-deleted-but-not-on-server` | maintenance |

`getOldDeletedDocumentIds` is a 1-call suspend wrapper over `cachedDocumentDao.getOldDeletedDocumentIds(retentionDays)`; covered indirectly by the maintenance flow if needed, otherwise omitted to stay at 15 cases.

**Frameworks:** Robolectric + mockk relaxed; `coEvery`/`every`; `slot<PendingChange>()` for offline-queue JSON assertions; `coVerify(exactly = 0|1|N)` for cascade verification.

**Total dedicated tests after Phase 3.1:** 8 (Count) + 12 (Metadata) + 11 (List) + 15 (Trash) = **46 tests** across 4 files.

### 7.3 Manual on-device smoke (pre-merge)

1. Document löschen via Swipe (Gmail-style animation, soft-delete sichtbar im Trash)
2. Offline löschen (Airplane mode) → online gehen → Sync zeigt Server-Übereinstimmung
3. Trash-Screen → restore single → restore bulk
4. Trash-Screen → permanently delete single → permanently delete bulk
5. Home-Screen "Expires in X days" Anzeige (`observeOldestDeletedTimestamp`)

### 7.4 CI validation

| Check | When | Command |
|---|---|---|
| Existing repo tests green | Before push | `./gradlew :app:testReleaseUnitTest --tests "*DocumentRepositoryTest*"` |
| New trash tests green | Before push | `./gradlew :app:testReleaseUnitTest --tests "*TrashRepositoryTest*"` |
| Lint clean | Before push | `./gradlew lintRelease` |
| Release build | Before push | `./gradlew assembleRelease` |
| Full local CI | Before push | `./scripts/validate-ci.sh` (RELEASE variants per CLAUDE.md) |
| Manual on-device smoke | Before merge | per §7.3 |
| CodeRabbit | After push | Round-1 findings actioned or skipped with explicit rationale |

---

## 8. Risks and Mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| `restoreDocument(id)` → `restoreDocuments(listOf(id))` consolidation subtly changes DAO cache behavior (`cachedDocumentDao.restoreDocument(id)` vs `restoreDocuments(ids)`) | Medium | Bulk DAO method works for 1-element lists (SQL `UPDATE ... WHERE id IN (...)` semantics). Tests #10 + #11 verify both paths. Same applies to `permanentlyDelete*`. |
| `deleteDocument` cascade (task acknowledgment + rollback on API failure) is complex and fragile during the move | High | Move byte-identically; tests #6 + #7 + #8 cover all 3 paths explicitly. Optimistic-UI ordering MUST be preserved (`softDelete` BEFORE API call). |
| `cachedTaskDao` ends up in TrashRepository solely for `deleteDocument` cascade — feels overscoped | Low | Accepted: pre-existing pattern. Phase 3.3 may centralize the cascade via `executeOrQueue`. Class KDoc notes the `cachedTaskDao` is delete-cascade-only. |
| Phase-3.3 debt grows (3 offline branches in TrashRepository + 2 in DocumentMetadata = 5 total) | Medium | Inline `// PHASE-3.3:` markers + class-level KDoc document the debt centrally. Phase 3.3 spec must enumerate all 5 extraction points. |
| Dead-code removal of `observeTrashDocuments`/`observeTrashCount` breaks a hidden caller (reflection / DI lookup) | Very Low | Grep on `data/`, `ui/`, `worker/`, `test/` shows zero hits. CI lint+compile catches every static caller. No reflection in this codebase. |
| CodeRabbit ASSERTIVE finds pre-existing bugs in moved code | Low (welcome) | Phase-1 pattern: action small real bugs (≤5 LOC) inline; reject architectural changes with rationale referencing §9. |

---

## 9. Out-of-Scope (Explicit)

- **Phase 3.2 `AuditRepository` (#168)** — `getDocumentHistory`, `addNote`, `deleteNote` stay in DocumentRepository.
- **Phase 3.3 `DocumentSyncRepository` (#169)** — `executeOrQueue` extraction across all 5 offline branches (delete + 2× updateDocument debt from #166 + restoreDocuments + permanentlyDeleteDocuments). HIGHEST RISK per master plan, own brainstorm + concurrent-mutation tests.
- **Phase 4 `PermissionRepository` (#170)** — `getUsers`, `getGroups` stay in DocumentRepository.
- **Phase 5 (#171)** — no caller migration; ViewModels + Workers + SyncManager continue to inject `DocumentRepository`.
- **`uploadDocument` / `uploadMultiPageDocument` / `downloadDocument`** — stay in DocumentRepository (separate Upload/Download phase or sub-issue).
- **`observeTrashedDocuments` typing migration** — keeps `Flow<List<CachedDocument>>` byte-identical; mapping to a domain type is caller-breaking and belongs to Phase 5.
- **Strict error-string-in-data-layer cleanup** — `Context` injection stays per pre-existing pattern; Phase 5 territory.
- **Test infrastructure changes** (Turbine, paging-testing, etc.) — not in this PR.

---

## 10. Rollback Strategy

Phase 3.1 is purely internal extraction + dead-code removal + internal consolidation — no public API change, no schema change, no migration. The PR is reverted by a single `git revert <sha>` which restores the methods to `DocumentRepository.kt`, restores the two deleted dead methods, and removes the new `TrashRepository.kt`. No data risk.

---

## 11. Acceptance Criteria

- [ ] 1 new file `app/src/main/java/com/paperless/scanner/data/repository/TrashRepository.kt` with `@Inject constructor` + `@Singleton`
- [ ] `DocumentRepository.kt` reduced by ≥ 350 LOC (935 → ≤ 585)
- [ ] `DocumentRepository.kt` constructor extended by exactly 1 field (`trash`) at position 17
- [ ] 11 affected façade methods are one-line delegations to `trash.*`
- [ ] 2 dead-code methods (`observeTrashDocuments`, `observeTrashCount`) removed without replacement
- [ ] `restoreDocument` and `permanentlyDeleteDocument` are 1-line wrappers delegating to their bulk counterparts
- [ ] `DocumentRepositoryTest.kt` green on every push (existing API unchanged; minimal 17th ctor arg update)
- [ ] 15 new tests in `TrashRepositoryTest.kt`, all green
- [ ] `./scripts/validate-ci.sh` green before push (RELEASE variants)
- [ ] CodeRabbit ASSERTIVE findings actioned or skipped with rationale (link to §9)
- [ ] Sub-issue #167 merged via "Closes #167"
- [ ] Inline `// PHASE-3.3:` markers at exactly 3 offline-branch sites + class-level KDoc documenting the debt
- [ ] Manual on-device smoke per §7.3 completed
- [ ] Memory file `issue_51_phase2_complete.md` updated (or renamed to `issue_51_phase3_1_complete.md`) to mark Phase 3.1 done with PR SHA; MEMORY.md pointer updated
