# Design — DocumentRepository Refactor (Issue #51, Phase 2)

**Date:** 2026-05-06
**Tracking issue:** [#51 — DocumentRepository God-class refactor](https://github.com/napoleonmm83/paperless-scanner/issues/51)
**Master plan reference:** [`docs/code-reviews/REFACTOR_DOCUMENT_REPOSITORY.md`](../../code-reviews/REFACTOR_DOCUMENT_REPOSITORY.md)
**Phase 1 spec:** [`docs/superpowers/specs/2026-05-05-document-repository-refactor-design.md`](./2026-05-05-document-repository-refactor-design.md)
**Scope of this spec:** Phase 2 only — extract three repositories (`DocumentListRepository`, `DocumentCountRepository`, `DocumentMetadataRepository`) from `DocumentRepository` while keeping it as a 1:1 façade. No public API changes, no caller migration.

---

## 1. Goal

Decompose three responsibility clusters out of `DocumentRepository.kt` (post-Phase-1: 1248 LOC) into dedicated repositories under `data/repository/`. `DocumentRepository` remains as a thin façade delegating 1:1 to the new repositories. No caller (ViewModels, Workers, SyncManager) changes.

**Phase 2 covers sub-issues #164, #165, #166** in three sequential PRs. Other sub-issues (#167–#171) remain stubs and are explicitly out-of-scope.

**Non-goals:**
- No public API changes for `DocumentRepository` in Phase 2.
- No caller migration; all 6 caller sites continue to inject `DocumentRepository`.
- No conversion of `suspend` functions to `Flow` (deferred to Phase 5).
- No full unification of the four count functions; only the two reactive Flow counts are unified internally.
- No A/B parallel-call infrastructure (façade-only pattern makes it unnecessary; Phase 1 pattern proven).

---

## 2. Sub-Issue Mapping

| Sub-issue | Phase | New repository | Effort | PR order |
|---|---|---|---|---|
| #165 | 2.2 | `DocumentCountRepository` | S | 1st (pilot) |
| #166 | 2.3 | `DocumentMetadataRepository` | M | 2nd |
| #164 | 2.1 | `DocumentListRepository` | M | 3rd |

PR order is **smallest scope first** (deviates from numeric order) so the Phase-2 pattern is validated on the lowest-risk extraction before tackling the more complex `updateDocument` (in #166) and the largest method count (in #164).

---

## 3. Architecture

### 3.1 Package layout (post-Phase 2)

```
app/src/main/java/com/paperless/scanner/data/
├── repository/
│   ├── DocumentRepository.kt              (Façade, ≤ 900 LOC after Phase 2; target reduction ≥ 350 LOC)
│   ├── DocumentListRepository.kt          NEW (~200 LOC)
│   ├── DocumentCountRepository.kt         NEW (~150 LOC)
│   ├── DocumentMetadataRepository.kt      NEW (~250 LOC)
│   └── ...other repos (TagRepository, ServerStatusRepository, …) unchanged
└── service/
    ├── ImageProcessorService.kt           (Phase 1)
    ├── PdfGeneratorService.kt             (Phase 1)
    └── DocumentSerializer.kt              (Phase 1)
```

The new classes are repositories (own DAO access + cache logic), not pure-logic services. They live alongside `TagRepository`, `ServerStatusRepository`, etc., consistent with the existing project layout.

### 3.2 Method-to-repository mapping

| Method (current line in DocumentRepository.kt) | Target repo | Sub-issue |
|---|---|---|
| `observeDocuments` (L243) | DocumentList | #164 |
| `getUntaggedDocuments` (L289) | DocumentList | #164 |
| `getDocumentsPaged` (L313) | DocumentList | #164 |
| `getDocuments` (L336) | DocumentList | #164 |
| `searchDocuments` (L459) | DocumentList | #164 |
| `getRecentDocuments` (L494) | DocumentList | #164 |
| `observeCountWithFilter` (L262) | DocumentCount | #165 |
| `observeUntaggedDocumentsCount` (L279) | DocumentCount | #165 |
| `getDocumentCount` (L468) | DocumentCount | #165 |
| `getUntaggedCount` (L515) | DocumentCount | #165 |
| `getDocument` (L396) | DocumentMetadata | #166 |
| `observeDocument` (L454) | DocumentMetadata | #166 |
| `updateDocument` (L648) | DocumentMetadata | #166 |
| `updateDocumentPermissions` (L841) | DocumentMetadata | #166 |
| `getOldTagIds` private (L735) | DocumentMetadata (private) | #166 |
| `updateTagDocumentCounts` private (L745) | DocumentMetadata (private) | #166 |

`downloadDocument` (L523) stays in `DocumentRepository` for now — semantically not part of List/Count/Metadata; classified later (likely with Upload/Download work).

### 3.3 Façade strategy

- `DocumentRepository` retains its current public/internal signatures byte-identically.
- All bodies of moved methods become one-liners: `return list.observeDocuments(page, pageSize)`, etc.
- Constructor gains 3 fields: `list: DocumentListRepository`, `count: DocumentCountRepository`, `metadata: DocumentMetadataRepository`.
- All current callers (`HomeViewModel`, `DocumentsViewModel`, `DocumentDetailViewModel`, `PdfViewerViewModel`, `TrashViewModel`, `UploadWorker`, `TrashDeleteWorker`, `SyncManager`) inject `DocumentRepository` unchanged.

---

## 4. New Repositories — Class Shapes

All three follow the existing repository pattern in this codebase: concrete class, `@Inject constructor`, `@Singleton`. No interfaces (matches `TagRepository`, `ServerStatusRepository`, the Phase-1 services).

### 4.1 DocumentListRepository (Sub-issue #164)

```kotlin
// app/src/main/java/com/paperless/scanner/data/repository/DocumentListRepository.kt
@Singleton
class DocumentListRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: PaperlessApi,
    private val cachedDocumentDao: CachedDocumentDao,
    private val networkMonitor: NetworkMonitor,
) {
    fun observeDocuments(page: Int = 1, pageSize: Int = 25): Flow<List<Document>>

    fun getDocumentsPaged(
        searchQuery: String? = null,
        filter: DocumentFilter = DocumentFilter.empty()
    ): Flow<PagingData<Document>>

    suspend fun getDocuments(
        page: Int = 1, pageSize: Int = 25, query: String? = null, tagIds: List<Int>? = null,
        correspondentId: Int? = null, documentTypeId: Int? = null,
        ordering: String = "-created", forceRefresh: Boolean = false
    ): Result<DocumentsResponse>

    suspend fun searchDocuments(query: String): Result<List<Document>>
    suspend fun getRecentDocuments(limit: Int = 5): Result<List<Document>>
    suspend fun getUntaggedDocuments(): Result<List<Document>>
}
```

**Move source:** `DocumentRepository.kt` L243–306 (`observeDocuments`, `getUntaggedDocuments`) and L313–514 (`getDocumentsPaged`, `getDocuments`, `searchDocuments`, `getRecentDocuments`).

**No behavior change** — bodies are moved byte-identically, only the enclosing class differs.

### 4.2 DocumentCountRepository (Sub-issue #165)

```kotlin
// app/src/main/java/com/paperless/scanner/data/repository/DocumentCountRepository.kt
@Singleton
class DocumentCountRepository @Inject constructor(
    private val api: PaperlessApi,
    private val cachedDocumentDao: CachedDocumentDao,
    private val networkMonitor: NetworkMonitor,
) {
    // Internal sealed class consolidates the two reactive Flow-count branches.
    private sealed class CountFilter {
        data class WithFilter(val searchQuery: String?, val filter: DocumentFilter) : CountFilter()
        data object Untagged : CountFilter()
    }

    private fun observeCountInternal(countFilter: CountFilter): Flow<Int> = when (countFilter) {
        is CountFilter.WithFilter -> {
            val query = DocumentFilterQueryBuilder.buildCountQuery(
                searchQuery = countFilter.searchQuery,
                filter = countFilter.filter
            )
            cachedDocumentDao.getCountWithFilter(query)
        }
        is CountFilter.Untagged -> cachedDocumentDao.observeUntaggedCount()
    }

    fun observeCountWithFilter(
        searchQuery: String? = null,
        filter: DocumentFilter = DocumentFilter.empty()
    ): Flow<Int> = observeCountInternal(CountFilter.WithFilter(searchQuery, filter))

    fun observeUntaggedDocumentsCount(): Flow<Int> = observeCountInternal(CountFilter.Untagged)

    // Suspend variants have divergent cache/API semantics — not consolidated.
    suspend fun getDocumentCount(forceRefresh: Boolean = false): Result<Int>  // cache-first + API fallback
    suspend fun getUntaggedCount(): Result<Int>                                // API-only via safeApiCall
}
```

**Consolidation rationale:** Only the two reactive Flow methods share semantics (pure DAO Room reads). The two suspend methods have fundamentally different strategies — `getDocumentCount` is cache-first with `forceRefresh` and API fallback; `getUntaggedCount` is API-only via `safeApiCall`. Forcing a four-way unification would either lose logic or distort it.

**Move source:** L262–286 (Flow counts) and L468–522 (suspend counts).

### 4.3 DocumentMetadataRepository (Sub-issue #166)

```kotlin
// app/src/main/java/com/paperless/scanner/data/repository/DocumentMetadataRepository.kt
@Singleton
class DocumentMetadataRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: PaperlessApi,
    private val cachedDocumentDao: CachedDocumentDao,
    private val cachedTagDao: CachedTagDao,             // for updateTagDocumentCounts
    private val pendingChangeDao: PendingChangeDao,     // PHASE-3.3 DEBT — moves to DocumentSyncRepository
    private val networkMonitor: NetworkMonitor,
    private val serverHealthMonitor: ServerHealthMonitor, // PHASE-3.3 DEBT — moves to DocumentSyncRepository
    private val serializer: DocumentSerializer,
) {
    fun observeDocument(id: Int): Flow<Document?>
    suspend fun getDocument(id: Int, forceRefresh: Boolean = false): Result<Document>

    suspend fun updateDocument(
        documentId: Int, title: String? = null, tags: List<Int>? = null,
        correspondent: Int? = null, documentType: Int? = null,
        archiveSerialNumber: Int? = null, created: String? = null
    ): Result<Document>

    suspend fun updateDocumentPermissions(
        documentId: Int, owner: Int?,
        viewUsers: List<Int>, viewGroups: List<Int>,
        changeUsers: List<Int>, changeGroups: List<Int>
    ): Result<Document>

    private suspend fun getOldTagIds(documentId: Int): List<Int>
    private suspend fun updateTagDocumentCounts(oldTagIds: List<Int>, newTagIds: List<Int>)
}
```

**⚠️ Phase-3.3 debt (acknowledged):** `updateDocument` contains inline PendingChange offline-queue logic (current L702–732). This logic semantically belongs to the future `DocumentSyncRepository` (sub-issue #169, master-plan Phase 3.3). Phase 2 moves it **with** `updateDocument` into `DocumentMetadataRepository` for consistency. Inline `// PHASE-3.3:` comments mark the offending lines so the future `executeOrQueue { ... }` extraction is straightforward. This is why `DocumentMetadataRepository` temporarily depends on `pendingChangeDao` and `serverHealthMonitor` — both dependencies will be removed in Phase 3.3 and replaced with a single `DocumentSyncRepository` injection.

**Move source:** L396–457 (`getDocument`, `observeDocument`), L648–760 (`updateDocument` + private helpers `getOldTagIds`, `updateTagDocumentCounts`), L841–872 (`updateDocumentPermissions`).

### 4.4 DocumentRepository constructor (post-Phase 2)

```kotlin
class DocumentRepository @Inject constructor(
    // 13 existing deps (incl. Phase-1 services) ...
    private val list: DocumentListRepository,        // +
    private val count: DocumentCountRepository,      // +
    private val metadata: DocumentMetadataRepository // +
)
```

Constructor grows by exactly 3 parameters. No removal of existing deps in Phase 2 (those deps are still used by the methods that remain in `DocumentRepository`: upload, trash, audit, users, groups, downloadDocument).

---

## 5. PR Sequence and Dependencies

| # | Branch | Sub-issue | Approx LOC moved | Depends on |
|---|---|---|---|---|
| 1 | `refactor/51-extract-document-count`     | #165 | ~110 | none |
| 2 | `refactor/51-extract-document-metadata`  | #166 | ~250 | PR 1 merged |
| 3 | `refactor/51-extract-document-list`      | #164 | ~200 | PR 2 merged |

**Sequential, not parallel.** Each PR adds exactly one parameter to `DocumentRepository`'s constructor and to `provideDocumentRepository` in `AppModule.kt`. Two parallel PRs would conflict in both files. Phase 1 used the same sequential strategy with zero conflicts.

Each PR follows the project's standard release flow (auto-deploy on merge to `main` per CLAUDE.md release section).

---

## 6. Hilt Wiring

`AppModule.kt:333-344` (`provideDocumentRepository`) gains one parameter per PR. Hilt resolves the new repos automatically because each has `@Inject constructor` + `@Singleton`. No new `@Provides` functions, no `@Module`/`@InstallIn` changes — identical pattern to Phase 1.

```kotlin
@Provides
@Singleton
fun provideDocumentRepository(
    // ...all 13 existing args
    list: DocumentListRepository,
    count: DocumentCountRepository,
    metadata: DocumentMetadataRepository,
): DocumentRepository = DocumentRepository(/* ..., */ list, count, metadata)
```

---

## 7. Testing Strategy

### 7.1 Existing safety net

`DocumentRepositoryTest.kt` (402 LOC) MUST stay green on every PR. Because the façade keeps its public API and only delegates, any drift in delegation behavior fails fast. No new assertions are added there.

### 7.2 New test files (per repository, dedicated)

| Test file | Framework | Min cases | Coverage targets |
|---|---|---|---|
| `DocumentCountRepositoryTest.kt`    | Robolectric + mockk | 8  | both `CountFilter` branches; `getDocumentCount` online with `forceRefresh=true`; `getDocumentCount` offline with cache; `getDocumentCount` cache-empty + offline → `Result.success(0)`; `getUntaggedCount` happy + `safeApiCall` error |
| `DocumentMetadataRepositoryTest.kt` | Robolectric + mockk | 12 | `observeDocument`; `getDocument` online; `getDocument` offline with cache hit; `getDocument` offline with no cache; `updateDocument` online; `updateDocument` offline → PendingChange queued; `updateDocument` with tag-count delta; `updateDocumentPermissions` online + offline; `getOldTagIds` null + parsable; permission update cache-write |
| `DocumentListRepositoryTest.kt`     | Robolectric + mockk | 10 | `observeDocuments`; `getDocumentsPaged` with filter; `getDocuments` cache hit; `getDocuments` `forceRefresh` + network; `getDocuments` offline + no cache; `searchDocuments`; `getRecentDocuments` cache + network fallback; `getUntaggedDocuments` |

**Total: 30 new tests across 3 files**, mirror package: `app/src/test/java/com/paperless/scanner/data/repository/`.

### 7.3 Manual on-device smoke (pre-merge, per PR)

| PR | Smoke steps |
|---|---|
| #165 (Count) | Documents screen → set filter → verify count display; Smart-Tagging screen → verify untagged count |
| #166 (Metadata) | Document Detail → change tag/title → sync; offline update (Airplane mode) → reconnect → sync; permissions update |
| #164 (List) | Documents list scroll (paging), search, filter, untagged list |

### 7.4 CI validation per PR

| Check | When | Command |
|---|---|---|
| Existing repo tests green   | Before push | `./gradlew testReleaseUnitTest` |
| New repo tests green        | Before push | `./gradlew :app:testReleaseUnitTest --tests *<NewClass>Test*` |
| Lint clean                  | Before push | `./gradlew lintRelease` |
| Release build green         | Before push | `./gradlew assembleRelease` |
| Full local CI               | Before push | `./scripts/validate-ci.sh` (RELEASE variants per CLAUDE.md) |
| Manual on-device smoke      | Before merge | per table above |
| CodeRabbit                  | After push | Round-1 findings actioned or skipped with explicit rationale |

---

## 8. Risks and Mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| `updateDocument` offline-queue logic temporarily lives in the "wrong" repo (`DocumentMetadata` instead of future `DocumentSync`) | Medium | Phase-3.3 debt explicitly documented in this spec; inline `// PHASE-3.3:` comments mark the offending lines for easy future extraction via `DocumentSyncRepository.executeOrQueue { ... }`. |
| Hilt constructor merge conflict with parallel PRs | Medium | PRs strictly sequential (#165 → #166 → #164); each PR rebases on `main` immediately before merge. |
| `DocumentRepositoryTest.kt` doesn't exercise PDF/image paths and would miss drift in the new repos | Medium | Dedicated 30 new tests in the 3 new test files are the real safety net. Existing test verifies façade delegation as an additional layer. |
| `getDocumentsPaged` Pager caching breaks across scope change (repo constructed in different lifecycle) | Low | `@Singleton` scope retained; Pager is instantiated per call as today — no behavior change. Manual smoke in PR #164 covers paging behavior. |
| `CountFilter` sealed class collides with another `CountFilter` type elsewhere | Low | Class is `private` inside `DocumentCountRepository` — no external visibility, no collision. |
| Workers (`UploadWorker`, `TrashDeleteWorker`, `SyncManager`) behave differently after refactor | Low | Workers continue to inject `DocumentRepository` (façade), not the new repos. Façade is 1:1 transparent. |
| CodeRabbit ASSERTIVE finds pre-existing bugs in the moved methods (as in Phase 1) | Low (welcome) | Apply Phase-1 pattern: action small real bugs (≤5 LOC) inside the move PR; reject architectural changes (Dispatchers.IO, interfaces) with rationale referencing the out-of-scope section. |

---

## 9. Out-of-Scope (Explicit)

- **Phase 3.1 `TrashRepository` (#167)** — trash methods L885–1248 stay in `DocumentRepository`.
- **Phase 3.2 `AuditRepository` (#168)** — `getDocumentHistory`, `addNote`, `deleteNote` stay in `DocumentRepository`.
- **Phase 3.3 `DocumentSyncRepository` (#169)** — offline-queue logic in `updateDocument` moves with it into `DocumentMetadata`; extraction deferred. **Highest-risk phase per master plan; needs its own brainstorm + spec.**
- **Phase 4 `PermissionRepository` (#170)** — `getUsers`, `getGroups` stay in `DocumentRepository` (`updateDocumentPermissions` does move to `DocumentMetadata` because the document-mutation operation belongs there).
- **Phase 5 façade cleanup + ViewModel migration (#171)** — no caller is touched in Phase 2.
- **`downloadDocument` (L523)** — stays in `DocumentRepository`; semantic placement is open (likely paired with upload work).
- **API-form changes** — no `suspend` function is converted to `Flow` (decision logged in this spec, Section 4).
- **Full unification of the four count methods** — only the two reactive Flow counts are unified internally; suspend counts remain separate due to divergent cache/API semantics.
- **A/B parallel-call infrastructure** — façade-only pattern makes it unnecessary; existing tests + new dedicated tests are the safety net.

---

## 10. Rollback Strategy

Phase 2 is purely internal extraction — no public API change, no schema change, no migration. Each PR is reverted by a single `git revert <sha>` which restores the methods to `DocumentRepository.kt` and removes the new repository file. No data risk.

---

## 11. Acceptance Criteria for Phase 2 (overall, across the 3 PRs)

- [ ] 3 new files at `data/repository/` with `@Inject constructor` + `@Singleton`
- [ ] `DocumentRepository.kt` reduced by ≥ 350 LOC (1248 → ≤ 900)
- [ ] `DocumentRepository.kt` constructor extended by exactly 3 fields (`list`, `count`, `metadata`)
- [ ] All 14 affected methods (Section 3.2) are one-line delegations in `DocumentRepository` after Phase 2
- [ ] `DocumentRepositoryTest.kt` green on every PR (existing API unchanged)
- [ ] 30 new tests across 3 new test files, all green
- [ ] `./scripts/validate-ci.sh` green before each push (RELEASE variants)
- [ ] CodeRabbit ASSERTIVE findings per PR actioned or skipped with rationale (link to out-of-scope section)
- [ ] All 3 sub-issues (#164, #165, #166) merged via "Closes #..."
- [ ] #51 parent-issue checklist updated to reflect Phase 2 done; #167–#171 remain open as stubs
- [ ] Manual on-device smoke per PR completed (Section 7.3)
- [ ] Inline `// PHASE-3.3:` markers in `DocumentMetadataRepository` flag the offline-queue extraction points for the future Phase-3.3 work
