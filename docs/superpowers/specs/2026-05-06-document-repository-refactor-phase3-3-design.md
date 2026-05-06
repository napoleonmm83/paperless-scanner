# Design — DocumentRepository Refactor (Issue #51, Phase 3.3)

**Date:** 2026-05-06
**Tracking issue:** [#51 — DocumentRepository God-class refactor](https://github.com/napoleonmm83/paperless-scanner/issues/51)
**Predecessor specs:** Phases 1, 2, 3.1, 3.2, 4 (all merged).
**Scope of this spec:** Phase 3.3 — extract `DocumentSyncRepository`. Centralizes the offline-queue + serverHealth pattern that lives inline as Phase-3.3 debt across `DocumentMetadataRepository` (1 site) and `TrashRepository` (3 sites). Fixes 2 production bugs as part of the move. Single PR, sub-issue #169. **HIGHEST RISK phase per master plan.**

---

## 1. Goal

Replace 4 inline offline-queue branches and 2 production bugs with a single `DocumentSyncRepository` that owns:

1. **`executeOrQueue<T>(online, offlineQueueAndOptimistic): Result<T>`** — snapshots `serverHealthMonitor.isServerReachable.value` once, runs `online`; on `IOException` (mid-flight network drop) falls back to `offlineQueueAndOptimistic`. HttpException (4xx/5xx) does NOT trigger fallback (server responded; queue would re-fail).
2. **Typed queue helpers** — `queueDocumentUpdate(id, payload)`, `queueDocumentDelete(id)`, `queueTrashAction(ids, action)` — each writes the correct `PendingChange` shape via `gson.toJson(...)`, removing the JSON-injection bug at the producer.

Two production bugs fixed:
- **JSON injection** in `updateDocument` offline branch (currently `buildString { append("\"title\":\"$it\"") }` — fails for titles with embedded `"`).
- **TOCTOU race** on `serverHealthMonitor.isServerReachable.value` — currently read once, no fallback if it flips during the API call → user edit lost. New `executeOrQueue` recovers via IOException-fallback.

`DocumentRepository` (the public façade) is NOT touched in Phase 3.3 — the change is purely between the new repos. Caller code (ViewModels) is NOT touched.

**Non-goals:**
- No public API changes on `DocumentRepository`, `DocumentMetadataRepository`, or `TrashRepository`.
- No `SyncManager` changes — its `pushDocumentChange` parser already uses `gson.fromJson(Map::class.java)` and reads camelCase keys (`documentType`, `archiveSerialNumber`, `created`), which `gson.toJson(DocumentUpdatePayload)` produces by default.
- No retry-policy changes for PendingChange sync.

---

## 2. Scope

| Sub-issue | Phase | Effort |
|---|---|---|
| #169 | 3.3 | M (HIGHEST RISK) |

In: 1 new file (`DocumentSyncRepository.kt`), 1 new test file (`DocumentSyncRepositoryTest.kt`), modify 2 existing repos (`DocumentMetadataRepository`, `TrashRepository`), update 2 existing test files (mock `pendingChangeDao` → `DocumentSyncRepository` mock).

Out: Phase 5 (#171 — façade cleanup, ViewModel migration, DTO→domain).

---

## 3. Architecture

### 3.1 Package layout (post-Phase 3.3)

```
app/src/main/java/com/paperless/scanner/data/repository/
├── DocumentRepository.kt              (Façade, unchanged in Phase 3.3 — same 19 ctor deps)
├── DocumentListRepository.kt
├── DocumentCountRepository.kt
├── DocumentMetadataRepository.kt      (modify: pendingChangeDao + serverHealthMonitor deps removed; sync added)
├── TrashRepository.kt                 (modify: pendingChangeDao dep removed; sync added)
├── AuditRepository.kt
├── PermissionRepository.kt
├── DocumentSyncRepository.kt          NEW (~150 LOC)
└── ...other repos unchanged
```

**`DocumentRepository` constructor remains 19 deps** — `pendingChangeDao` and `serverHealthMonitor` were already moved to DocumentMetadata/Trash in Phases 2.3/3.1; they continue to be injected into DocumentRepository (cosmetic Phase-5 cleanup target). The Phase-3.3 change is **internal to DocumentMetadata + Trash + new DocumentSync** only.

### 3.2 Migration sites

| File | Method | Phase-3.3 marker | Action |
|---|---|---|---|
| DocumentMetadataRepository.kt | `updateDocument` | L93 (TOCTOU FIXME) | replaced via `sync.executeOrQueue` snapshot+fallback |
| DocumentMetadataRepository.kt | `updateDocument` | L113 (PHASE-3.3) | offline branch → `sync.queueDocumentUpdate` |
| DocumentMetadataRepository.kt | `updateDocument` | L114 (JSON-injection FIXME) | replaced via `gson.toJson(DocumentUpdatePayload)` inside `queueDocumentUpdate` |
| TrashRepository.kt | `deleteDocument` | L99 (PHASE-3.3) | wrapped in `executeOrQueue`; offline branch uses `queueDocumentDelete` |
| TrashRepository.kt | `restoreDocuments` | L196 (PHASE-3.3) | wrapped in `executeOrQueue`; offline branch uses `queueTrashAction(RESTORE)` |
| TrashRepository.kt | `permanentlyDeleteDocuments` | L248 (PHASE-3.3) | wrapped in `executeOrQueue`; offline branch uses `queueTrashAction(PERMANENT_DELETE)` |

After this phase, `grep -rn "PHASE-3.3:\|FIXME (#169):"` returns 0 hits.

---

## 4. New Repository — Class Shape

```kotlin
// app/src/main/java/com/paperless/scanner/data/repository/DocumentSyncRepository.kt
@Singleton
class DocumentSyncRepository @Inject constructor(
    private val pendingChangeDao: PendingChangeDao,
    private val serverHealthMonitor: ServerHealthMonitor,
    private val gson: Gson,
) {

    /**
     * Snapshot serverHealth once, run [online]; on IOException fall back to [offlineQueueAndOptimistic].
     *
     * IOException → fallback (mid-flight network drop, queue makes sense).
     * HttpException → failure (server responded, queue would just re-fail).
     * Other Exception → failure via PaperlessException.from.
     *
     * Fixes the TOCTOU race (#169): previously serverHealthMonitor was read inline at
     * the call site without IOException fallback; if state flipped between check and
     * API call, the user's edit was lost rather than queued.
     */
    suspend fun <T> executeOrQueue(
        online: suspend () -> T,
        offlineQueueAndOptimistic: suspend () -> T,
    ): Result<T> {
        val isOnline = serverHealthMonitor.isServerReachable.value
        return try {
            if (isOnline) {
                try {
                    Result.success(online())
                } catch (e: IOException) {
                    Result.success(offlineQueueAndOptimistic())
                }
            } else {
                Result.success(offlineQueueAndOptimistic())
            }
        } catch (e: retrofit2.HttpException) {
            Result.failure(PaperlessException.fromHttpCode(e.code(), e.message()))
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    /**
     * Queue a document-update for offline sync.
     *
     * Fixes the JSON-injection bug (#169): previous implementation used buildString
     * with raw "$it" interpolation, producing invalid JSON for titles containing
     * embedded `"`. gson.toJson serializes only non-null fields by default
     * (matching SyncManager.pushDocumentChange's parser, which reads
     * Map<String, Any?> and does null-safe casts).
     */
    suspend fun queueDocumentUpdate(documentId: Int, payload: DocumentUpdatePayload) {
        pendingChangeDao.insert(
            PendingChange(
                entityType = "document",
                entityId = documentId,
                changeType = "update",
                changeData = gson.toJson(payload),
            )
        )
    }

    /** Queue a soft-delete (document → trash). */
    suspend fun queueDocumentDelete(documentId: Int) {
        pendingChangeDao.insert(
            PendingChange(
                entityType = "document",
                entityId = documentId,
                changeType = "delete",
                changeData = "{}",
            )
        )
    }

    /** Queue a bulk trash action (RESTORE or PERMANENT_DELETE). One PendingChange per id. */
    suspend fun queueTrashAction(documentIds: List<Int>, action: TrashAction) {
        documentIds.forEach { docId ->
            pendingChangeDao.insert(
                PendingChange(
                    entityType = "trash",
                    entityId = docId,
                    changeType = action.changeType,
                    changeData = "{}",
                )
            )
        }
    }

    enum class TrashAction(val changeType: String) {
        RESTORE("restore"),
        PERMANENT_DELETE("delete"),
    }

    /**
     * Payload for queueDocumentUpdate. Property names match the keys that
     * SyncManager.pushDocumentChange reads from the JSON: "title", "tags",
     * "correspondent", "documentType", "archiveSerialNumber", "created".
     * Null fields are omitted by Gson (default behavior), preserving the
     * pre-Phase-3.3 changeData shape.
     */
    data class DocumentUpdatePayload(
        val title: String? = null,
        val tags: List<Int>? = null,
        val correspondent: Int? = null,
        val documentType: Int? = null,
        val archiveSerialNumber: Int? = null,
        val created: String? = null,
    )
}
```

---

## 5. Caller Migration

### 5.1 DocumentMetadataRepository

**Constructor:** drop `pendingChangeDao` and `serverHealthMonitor`; add `sync: DocumentSyncRepository`.

**`updateDocument` body** becomes:

```kotlin
suspend fun updateDocument(
    documentId: Int,
    title: String? = null,
    tags: List<Int>? = null,
    correspondent: Int? = null,
    documentType: Int? = null,
    archiveSerialNumber: Int? = null,
    created: String? = null,
): Result<Document> = sync.executeOrQueue(
    online = {
        val oldTagIds = if (tags != null) getOldTagIds(documentId) else null
        val request = UpdateDocumentRequest(
            title = title, tags = tags, correspondent = correspondent,
            documentType = documentType, archiveSerialNumber = archiveSerialNumber,
            created = created,
        )
        val updatedDocument = api.updateDocument(documentId, request)
        cachedDocumentDao.insert(updatedDocument.toCachedEntity())
        if (tags != null && oldTagIds != null) {
            updateTagDocumentCounts(oldTagIds, tags)
        }
        updatedDocument.toDomain()
    },
    offlineQueueAndOptimistic = {
        sync.queueDocumentUpdate(
            documentId = documentId,
            payload = DocumentSyncRepository.DocumentUpdatePayload(
                title = title, tags = tags, correspondent = correspondent,
                documentType = documentType, archiveSerialNumber = archiveSerialNumber,
                created = created,
            ),
        )
        val cached = cachedDocumentDao.getDocument(documentId)
        cached?.toCachedDomain()
            ?: throw PaperlessException.ClientError(404, context.getString(R.string.error_document_not_cached))
    },
)
```

Both `// PHASE-3.3:` and `// FIXME (#169):` markers are deleted. The class KDoc is updated to remove "PHASE-3.3 DEBT" wording.

### 5.2 TrashRepository

**Constructor:** drop `pendingChangeDao`; add `sync: DocumentSyncRepository`. `cachedTaskDao` and `networkMonitor` stay (cascade-task-ack still uses the former; the latter is now redundant for the offline-check but keep it for `getTrashDocuments` which has its own offline-failure path).

Wait — `networkMonitor` is no longer used after migration if all 4 trash mutations go through `sync.executeOrQueue` AND `getTrashDocuments` is the only remaining caller. Let me check: `getTrashDocuments` (Phase 3.1) does `if (networkMonitor.checkOnlineStatus()) { api.getTrash(...) } else { NetworkError }` — it's a read-only fetch, NOT a mutation. It doesn't need offline-queueing because there's nothing to queue (just a server-state read). So `networkMonitor` stays in TrashRepository for that one use.

**`deleteDocument` body** becomes:

```kotlin
suspend fun deleteDocument(documentId: Int): Result<Unit> = sync.executeOrQueue(
    online = {
        // existing online flow: get unack'd tasks, optimistic softDelete, ack tasks
        // locally, api.deleteDocument, server-side ack tasks, rollback on failure.
        // BYTE-IDENTICAL preservation of cascade ordering (softDelete BEFORE API).
        // ...full existing online block...
    },
    offlineQueueAndOptimistic = {
        sync.queueDocumentDelete(documentId)
        cachedTaskDao.acknowledgeTasksForDocument(documentId.toString())
        cachedDocumentDao.softDelete(documentId, deletedAt = System.currentTimeMillis())
    },
)
```

**`restoreDocuments(documentIds)` body** becomes:

```kotlin
suspend fun restoreDocuments(documentIds: List<Int>): Result<Unit> = sync.executeOrQueue(
    online = {
        val request = TrashBulkActionRequest(documents = documentIds, action = "restore")
        val response = api.trashBulkAction(request)
        if (response.isSuccessful) {
            cachedDocumentDao.restoreDocuments(documentIds)
        } else {
            // throw to land in PaperlessException.fromHttpCode mapping in executeOrQueue
            val errorBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
            throw retrofit2.HttpException(retrofit2.Response.error<Unit>(response.code(), (errorBody ?: "{}").toResponseBody("application/json".toMediaTypeOrNull())))
        }
    },
    offlineQueueAndOptimistic = {
        sync.queueTrashAction(documentIds, DocumentSyncRepository.TrashAction.RESTORE)
        cachedDocumentDao.restoreDocuments(documentIds)
    },
)
```

(Same pattern for `permanentlyDeleteDocuments` with `TrashAction.PERMANENT_DELETE` and `cachedDocumentDao.deleteByIds(documentIds)`.)

`restoreDocument(id)` and `permanentlyDeleteDocument(id)` stay as 1-line wrappers to their bulk variants (Phase 3.1 consolidation preserved).

All 3 inline `// PHASE-3.3:` markers and the class-level Phase-3.3 KDoc are removed.

### 5.3 AppModule

`provideDocumentRepository` signature changes only if `DocumentSyncRepository` needs to be passed as a transitive ctor arg — but since DocumentMetadata and Trash construct it via `@Inject constructor`, Hilt resolves it automatically. No `AppModule` changes needed.

---

## 6. PR Sequence

| Branch | Sub-issue | LOC moved/added |
|---|---|---|
| `refactor/51-extract-document-sync` | #169 | +150 (new) +0 net (DocumentMetadata/Trash net-neutral or slightly negative) |

Single PR. M-Effort.

---

## 7. Testing Strategy

### 7.1 Existing safety net

`DocumentRepositoryTest.kt` keeps 19 ctor args (no DocumentRepository ctor change). The constructed `metadataRepository` and `trashRepository` need `sync: DocumentSyncRepository` added to their constructors instead of `pendingChangeDao` (Metadata: also `serverHealthMonitor`).

`DocumentMetadataRepositoryTest.kt` and `TrashRepositoryTest.kt`: existing tests need their mock-setup updated — `pendingChangeDao` mock removed, replaced with `sync: DocumentSyncRepository` mock. Test bodies that previously asserted `coVerify { pendingChangeDao.insert(...) }` now assert `coVerify { sync.queueDocumentUpdate(...) }` / `sync.queueDocumentDelete(...)` / `sync.queueTrashAction(...)`. Tests that captured `slot<PendingChange>()` for offline JSON-shape verification migrate to `slot<DocumentSyncRepository.DocumentUpdatePayload>()` (or equivalent).

**Crucial:** preserve the `coVerifyOrder` asymmetric-delete test from Phase 3.1. The cascade ordering `softDelete BEFORE API` (online) and `ack BEFORE softDelete` (offline) must hold.

### 7.2 New test file: `DocumentSyncRepositoryTest.kt` — 10 cases

| # | Test name | Branch under test |
|---|---|---|
| 1 | `queueDocumentUpdate writes PendingChange with gson-serialized payload` | basic JSON shape |
| 2 | `queueDocumentUpdate handles title with embedded quotes safely` | **JSON-Injection Bug Fix** — title=`He said "hi"` → valid JSON |
| 3 | `queueDocumentUpdate omits null payload fields from JSON` | Gson default skip-nulls |
| 4 | `queueDocumentDelete writes correct PendingChange` | basic |
| 5 | `queueTrashAction RESTORE writes one PendingChange per id with changeType restore` | bulk restore |
| 6 | `queueTrashAction PERMANENT_DELETE writes one PendingChange per id with changeType delete` | bulk perm-delete |
| 7 | `executeOrQueue online happy path returns Result success of online value` | online path |
| 8 | `executeOrQueue offline runs offlineQueueAndOptimistic path` | offline path |
| 9 | `executeOrQueue online IOException falls back to offlineQueueAndOptimistic` | **TOCTOU Bug Fix** |
| 10 | `executeOrQueue online HttpException 4xx returns failure without fallback` | heuristic verification |

**Frameworks:** plain JUnit + mockk + `kotlinx-coroutines-test`. No Robolectric needed — `DocumentSyncRepository` has no Android dependencies (only `Gson`, DAO, StateFlow).

**Cumulative dedicated tests after Phase 3.3:** 8 + 12 + 11 + 15 + 7 + 5 + 10 = **68 tests** across 7 files.

### 7.3 Manual on-device smoke (pre-merge)

1. **Update document title with `"`** (online): edit → save → server gets escaped value → no error.
2. **Update document title with `"`** (offline): airplane mode → edit → save → reconnect → SyncManager replays → server gets correct value (validates the JSON-injection fix end-to-end).
3. **Mid-flight network drop** (TOCTOU): start an edit while online → toggle airplane mid-API-call (or simulate via slow network) → user-visible behavior: optimistic UI shows the change AND the change is queued (not lost as error).
4. **Trash flows**: soft-delete + restore + permanent delete in both online and offline modes.

### 7.4 CI validation

- `./scripts/validate-ci.sh` (RELEASE variants) green before push.
- CodeRabbit findings actioned or skipped with rationale.

---

## 8. Risks and Mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| `gson.toJson(DocumentUpdatePayload)` produces JSON with different key names than SyncManager parser expects | High | SyncManager reads `data["title"]`, `data["tags"]`, `data["correspondent"]`, `data["documentType"]`, `data["archiveSerialNumber"]`, `data["created"]` — verified by inspection at `SyncManager.kt:418-423`. Property names match exactly. Test #1 + #2 verify with concrete JSON shape inspection. |
| `executeOrQueue` IOException-fallback fires for non-network IOException (e.g., disk error during JSON serialization) | Low | Gson serialization happens inside `offlineQueueAndOptimistic`, AFTER the IOException check — disk errors during gson would bubble to the outer `catch (e: Exception)`. Online-path IOException is virtually always network (OkHttp wraps `SocketTimeoutException`, `UnknownHostException`, etc. as IOException). Acceptable trade-off. |
| Cascade ordering in `deleteDocument` breaks when wrapped in `executeOrQueue` lambda | High | Both online and offline lambdas preserve byte-identical body structure. Phase 3.1's `coVerifyOrder` tests stay green. Manual on-device smoke verifies Gmail-swipe optimistic UI. |
| Concurrent-mutation tests are hard to write (per master plan: "concurrent-mutation tests required") | Medium | Test #9 simulates concurrent mutation via `coEvery { online() } throws IOException(...)` — this models the effect of state flipping during the API call without needing TestDispatcher complexity. Sufficient for the stated bug. |
| Existing `DocumentMetadataRepositoryTest` / `TrashRepositoryTest` mock updates introduce test-mock churn (~30 verify-assertion changes) | Medium | Each mock change is mechanical: `pendingChangeDao.insert` → `sync.queueDocument*`. Implementer follows a strict find-and-replace procedure. CI catches any genuine regression. |
| `DocumentRepository` ctor (19 deps) may need adjustment because `pendingChangeDao` and `serverHealthMonitor` lose their last consumer | Low | Both deps stay in `DocumentRepository` for now (still injected as 19th and 7th-something positions). They become unused-but-injected — Phase 5 cleanup target. Same pattern as `cachedTaskDao` after Phase 3.1. No regression. |
| CodeRabbit ASSERTIVE finds pre-existing bugs in moved code | Low (welcome) | Phase-1 pattern: action small fixes (≤5 LOC) inline; defer architectural changes. |

---

## 9. Out-of-Scope

- **Phase 5 (#171)** — Façade cleanup, ViewModel migration, DTO→domain (PermissionRepository), removal of dead `DocumentRepository` ctor params (`cachedTaskDao`, `pendingChangeDao`, `serverHealthMonitor` after Phase 3.3).
- **SyncManager refactor** — its parser stays unchanged.
- **Retry policies** for failed PendingChange pushes — stays in SyncManager.
- **Adding `IOException` fallback to AuditRepository / PermissionRepository / DocumentListRepository** — those are read-only operations; no offline queue makes sense.

---

## 10. Rollback Strategy

Pure repository refactor. Single `git revert <sha>` restores DocumentMetadata + Trash to their pre-Phase-3.3 state and removes `DocumentSyncRepository.kt`. No schema change, no migration. **One concern:** PendingChange data already queued by users between merge and revert would have new JSON shape; rollback's old `buildString` parser handles them only if the title/created strings happen to be quote-free. Acceptable: rollback restores a strictly-worse-but-not-broken state.

---

## 11. Acceptance Criteria

- [ ] 1 new file `app/src/main/java/com/paperless/scanner/data/repository/DocumentSyncRepository.kt` with `@Inject constructor` + `@Singleton`
- [ ] 4 inline `// PHASE-3.3:` markers and 2 `// FIXME (#169):` markers removed from DocumentMetadata + Trash (final count: 0)
- [ ] DocumentMetadataRepository.updateDocument migrated; `pendingChangeDao` and `serverHealthMonitor` deps removed; `sync: DocumentSyncRepository` added
- [ ] TrashRepository's 3 mutations migrated; `pendingChangeDao` dep removed; `sync: DocumentSyncRepository` added
- [ ] DocumentRepository ctor unchanged (still 19 deps; `pendingChangeDao` + `serverHealthMonitor` become unused-but-injected — Phase-5 cleanup)
- [ ] 10 new tests in `DocumentSyncRepositoryTest.kt`, all green
- [ ] **Test #2 (JSON-injection title with `"`) and Test #9 (IOException fallback) BOTH pass — these verify the bug fixes**
- [ ] Existing `DocumentMetadataRepositoryTest.kt` and `TrashRepositoryTest.kt` green with updated mocks (pendingChangeDao→sync)
- [ ] Phase-3.1 `coVerifyOrder` cascade-ordering tests in TrashRepositoryTest preserved + green
- [ ] DocumentRepositoryTest green (no API change)
- [ ] `./scripts/validate-ci.sh` green before push
- [ ] CodeRabbit findings actioned or skipped with rationale
- [ ] Sub-issue #169 merged via "Closes #169"
- [ ] Manual on-device smoke per §7.3 completed (especially scenario 3: mid-flight drop)
- [ ] Memory file `issue_51_phase4_complete.md` → `issue_51_phase3_3_complete.md` (or in-place); MEMORY.md pointer updated; Phase-3.3 debt count reduced from 5 to 0
