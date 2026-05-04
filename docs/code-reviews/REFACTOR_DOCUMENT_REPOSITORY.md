# Refactor Plan — DocumentRepository Decomposition

> **Source:** [F-025](./findings/F-025-documentrepository-is-a-1349-line-god-class-with-40-mixed-r.md), [F-041](./findings/F-041-decomposition-plan-reference-refactor-roadmap.md)
> **Original:** `app/src/main/java/com/paperless/scanner/data/repository/DocumentRepository.kt` — **1349 LOC class, 40+ mixed responsibilities**
> **Target:** 8 specialized repositories + 3 service classes, each ≤ 250 LOC
> **Total effort:** ~13 dev-days, sprintable

## Why split

A single repository spans:
- PDF creation (iText)
- Image compression / sampling
- Document CRUD (single + bulk)
- Listing + paging + filtering
- Search
- Trash (soft-delete + restore + permanent delete, single + bulk)
- Permissions (users / groups)
- Notes / audit history
- Tag operations + counts
- Pending change queue (offline-first)

This wrecks SRP, makes mocking impossible, and forces every feature touch to compile a 1300-line file.

## Target architecture

### 8 Repositories

| New repository | Lines (in original) | Responsibility |
|----------------|---------------------|----------------|
| `DocumentListRepository` | 395–606 | `observeDocuments()`, `getDocumentsPaged()`, `searchDocuments()`, `observeDocumentCount()` |
| `DocumentUploadRepository` | 73–395 | `uploadDocument()`, `uploadMultiPageDocument()` (delegates to ImageProcessor + PdfGenerator) |
| `DocumentMetadataRepository` | 548–800, 998–1040 | `observeDocument()`, `getDocument()`, `updateDocument()`, `updateDocumentPermissions()` |
| `TrashRepository` | 1042–1405 | `observeTrashDocuments()`, `restore*()`, `permanentlyDelete*()`, retention helpers |
| `DocumentSyncRepository` | new | `executeOrQueue()`, `syncPendingChanges()`, server health observation |
| `AuditRepository` | 920–967 | `getDocumentHistory()`, `addNote()`, `deleteNote()` |
| `PermissionRepository` | 968–1040 | `getUsers()`, `getGroups()` |
| `DocumentCountRepository` | 414–672 | Unified `observeCount(filter, search, forceRefresh): Flow<Int>` |

### 3 Service classes

| Service | Lines (in original) | Responsibility |
|---------|---------------------|----------------|
| `ImageProcessorService` | 316–372 | URI → ByteArray, compression-quality calc, dimension probe |
| `PdfGeneratorService` | 251–315 | iText wrapper for multi-page PDF assembly |
| `DocumentSerializer` | scattered | Custom-field + tag JSON serialize/deserialize via Gson |

## Migration phases (least-risky first)

### Phase 1 — Extract services (3 days)

Pure extraction, no behavior change.

1. **ImageProcessorService** (S) — move `getImageBytesFromUri()` + `calculateCompressionQuality()`
2. **PdfGeneratorService** (S) — move `createPdfFromImages()`
3. **DocumentSerializer** (S) — centralize Gson custom-field + tag serialization

**Validation:** existing upload + tag tests unchanged.

### Phase 2 — Reactive repositories (3 days)

New repositories run alongside the old; ViewModels can switch one at a time.

4. **DocumentListRepository** (M) — extract list/search/paging methods, return `Flow<PagingData<Document>>`
5. **DocumentCountRepository** (S) — unify five count functions behind one API
6. **DocumentMetadataRepository** (M) — extract single-doc operations, switch `getDocument` to `Flow<Document?>`

**Validation:** parallel A/B (call old + new, assert equal output) before flipping consumers.

### Phase 3 — Specialized repositories (3 days)

7. **TrashRepository** (M) — move trash flows, dedupe single + bulk variants via private helpers
8. **AuditRepository** (S) — move thin API wrappers
9. **DocumentSyncRepository** (M, **highest risk**) — extract online/offline branching from `updateDocument` + trash ops; centralize PendingChange queue

**Validation:** offline mutation test, sync-on-reconnect test, concurrent-mutation test.

### Phase 4 — Permission repository (1 day)

10. **PermissionRepository** (S) — separate access-control methods, dependency direction Document → Permission

### Phase 5 — Façade + caller migration (3 days)

11. **DocumentRepository façade** (M) — keep the original class as a thin delegator so existing callers compile unchanged
12. **Gradual ViewModel migration** (L, 1–2 sprints) — high-value VMs first (`DocumentListViewModel`, `DocumentDetailViewModel`)

## Risks + mitigations

| Risk | Severity | Mitigation |
|------|----------|------------|
| Offline sync race conditions | High | DocumentSyncRepository serializes queue access with a lock; concurrent-mutation tests in CI |
| Cache invalidation drift | High | Explicit TTL + invalidation triggers per entity, documented in repo kdoc |
| Scope cancellation loses uploads | High | Move long-running uploads to WorkManager / `applicationScope`; persist intent before suspend |
| JSON serialization breaks | Medium | Snapshot test of pending-change JSON format; version PendingChange schema |
| Paging state lost on config change | Medium | Pager owns state; verify on rotation + nav-pop |
| Permission updates incomplete | Medium | Test permission mutations propagate to MetadataRepository immediately |
| Test infra fragile | Medium | Each repository has fakes for collaborators; minimize integration tests |
| Backward compatibility breaks | Low | Façade keeps signatures; deprecate methods over 2+ releases before removal |

## Recommended cadence

- **Sprint 1:** Phases 1 + 2 (parallel extraction + reactive repos with A/B validation)
- **Sprint 2:** Phases 3 + 4 (specialized repos + permission split — heaviest review)
- **Sprint 3+:** Phase 5 (gradual VM migration; low risk, can spread over time)

## Acceptance Criteria (overall)

- [ ] DocumentRepository becomes a delegating façade (≤ 200 LOC)
- [ ] Each new repository ≤ 250 LOC with single responsibility
- [ ] All existing callers compile unchanged via façade
- [ ] Each new repository has dedicated unit tests
- [ ] Phased migration committed in atomic PRs
- [ ] Documentation updated in `docs/TECHNICAL.md` reflecting the new layout
- [ ] CodeRabbit + `validate-ci.sh` green on every phase
