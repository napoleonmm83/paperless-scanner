# Design — DocumentRepository Refactor (Issue #51, Phase 4)

**Date:** 2026-05-06
**Tracking issue:** [#51 — DocumentRepository God-class refactor](https://github.com/napoleonmm83/paperless-scanner/issues/51)
**Predecessor specs:** Phases 1, 2, 3.1, 3.2 (all merged).
**Scope of this spec:** Phase 4 only — extract `PermissionRepository` from `DocumentRepository`. Single PR, sub-issue #170.

---

## 1. Goal

Extract two thin online-only API wrappers (`getUsers`, `getGroups`) from `DocumentRepository.kt` (post-Phase-3.2: 465 LOC) into a dedicated `PermissionRepository` under `data/repository/`. Mirrors the Phase 3.2 (AuditRepository) shape exactly — same online-only pattern, no offline-queue, no cascade, no consolidation.

`DocumentRepository` remains a 1:1 façade. Single caller (`DocumentDetailViewModel.kt:539, 543`) untouched.

**Non-goals:**
- No public API changes; no caller migration (Phase 5).
- No DTO-to-domain migration. Both methods currently return `Result<List<com.paperless.scanner.data.api.models.User|Group>>` — data-layer DTOs escape the repository. Per CLAUDE.md "Resolve error messages in UI layer, not data layer" + domain-typing principle, DTOs should NOT escape; this is pre-existing tech debt and migration is caller-breaking → Phase 5 (#171) territory. Phase 4 preserves byte-identical signatures.
- No `Context` removal from data layer (Phase 5).

---

## 2. Scope

| Sub-issue | Phase | Effort |
|---|---|---|
| #170 | 4 | S |

In: 1 new file, 1 new test file, modify DocumentRepository, AppModule, DocumentRepositoryTest.
Out: Phases 3.3 (#169 — HIGHEST RISK), 5 (#171).

---

## 3. Architecture

### 3.1 Package layout (post-Phase 4)

```
app/src/main/java/com/paperless/scanner/data/repository/
├── DocumentRepository.kt              (Façade, ≤ 410 LOC after Phase 4)
├── DocumentListRepository.kt          (Phase 2.1)
├── DocumentCountRepository.kt         (Phase 2.2)
├── DocumentMetadataRepository.kt      (Phase 2.3)
├── TrashRepository.kt                 (Phase 3.1)
├── AuditRepository.kt                 (Phase 3.2)
├── PermissionRepository.kt            NEW (~60 LOC)
└── ...other repos unchanged
```

### 3.2 Method-to-repository mapping

| Method (current line in DocumentRepository.kt) | Action |
|---|---|
| `getUsers` (L394) | → PermissionRepository |
| `getGroups` (L409) | → PermissionRepository |

### 3.3 Façade strategy

- `DocumentRepository` retains current signatures byte-identically (including the DTO-typed return; Phase 5 will migrate to domain types).
- The 2 method bodies become 1-liners: `return permission.getUsers()`, `return permission.getGroups()`.
- Constructor gains 1 field: `permission: PermissionRepository` at position 19.
- `DocumentDetailViewModel` (the only caller) unchanged.

---

## 4. New Repository — Class Shape

```kotlin
// app/src/main/java/com/paperless/scanner/data/repository/PermissionRepository.kt
@Singleton
class PermissionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: PaperlessApi,
    private val networkMonitor: NetworkMonitor,
) {
    suspend fun getUsers(): Result<List<User>>      // data.api.models.User (DTO escape — pre-existing, Phase-5 migration)
    suspend fun getGroups(): Result<List<Group>>    // data.api.models.Group (DTO escape — pre-existing, Phase-5 migration)
}
```

### 4.1 Method bodies (preserved byte-identically)

Both follow this pattern:
```kotlin
return try {
    if (networkMonitor.checkOnlineStatus()) {
        val response = api.<getUsers|getGroups>()
        Result.success(response.results)
    } else {
        Result.failure(PaperlessException.NetworkError(IOException(context.getString(R.string.error_offline))))
    }
} catch (e: retrofit2.HttpException) {
    Result.failure(PaperlessException.fromHttpCode(e.code(), e.message()))
} catch (e: Exception) {
    Result.failure(PaperlessException.from(e))
}
```

Note: unlike `getDocumentHistory`/`addNote`/`deleteNote` (Phase 3.2, which mapped DTOs to domain via `.toAuditLogDomain()` / `.toDomain()`), these methods return `response.results` directly — DTOs escape the repository. Pre-existing pattern; preserve byte-identical.

### 4.2 DocumentRepository façade (post-Phase 4)

```kotlin
class DocumentRepository @Inject constructor(
    // ...all 18 existing deps unchanged...
    private val permission: PermissionRepository,        // +
)
```

Constructor grows by exactly 1 parameter (position 19).

### 4.3 Imports cleanup

After delegation, no DocumentRepository imports become uniquely unused (the DTO types `User`/`Group` are FQN'd inline at the return type sites of the façade methods, so the old code likely already used FQN — verify with grep before any changes).

If any of these imports turn out to be unused after the move, remove them:
- `com.paperless.scanner.data.api.models.User`
- `com.paperless.scanner.data.api.models.Group`

(Likely both were FQN'd at the existing call site; no top-level import to remove.)

---

## 5. PR Sequence

| Branch | Sub-issue | LOC moved |
|---|---|---|
| `refactor/51-extract-permission-repository` | #170 | ~30 |

Single PR. Effort S.

---

## 6. Hilt Wiring

`AppModule.kt:provideDocumentRepository` gains `permission: PermissionRepository,` parameter (passed to constructor). `PermissionRepository` itself has `@Inject constructor` + `@Singleton` — auto-discovery. Add the import:
```kotlin
import com.paperless.scanner.data.repository.PermissionRepository
```

---

## 7. Testing Strategy

### 7.1 Existing safety net

`DocumentRepositoryTest.kt` extends from 18 to 19 ctor args. Stays green via façade delegation.

### 7.2 New test file: `PermissionRepositoryTest.kt` — 5 cases

| # | Test name | Branch under test |
|---|---|---|
| 1 | `getUsers online returns DTO list from response results` | online happy |
| 2 | `getUsers offline returns NetworkError` | offline failure |
| 3 | `getGroups online returns DTO list from response results` | online happy |
| 4 | `getGroups offline returns NetworkError` | offline failure |
| 5 | `getUsers online HttpException maps to PaperlessException via fromHttpCode` | shared catch branch |

**Frameworks:** Robolectric + mockk relaxed; same fixture pattern as `AuditRepositoryTest.kt`. The 5th test covers the HttpException catch branch shared by both methods (DRY pattern from Phase 3.2 — one test sufficient).

**Cumulative dedicated tests after Phase 4:** 8 + 12 + 11 + 15 + 7 + 5 = **58 tests** across 6 files.

### 7.3 Manual on-device smoke (pre-merge)

1. Open a document detail → permissions section → **Add user** dropdown loads (calls `getUsers`).
2. Same view → **Add group** dropdown loads (calls `getGroups`).
3. Airplane mode + retry → expect offline error, no crash.

### 7.4 CI validation

- `./scripts/validate-ci.sh` (RELEASE variants) green before push.
- CodeRabbit findings actioned or skipped with rationale.

---

## 8. Risks and Mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| DTO-escape (`User`/`Group` FQN return type) confuses tests or future readers | Low | Preserved byte-identically; Phase 5 will migrate to domain types. Class KDoc documents the pre-existing pattern. |
| `api.getUsers()`/`api.getGroups()` signature differs from expectation (e.g., `Response<UsersResponse>` wrapper) | Low | Inspect `PaperlessApi.kt` before authoring tests; adapt mocks to actual signature. |
| CodeRabbit flags missing HttpException test (as in Phase 3.2) | Already addressed | 5th test covers shared catch branch. |

No Phase-3.3 debt is introduced — both methods are online-only.

---

## 9. Out-of-Scope

- **Phase 3.3 `DocumentSyncRepository` (#169)** — HIGHEST RISK. Still owns 5 inline `// PHASE-3.3:` markers + JSON-injection FIXME.
- **Phase 5 (#171)** — no caller migration; `DocumentDetailViewModel` continues to inject `DocumentRepository`. Phase 5 will:
  - Migrate `getUsers`/`getGroups` return type from DTO to domain.
  - Remove dead ctor params from DocumentRepository (`cachedTaskDao`, `pendingChangeDao`).
  - Remove `Context` injection (data-layer string resolution).
- **Adding offline support** to permission lookups — not part of the refactor.

---

## 10. Rollback Strategy

Pure internal extraction. Single `git revert <sha>` restores the 2 methods to `DocumentRepository.kt` and removes `PermissionRepository.kt`.

---

## 11. Acceptance Criteria

- [ ] 1 new file `app/src/main/java/com/paperless/scanner/data/repository/PermissionRepository.kt` with `@Inject constructor` + `@Singleton`
- [ ] DocumentRepository.kt reduced by ≥ 50 LOC (465 → ≤ 410)
- [ ] DocumentRepository constructor extended by exactly 1 field (`permission`) at position 19
- [ ] Both affected façade methods are one-line delegations to `permission.*`
- [ ] DocumentRepositoryTest green (existing API unchanged; 19th ctor arg)
- [ ] 5 new tests in `PermissionRepositoryTest.kt`, all green
- [ ] `./scripts/validate-ci.sh` green before push (RELEASE variants)
- [ ] CodeRabbit findings actioned or skipped with rationale
- [ ] Sub-issue #170 merged via "Closes #170"
- [ ] Manual on-device smoke per §7.3 completed
- [ ] Memory file `issue_51_phase3_2_complete.md` → `issue_51_phase4_complete.md` (or in-place update); MEMORY.md pointer updated
