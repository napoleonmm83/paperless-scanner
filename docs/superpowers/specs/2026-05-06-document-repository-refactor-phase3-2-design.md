# Design — DocumentRepository Refactor (Issue #51, Phase 3.2)

**Date:** 2026-05-06
**Tracking issue:** [#51 — DocumentRepository God-class refactor](https://github.com/napoleonmm83/paperless-scanner/issues/51)
**Master plan reference:** [`docs/code-reviews/REFACTOR_DOCUMENT_REPOSITORY.md`](../../code-reviews/REFACTOR_DOCUMENT_REPOSITORY.md)
**Predecessor specs:** Phase 1, Phase 2, Phase 3.1 (all merged).
**Scope of this spec:** Phase 3.2 only — extract `AuditRepository` from `DocumentRepository`. Single PR, façade-only pattern, sub-issue #168.

---

## 1. Goal

Extract the three audit-history methods (`getDocumentHistory`, `addNote`, `deleteNote`) from `DocumentRepository.kt` (post-Phase-3.1: 503 LOC) into a dedicated `AuditRepository` under `data/repository/`. The simplest extraction in the refactor — three thin online-only API wrappers, ~50 LOC of source code, no offline-queue, no cascade, no consolidation, no dead code.

`DocumentRepository` remains a 1:1 façade. No caller migration. The single caller (`DocumentDetailViewModel`) keeps injecting `DocumentRepository` unchanged.

**Non-goals:**
- No public API changes; no caller migration (Phase 5).
- No `Context` removal from data layer (Phase 5).
- No conversion of any of the 3 methods to add offline support — they are online-only by design.

---

## 2. Scope

| Sub-issue | Phase | Effort |
|---|---|---|
| #168 | 3.2 | S |

In: 1 new file, 1 new test file, modify DocumentRepository, AppModule, DocumentRepositoryTest.
Out: Phases 3.3 (#169), 4 (#170), 5 (#171).

---

## 3. Architecture

### 3.1 Package layout (post-Phase 3.2)

```
app/src/main/java/com/paperless/scanner/data/repository/
├── DocumentRepository.kt              (Façade, ≤ 460 LOC after Phase 3.2)
├── DocumentListRepository.kt          (Phase 2.1)
├── DocumentCountRepository.kt         (Phase 2.2)
├── DocumentMetadataRepository.kt      (Phase 2.3)
├── TrashRepository.kt                 (Phase 3.1)
├── AuditRepository.kt                 NEW (~80 LOC)
└── ...other repos unchanged
```

### 3.2 Method-to-repository mapping

| Method (current line in DocumentRepository.kt) | Action |
|---|---|
| `getDocumentHistory` (L384) | → AuditRepository |
| `addNote` (L399) | → AuditRepository |
| `deleteNote` (L415) | → AuditRepository |

### 3.3 Façade strategy

- `DocumentRepository` retains its current signatures byte-identically.
- The bodies of the 3 moved methods become one-liners: `return audit.getDocumentHistory(id)`, etc.
- Constructor gains 1 field: `audit: AuditRepository` at position 18.
- Single caller (`DocumentDetailViewModel.kt:350, 467, 498`) continues to inject `DocumentRepository` unchanged.

---

## 4. New Repository — Class Shape

```kotlin
// app/src/main/java/com/paperless/scanner/data/repository/AuditRepository.kt
@Singleton
class AuditRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: PaperlessApi,
    private val networkMonitor: NetworkMonitor,
) {
    suspend fun getDocumentHistory(documentId: Int): Result<List<AuditLogEntry>>
    suspend fun addNote(documentId: Int, noteText: String): Result<List<Note>>
    suspend fun deleteNote(documentId: Int, noteId: Int): Result<List<Note>>
}
```

### 4.1 Method bodies (preserved byte-identically from DocumentRepository)

All three follow the same pattern:
```kotlin
return try {
    if (networkMonitor.checkOnlineStatus()) {
        // call api.<method>(...)
        // map to domain
        Result.success(...)
    } else {
        Result.failure(PaperlessException.NetworkError(IOException(context.getString(R.string.error_offline))))
    }
} catch (e: retrofit2.HttpException) {
    Result.failure(PaperlessException.fromHttpCode(e.code(), e.message()))
} catch (e: Exception) {
    Result.failure(PaperlessException.from(e))
}
```

`addNote` builds a `CreateNoteRequest(note = noteText)` before the API call. `deleteNote` passes both `documentId` and `noteId`. `getDocumentHistory` maps via `.toAuditLogDomain()`; the note-returning methods map each item via `.toDomain()`.

### 4.2 DocumentRepository façade (post-Phase 3.2)

```kotlin
class DocumentRepository @Inject constructor(
    // ...all 17 existing deps unchanged...
    private val audit: AuditRepository,                   // +
)
```

Constructor grows by exactly 1 parameter (position 18).

### 4.3 Imports cleanup

After delegation, the following DocumentRepository imports likely become unused. **Verify each via grep before removing:**
- `import com.paperless.scanner.data.api.models.CreateNoteRequest`
- `import com.paperless.scanner.domain.mapper.toAuditLogDomain`
- `import com.paperless.scanner.domain.model.AuditLogEntry`

`Note` (domain model) and `IOException` may still be used elsewhere — grep before removing.

---

## 5. PR Sequence

| Branch | Sub-issue | LOC moved |
|---|---|---|
| `refactor/51-extract-audit-repository` | #168 | ~50 (move) |

Single PR. Effort S.

---

## 6. Hilt Wiring

`AppModule.kt:provideDocumentRepository` gains `audit: AuditRepository,` parameter (passed to constructor). `AuditRepository` itself has `@Inject constructor` + `@Singleton` — auto-discovery, no `@Provides` function needed.

```kotlin
import com.paperless.scanner.data.repository.AuditRepository

// inside the function signature:
audit: AuditRepository,                                // NEW
// inside the constructor call:
audit,                                                 // NEW
```

---

## 7. Testing Strategy

### 7.1 Existing safety net

`DocumentRepositoryTest.kt` extends from 17 to 18 ctor args. Stays green via façade delegation. No tests added/removed.

### 7.2 New test file: `AuditRepositoryTest.kt` — 6 cases

| # | Test name | Branch under test |
|---|---|---|
| 1 | `getDocumentHistory online returns mapped audit log` | online happy path |
| 2 | `getDocumentHistory offline returns NetworkError` | offline failure |
| 3 | `addNote online sends CreateNoteRequest and returns mapped notes` | online happy path + slot capture for request shape |
| 4 | `addNote offline returns NetworkError` | offline failure |
| 5 | `deleteNote online returns mapped notes` | online happy path |
| 6 | `deleteNote offline returns NetworkError` | offline failure |

**Frameworks:** Robolectric + mockk relaxed; `coEvery`; `slot<CreateNoteRequest>()` for #3 to verify the request body shape.

The error-mapping path (`HttpException` → `PaperlessException.fromHttpCode`, `Exception` → `PaperlessException.from`) is identical across all 3 methods. One round of mapping coverage is implicit through the offline-NetworkError tests; if CodeRabbit wants a dedicated HttpException case, add it as a 7th test in PR-response.

**Cumulative dedicated tests after Phase 3.2:** 8 + 12 + 11 + 15 + 6 = **52 tests** across 5 files.

### 7.3 Manual on-device smoke (pre-merge)

1. Open a document detail → check **History** tab loads (calls `getDocumentHistory`).
2. Add a note → see it in the notes list (calls `addNote`).
3. Delete a note → see it disappear (calls `deleteNote`).
4. Offline (Airplane mode) → all three operations show appropriate offline error message.

### 7.4 CI validation

- `./scripts/validate-ci.sh` (RELEASE variants) green before push.
- CodeRabbit findings actioned or skipped with rationale.

---

## 8. Risks and Mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| Mapper functions (`toAuditLogDomain`, `toDomain`) have subtle differences during the move | Low | Move bodies byte-identically; tests #1, #3, #5 verify mapped output. |
| Imports cleanup removes a still-used import | Low | Verify each removal with grep before deletion. CI compile catches false removals. |
| CodeRabbit flags pre-existing offline behavior (NetworkError swallows the actual cause) | Low | Pre-existing pattern across all online-only methods in this codebase; flag only, defer to Phase 5 typed-error work or new issue. |

No Phase-3.3 debt is introduced — all 3 methods are online-only with no `PendingChange` / offline-queue logic.

---

## 9. Out-of-Scope

- **Phase 3.3 `DocumentSyncRepository` (#169)** — HIGHEST RISK. After Phase 3.2 lands, sub-issue #169 still owns 5 inline `// PHASE-3.3:` markers (2 in DocumentMetadata, 3 in TrashRepository) plus the JSON-injection FIXME.
- **Phase 4 `PermissionRepository` (#170)** — `getUsers`, `getGroups` stay in DocumentRepository.
- **Phase 5 (#171)** — no caller migration; `DocumentDetailViewModel` continues to inject `DocumentRepository`.
- **Adding offline support** to history/notes — not part of the refactor; would be a separate feature ticket.

---

## 10. Rollback Strategy

Pure internal extraction — no public API change, no schema change. Single `git revert <sha>` restores the 3 methods to `DocumentRepository.kt` and removes `AuditRepository.kt`.

---

## 11. Acceptance Criteria

- [ ] 1 new file `app/src/main/java/com/paperless/scanner/data/repository/AuditRepository.kt` with `@Inject constructor` + `@Singleton`
- [ ] DocumentRepository.kt reduced by ≥ 50 LOC (503 → ≤ 460)
- [ ] DocumentRepository constructor extended by exactly 1 field (`audit`) at position 18
- [ ] All 3 affected façade methods are one-line delegations to `audit.*`
- [ ] DocumentRepositoryTest green (existing API unchanged; 18th ctor arg)
- [ ] 6 new tests in `AuditRepositoryTest.kt`, all green
- [ ] `./scripts/validate-ci.sh` green before push (RELEASE variants)
- [ ] CodeRabbit ASSERTIVE findings actioned or skipped with rationale (link to §9)
- [ ] Sub-issue #168 merged via "Closes #168"
- [ ] Manual on-device smoke per §7.3 completed
- [ ] Memory file `issue_51_phase3_1_complete.md` → `issue_51_phase3_2_complete.md` (or in-place update); MEMORY.md pointer updated
