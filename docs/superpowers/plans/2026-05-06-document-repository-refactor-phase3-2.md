# DocumentRepository Refactor — Phase 3.2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract `AuditRepository` (3 thin online-only API wrappers — `getDocumentHistory`, `addNote`, `deleteNote`) from `DocumentRepository` into `data/repository/`. Single PR.

**Architecture:** One new repository in `data/repository/`. `DocumentRepository` becomes a 1:1 façade for 3 audit methods. No offline-queue, no cascade, no consolidation, no dead code. Smallest extraction in the refactor.

**Tech Stack:** Kotlin 2.0, Hilt DI, Retrofit (PaperlessApi), Robolectric + mockk, Gradle (`testReleaseUnitTest`, `lintRelease`, `assembleRelease`), `validate-ci.sh`.

**Spec reference:** [`docs/superpowers/specs/2026-05-06-document-repository-refactor-phase3-2-design.md`](../specs/2026-05-06-document-repository-refactor-phase3-2-design.md)

**Phase 3.1 references (templates):**
- `app/src/main/java/com/paperless/scanner/data/repository/TrashRepository.kt` — Singleton repo class shape
- `app/src/test/java/com/paperless/scanner/data/repository/TrashRepositoryTest.kt` — Robolectric + mockk patterns

---

## File Structure

| Path | Action |
|---|---|
| `app/src/main/java/com/paperless/scanner/data/repository/AuditRepository.kt` | Create (~80 LOC) |
| `app/src/test/java/com/paperless/scanner/data/repository/AuditRepositoryTest.kt` | Create (6 tests) |
| `app/src/main/java/com/paperless/scanner/data/repository/DocumentRepository.kt` | Modify (3 method bodies → 1-line delegations; constructor +1 dep; clean up imports) |
| `app/src/main/java/com/paperless/scanner/di/AppModule.kt` | Modify (`provideDocumentRepository` +1 param + import) |
| `app/src/test/java/com/paperless/scanner/data/repository/DocumentRepositoryTest.kt` | Modify (18th constructor arg) |

---

## Pre-PR Setup

- [ ] **Step P.1: Confirm baseline state**

Run:
```bash
cd "E:/Git/paperless client" && git status && git log --oneline -3
```
Expected: working tree clean, on `main`, latest commit is `26559ad docs(superpowers): add Phase 3.2 design spec for #51 (AuditRepository)` with `30d3d57 refactor: extract TrashRepository (Phase 3.1 of #51) (#180)` further behind.

---

## Task 1: Create branch

- [ ] **Step 1.1: Create and checkout the branch**

Run:
```bash
cd "E:/Git/paperless client" && git checkout main && git pull --rebase --autostash && git checkout -b refactor/51-extract-audit-repository
```

Expected: `Switched to a new branch 'refactor/51-extract-audit-repository'`.

---

## Task 2: Create AuditRepository

**Files:**
- Create: `app/src/main/java/com/paperless/scanner/data/repository/AuditRepository.kt`

- [ ] **Step 2.1: Write the new repository file**

Create `app/src/main/java/com/paperless/scanner/data/repository/AuditRepository.kt` with this exact content:

```kotlin
package com.paperless.scanner.data.repository

import android.content.Context
import com.paperless.scanner.R
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.api.models.CreateNoteRequest
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.domain.mapper.toAuditLogDomain
import com.paperless.scanner.domain.mapper.toDomain
import com.paperless.scanner.domain.model.AuditLogEntry
import com.paperless.scanner.domain.model.Note
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 3.2 of #51 — extracted from DocumentRepository.
 *
 * Owns audit-history operations: getDocumentHistory, addNote, deleteNote.
 * All methods are online-only thin wrappers around PaperlessApi; offline
 * branches return PaperlessException.NetworkError. No cache, no offline-queue.
 */
@Singleton
class AuditRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: PaperlessApi,
    private val networkMonitor: NetworkMonitor,
) {

    suspend fun getDocumentHistory(documentId: Int): Result<List<AuditLogEntry>> {
        return try {
            if (networkMonitor.checkOnlineStatus()) {
                val history = api.getDocumentHistory(documentId)
                Result.success(history.toAuditLogDomain())
            } else {
                Result.failure(
                    PaperlessException.NetworkError(IOException(context.getString(R.string.error_offline)))
                )
            }
        } catch (e: retrofit2.HttpException) {
            Result.failure(PaperlessException.fromHttpCode(e.code(), e.message()))
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    suspend fun addNote(documentId: Int, noteText: String): Result<List<Note>> {
        return try {
            if (networkMonitor.checkOnlineStatus()) {
                val request = CreateNoteRequest(note = noteText)
                val notes = api.addNote(documentId, request)
                Result.success(notes.map { it.toDomain() })
            } else {
                Result.failure(
                    PaperlessException.NetworkError(IOException(context.getString(R.string.error_offline)))
                )
            }
        } catch (e: retrofit2.HttpException) {
            Result.failure(PaperlessException.fromHttpCode(e.code(), e.message()))
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    suspend fun deleteNote(documentId: Int, noteId: Int): Result<List<Note>> {
        return try {
            if (networkMonitor.checkOnlineStatus()) {
                val notes = api.deleteNote(documentId, noteId)
                Result.success(notes.map { it.toDomain() })
            } else {
                Result.failure(
                    PaperlessException.NetworkError(IOException(context.getString(R.string.error_offline)))
                )
            }
        } catch (e: retrofit2.HttpException) {
            Result.failure(PaperlessException.fromHttpCode(e.code(), e.message()))
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }
}
```

- [ ] **Step 2.2: Verify compile**

Run:
```bash
export JAVA_HOME="$(ls -d '/c/Program Files/Eclipse Adoptium/jdk-21.'* 2>/dev/null | head -1)"
./gradlew :app:compileReleaseKotlin --no-daemon
```
Expected: BUILD SUCCESSFUL.

---

## Task 3: Update DocumentRepository

**Files:**
- Modify: `app/src/main/java/com/paperless/scanner/data/repository/DocumentRepository.kt`

- [ ] **Step 3.1: Add `audit` to constructor**

Append `private val audit: AuditRepository,` AFTER the last existing param (`trash` from Phase 3.1). Constructor tail becomes:
```kotlin
    private val list: DocumentListRepository,
    private val count: DocumentCountRepository,
    private val metadata: DocumentMetadataRepository,
    private val trash: TrashRepository,
    private val audit: AuditRepository,
) {
```
(Note: actual order may be `count, metadata, list, trash` per Phase 3.1 implementer's observation. Append `audit` LAST regardless of the existing order.)

- [ ] **Step 3.2: Replace `getDocumentHistory` body**

Locate `getDocumentHistory` (around L384). Replace the entire function with:
```kotlin
    suspend fun getDocumentHistory(documentId: Int): Result<List<AuditLogEntry>> =
        audit.getDocumentHistory(documentId)
```

- [ ] **Step 3.3: Replace `addNote` body**

Locate `addNote` (around L399). Replace with:
```kotlin
    suspend fun addNote(documentId: Int, noteText: String): Result<List<com.paperless.scanner.domain.model.Note>> =
        audit.addNote(documentId, noteText)
```

- [ ] **Step 3.4: Replace `deleteNote` body**

Locate `deleteNote` (around L415). Replace with:
```kotlin
    suspend fun deleteNote(documentId: Int, noteId: Int): Result<List<com.paperless.scanner.domain.model.Note>> =
        audit.deleteNote(documentId, noteId)
```

- [ ] **Step 3.5: Clean up unused imports**

After delegation, these imports likely become unused. **Verify each via** `grep -n '<symbol>' app/src/main/java/com/paperless/scanner/data/repository/DocumentRepository.kt` BEFORE removing:
- `import com.paperless.scanner.data.api.models.CreateNoteRequest` — only used in `addNote` body
- `import com.paperless.scanner.domain.mapper.toAuditLogDomain` — only used in `getDocumentHistory` body
- `import com.paperless.scanner.domain.model.AuditLogEntry` — used in the delegation return type, so KEEP

`com.paperless.scanner.domain.mapper.toDomain` is used widely (`addNote`/`deleteNote` mapping is gone, but other methods still use it via `.toDomain()`). Grep before removing.

`IOException` may be used in remaining methods (e.g., `getTrashDocuments` was already delegated, so check `getDocuments`/etc. — likely still used). Grep before removing.

Only delete imports where grep returns 0 hits.

- [ ] **Step 3.6: Verify compile**

Run:
```bash
export JAVA_HOME="$(ls -d '/c/Program Files/Eclipse Adoptium/jdk-21.'* 2>/dev/null | head -1)"
./gradlew :app:compileReleaseKotlin --no-daemon
```
Expected: BUILD FAILED with `parameter not provided` in `AppModule.provideDocumentRepository` (fixed in Task 4). If `unresolved reference: audit`, double-check the constructor change in 3.1.

---

## Task 4: Update AppModule

**Files:**
- Modify: `app/src/main/java/com/paperless/scanner/di/AppModule.kt`

- [ ] **Step 4.1: Add `audit` parameter**

Locate `provideDocumentRepository`. Add `audit: AuditRepository,` as the LAST parameter and pass `audit` to the `DocumentRepository(...)` call. Add the import:
```kotlin
import com.paperless.scanner.data.repository.AuditRepository
```

- [ ] **Step 4.2: Verify compile**

Run:
```bash
export JAVA_HOME="$(ls -d '/c/Program Files/Eclipse Adoptium/jdk-21.'* 2>/dev/null | head -1)"
./gradlew :app:compileReleaseKotlin --no-daemon
```
Expected: BUILD SUCCESSFUL.

---

## Task 5: Update DocumentRepositoryTest minimally

**Files:**
- Modify: `app/src/test/java/com/paperless/scanner/data/repository/DocumentRepositoryTest.kt`

- [ ] **Step 5.1: Add `auditRepository` to setup() and ctor call**

In `setup()`, after the construction of `trashRepository` (the 17th-arg field added in Phase 3.1), add:
```kotlin
val auditRepository = AuditRepository(
    context = context,
    api = api,
    networkMonitor = networkMonitor,
)
```

Append `auditRepository,` as the 18th argument to the `DocumentRepository(...)` constructor call. Add the import:
```kotlin
import com.paperless.scanner.data.repository.AuditRepository
```

- [ ] **Step 5.2: Run DocumentRepositoryTest**

Run:
```bash
export JAVA_HOME="$(ls -d '/c/Program Files/Eclipse Adoptium/jdk-21.'* 2>/dev/null | head -1)"
./gradlew :app:testReleaseUnitTest --tests "*DocumentRepositoryTest*" --no-daemon
```
Expected: BUILD SUCCESSFUL.

---

## Task 6: Write AuditRepository tests

**Files:**
- Create: `app/src/test/java/com/paperless/scanner/data/repository/AuditRepositoryTest.kt`

- [ ] **Step 6.1: Write the test file**

Create `app/src/test/java/com/paperless/scanner/data/repository/AuditRepositoryTest.kt` with this exact content (6 tests):

```kotlin
package com.paperless.scanner.data.repository

import androidx.test.core.app.ApplicationProvider
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.api.models.CreateNoteRequest
import com.paperless.scanner.data.network.NetworkMonitor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AuditRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var api: PaperlessApi
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var repo: AuditRepository

    @Before
    fun setup() {
        api = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)
        repo = AuditRepository(context, api, networkMonitor)
    }

    @Test
    fun `getDocumentHistory online returns mapped audit log`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        // api.getDocumentHistory returns a list of AuditLogEntryDto.
        // Use mockk's relaxed default — empty list maps to empty list via toAuditLogDomain().
        coEvery { api.getDocumentHistory(42) } returns emptyList()

        val result = repo.getDocumentHistory(42)

        assertTrue(result.isSuccess)
        assertEquals(emptyList<Any>(), result.getOrNull())
        coVerify { api.getDocumentHistory(42) }
    }

    @Test
    fun `getDocumentHistory offline returns NetworkError`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns false

        val result = repo.getDocumentHistory(42)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is PaperlessException.NetworkError)
        coVerify(exactly = 0) { api.getDocumentHistory(any()) }
    }

    @Test
    fun `addNote online sends CreateNoteRequest and returns mapped notes`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        val requestSlot = slot<CreateNoteRequest>()
        coEvery { api.addNote(42, capture(requestSlot)) } returns emptyList()

        val result = repo.addNote(42, "Hello world")

        assertTrue(result.isSuccess)
        assertEquals("Hello world", requestSlot.captured.note)
    }

    @Test
    fun `addNote offline returns NetworkError`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns false

        val result = repo.addNote(42, "ignored")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is PaperlessException.NetworkError)
        coVerify(exactly = 0) { api.addNote(any(), any()) }
    }

    @Test
    fun `deleteNote online returns mapped notes`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        coEvery { api.deleteNote(42, 7) } returns emptyList()

        val result = repo.deleteNote(42, 7)

        assertTrue(result.isSuccess)
        assertEquals(emptyList<Any>(), result.getOrNull())
        coVerify { api.deleteNote(42, 7) }
    }

    @Test
    fun `deleteNote offline returns NetworkError`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns false

        val result = repo.deleteNote(42, 7)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is PaperlessException.NetworkError)
        coVerify(exactly = 0) { api.deleteNote(any(), any()) }
    }
}
```

If a test fails because `api.getDocumentHistory`/`api.addNote`/`api.deleteNote` return-type signatures differ from `List<Dto>` (e.g., wrapped in `Response<List<Dto>>`), inspect `PaperlessApi.kt` and adjust the `coEvery { ... } returns ...` stubs accordingly. The production code is correct — fix the test.

If `toAuditLogDomain()` cannot map an empty list (some mappers throw on empty input — unlikely but possible), use a 1-element fixture instead:
```kotlin
coEvery { api.getDocumentHistory(42) } returns listOf(/* fixture from sister test */)
```

- [ ] **Step 6.2: Run new tests**

Run:
```bash
export JAVA_HOME="$(ls -d '/c/Program Files/Eclipse Adoptium/jdk-21.'* 2>/dev/null | head -1)"
./gradlew :app:testReleaseUnitTest --tests "*AuditRepositoryTest*" --no-daemon
```
Expected: 6 tests pass.

---

## Task 7: validate-ci.sh

- [ ] **Step 7.1: Run validate-ci.sh**

Run:
```bash
cd "E:/Git/paperless client" && ./scripts/validate-ci.sh
```
Expected: green for all phases (translation/duplicate/empty checks, testReleaseUnitTest, assembleRelease, lintRelease).

---

## Task 8: Commit

- [ ] **Step 8.1: Stage and commit**

Run:
```bash
cd "E:/Git/paperless client" && \
  git add app/src/main/java/com/paperless/scanner/data/repository/AuditRepository.kt \
          app/src/test/java/com/paperless/scanner/data/repository/AuditRepositoryTest.kt \
          app/src/main/java/com/paperless/scanner/data/repository/DocumentRepository.kt \
          app/src/main/java/com/paperless/scanner/di/AppModule.kt \
          app/src/test/java/com/paperless/scanner/data/repository/DocumentRepositoryTest.kt && \
  git commit -m "$(cat <<'EOF'
refactor: extract AuditRepository (Phase 3.2 of #51)

Extracts three online-only audit-history methods (getDocumentHistory,
addNote, deleteNote) from DocumentRepository into a dedicated
AuditRepository under data/repository/. Smallest extraction in the
refactor — ~50 LOC moved, no offline-queue, no cascade, no consolidation.

DocumentRepository becomes a thin façade for these methods. No public
API changes, no caller migration.

Closes #168

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

Pre-commit hook runs automatically; allow it. Do NOT use `--no-verify`.

---

## Task 9: Manual on-device smoke

- [ ] **Step 9.1: Build and install debug APK**

Run:
```bash
export JAVA_HOME="$(ls -d '/c/Program Files/Eclipse Adoptium/jdk-21.'* 2>/dev/null | head -1)"
./gradlew :app:installDebug --no-daemon
```

- [ ] **Step 9.2: Smoke test audit flows**

On-device:
1. Open a document detail → tap **History** tab → list of audit-log entries appears (calls `getDocumentHistory`).
2. Notes section → add a note → it appears in the list (calls `addNote`).
3. Notes section → delete the note → it disappears (calls `deleteNote`).
4. Airplane mode + retry each → expect offline error message instead of crash.

If any flow breaks, diff the moved methods against the originals via `git show 30d3d57:app/src/main/java/com/paperless/scanner/data/repository/DocumentRepository.kt`.

---

## Task 10: Push and open PR

- [ ] **Step 10.1: Push branch**

Run:
```bash
cd "E:/Git/paperless client" && git push -u origin refactor/51-extract-audit-repository
```
Pre-push hook runs auto-rebase + full validate-ci.sh. Do NOT use `--no-verify`.

- [ ] **Step 10.2: Open the PR**

Run:
```bash
cd "E:/Git/paperless client" && gh pr create \
  --base main \
  --head refactor/51-extract-audit-repository \
  --title "refactor: extract AuditRepository (Phase 3.2 of #51)" \
  --body "$(cat <<'EOF'
## Summary
- Extracts the 3 audit-history methods (`getDocumentHistory`, `addNote`, `deleteNote`) into a dedicated `AuditRepository`.
- Smallest extraction in the refactor: thin online-only API wrappers, no offline-queue, no cascade, no consolidation, no dead code.
- `DocumentRepository` becomes a façade for these methods. Zero caller changes.

## Phase
Phase 3.2 of #51 — DocumentRepository God-class refactor. See [spec](docs/superpowers/specs/2026-05-06-document-repository-refactor-phase3-2-design.md).

## Tests
- ✅ Existing `DocumentRepositoryTest` stays green (façade delegates 1:1; 18th ctor arg added).
- ✅ New `AuditRepositoryTest` adds 6 cases (online + offline for each of the 3 methods, including `CreateNoteRequest` shape verification via slot capture for `addNote`).

## Test plan
- [x] `./scripts/validate-ci.sh` (RELEASE variants) green locally
- [ ] On-device smoke: history loads, add note, delete note, offline behavior

Closes #168
EOF
)"
```

- [ ] **Step 10.3: Address CodeRabbit review**

Wait for CodeRabbit. Action small real bugs (≤5 LOC) inline. Skip architectural suggestions (Dispatchers.IO, error-string-in-data-layer) with rationale referencing spec §9 (Out-of-Scope).

- [ ] **Step 10.4: Squash-merge**

```bash
gh pr merge --squash --delete-branch
```

---

## Task 11: Post-merge — update memory

- [ ] **Step 11.1: Sync local main**

```bash
cd "E:/Git/paperless client" && git checkout main && git fetch origin && git rebase origin/main
```
Use `git rebase --skip` for any add/add doc conflicts (your local commits are in origin via the squash).

- [ ] **Step 11.2: Rename + update memory file**

Rename `C:\Users\marcu\.claude\projects\E--Git-paperless-client\memory\issue_51_phase3_1_complete.md` → `issue_51_phase3_2_complete.md` (or update title in-place). Add Phase 3.2 row to the State table:

```
| <PR#> | #168 | AuditRepository (Phase 3.2) | ~80 | 6 | <merge SHA> |
```

Update DocumentRepository LOC progression line: `503 (Phase 3.1) → ~440 (Phase 3.2). Cumulative: 1405 → 440 (-69 %).`

Update "How to resume" — recommend Phase 4 PermissionRepository (#170) as next (S-effort, similar mechanical move). Phase 3.3 (#169) remains HIGHEST RISK / dedicated brainstorm.

- [ ] **Step 11.3: Update MEMORY.md pointer**

Edit `C:\Users\marcu\.claude\projects\E--Git-paperless-client\memory\MEMORY.md`:
```
- [Issue #51 DocumentRepository refactor — Phase 3.2 done, Phase 3.3-5 stubbed](issue_51_phase3_2_complete.md) — resume: brainstorm Phase 4 PermissionRepository (#170) spec next; #169-#171 sub-issues open; Phase-3.3 debt = 5 inline markers
```

---

## Acceptance Criteria (from spec §11)

- [ ] 1 new file `AuditRepository.kt` with `@Inject constructor` + `@Singleton`
- [ ] DocumentRepository.kt reduced by ≥ 50 LOC (503 → ≤ 460)
- [ ] DocumentRepository constructor extended by exactly 1 field (`audit`) at the LAST position
- [ ] 3 affected façade methods are one-line delegations to `audit.*`
- [ ] DocumentRepositoryTest green (existing API unchanged; 18th ctor arg)
- [ ] 6 new tests in `AuditRepositoryTest.kt`, all green
- [ ] `./scripts/validate-ci.sh` green before push
- [ ] CodeRabbit findings actioned or skipped with rationale
- [ ] Sub-issue #168 merged via "Closes #168"
- [ ] Manual on-device smoke per Task 9.2 completed
- [ ] Memory file + MEMORY.md pointer updated post-merge
