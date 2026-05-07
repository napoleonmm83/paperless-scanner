# Phase 5 — DocumentRepository Façade Cleanup + ViewModel Migration — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Migrate 5 callers (1 worker + 4 ViewModels) from the `DocumentRepository` façade to the specialized sub-repositories, then shrink `DocumentRepository.kt` to upload/download-only (~120 LOC). Closes Issue #51.

**Architecture:** Risk-ascending PR sequence — dead-param cleanup first, workers next, simple VMs, complex VMs, final façade-shrink. Each migration PR is a pure mechanical swap: existing test logic stays, only mock targets change. No behavior changes; no new tests; no bug fixes.

**Tech Stack:** Kotlin 2.0, Hilt DI, Jetpack Compose, JUnit 4 + MockK, Gradle (RELEASE variants for CI parity).

**Spec:** `docs/superpowers/specs/2026-05-07-document-repository-refactor-phase5-design.md`
**Issue:** [#171](https://github.com/napoleonmm83/paperless-scanner/issues/171), parent [#51](https://github.com/napoleonmm83/paperless-scanner/issues/51)

---

## Pre-flight (before starting any task)

- [ ] **Step 0.1: Verify clean working tree on `main`**

Run:
```bash
git status
git log --oneline -3
```

Expected: working tree clean, current branch `main`, last commit is `d7f2630` (Phase 5 spec) or later.

- [ ] **Step 0.2: Verify JDK 21 is available**

Run:
```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.5.11-hotspot"
"$JAVA_HOME/bin/java" -version
```

Expected: `openjdk version "21.0.5"` (or matching 21.x). If different path, check feedback memory `feedback_jdk_path.md`.

- [ ] **Step 0.3: Verify all current tests are green**

Run:
```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.5.11-hotspot"
./scripts/validate-ci.sh --quick
```

Expected: green. If `AppLockManagerTest` flake → re-run; if other failures → STOP, investigate before starting Phase 5.

---

## Task 1: PR1 — Remove dead constructor params

**Files:**
- Modify: `app/src/main/java/com/paperless/scanner/data/repository/DocumentRepository.kt:33-52`
- Modify: `app/src/test/java/com/paperless/scanner/data/repository/DocumentRepositoryTest.kt`

**Branch name:** `refactor/issue-51-phase5-pr1-dead-ctor-params`

- [ ] **Step 1.1: Create branch**

```bash
git checkout main
git pull --rebase
git checkout -b refactor/issue-51-phase5-pr1-dead-ctor-params
```

- [ ] **Step 1.2: Confirm dead params are unused in DocumentRepository**

Run:
```bash
grep -n "cachedTaskDao\|pendingChangeDao\|serverHealthMonitor" \
  app/src/main/java/com/paperless/scanner/data/repository/DocumentRepository.kt
```

Expected: matches ONLY in the constructor parameter list (lines 38, 39, 41). Zero matches in method bodies. If method-body matches found → STOP; the param is still in use, spec assumption violated.

- [ ] **Step 1.3: Remove the three params from `DocumentRepository.kt`**

Edit lines 33-52 — drop these three lines:
```kotlin
    private val cachedTaskDao: CachedTaskDao,
    private val pendingChangeDao: PendingChangeDao,
    private val serverHealthMonitor: ServerHealthMonitor,
```

Also remove the now-unused imports at the top of the file:
```kotlin
import com.paperless.scanner.data.database.dao.CachedTaskDao
import com.paperless.scanner.data.database.dao.PendingChangeDao
import com.paperless.scanner.data.network.ServerHealthMonitor
```

(Verify via grep that the types appear nowhere else in the file before removing imports.)

- [ ] **Step 1.4: Update `DocumentRepositoryTest.kt` — drop the three mocks**

Find the mock declarations (typically `@MockK private lateinit var cachedTaskDao: CachedTaskDao` etc.) and remove them. Find the constructor call in `setup()` that passes these three mocks and remove the three arguments. Remove the now-unused imports.

- [ ] **Step 1.5: Run unit tests for DocumentRepository**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.5.11-hotspot"
./gradlew testReleaseUnitTest --tests "com.paperless.scanner.data.repository.DocumentRepositoryTest" --no-daemon
```

Expected: BUILD SUCCESSFUL, all DocumentRepositoryTest cases pass.

- [ ] **Step 1.6: Run full CI validation**

```bash
./scripts/validate-ci.sh
```

Expected: all phases green.

- [ ] **Step 1.7: Commit**

```bash
git add app/src/main/java/com/paperless/scanner/data/repository/DocumentRepository.kt \
        app/src/test/java/com/paperless/scanner/data/repository/DocumentRepositoryTest.kt
git commit -m "$(cat <<'EOF'
refactor: remove dead ctor params from DocumentRepository (Phase 5 PR1 of #51)

cachedTaskDao, pendingChangeDao, serverHealthMonitor were obsoleted by
DocumentSyncRepository (Phase 3.3). Sub-repos that need them inject
them directly. No behavior change.

Refs #171

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 1.8: Push (pre-push hook auto-rebases + runs full CI)**

```bash
git push -u origin refactor/issue-51-phase5-pr1-dead-ctor-params
```

- [ ] **Step 1.9: Open PR**

```bash
gh pr create --base main \
  --title "refactor: remove dead DocumentRepository ctor params (Phase 5 PR1 of #51)" \
  --body "$(cat <<'EOF'
## Summary
- Remove `cachedTaskDao`, `pendingChangeDao`, `serverHealthMonitor` from DocumentRepository constructor — unused since Phase 3.3 (DocumentSyncRepository extraction).
- Drop the corresponding mocks from DocumentRepositoryTest.
- No behavior change.

## Test plan
- [x] testReleaseUnitTest green
- [x] lintRelease green
- [x] assembleRelease green
- [ ] CI all checks green
- [ ] Manual smoke test: app launches, no Hilt binding errors

Refs #171, #51

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 1.10: Wait for CI, address CodeRabbit, merge**

```bash
gh pr checks --watch
# After green:
gh pr merge --squash --delete-branch
git checkout main && git pull --rebase
```

If CI fails: investigate locally, push fix as new commit on the branch, re-run.
If CodeRabbit raises actionable findings: address in a fix commit; if deferred, post rationale per yesterday's pattern.

---

## Task 2: PR2 — Migrate `TrashDeleteWorker`

**Files:**
- Modify: `app/src/main/java/com/paperless/scanner/worker/TrashDeleteWorker.kt`
- Modify (if exists): `app/src/test/java/com/paperless/scanner/worker/TrashDeleteWorkerTest.kt`

**Branch name:** `refactor/issue-51-phase5-pr2-trash-delete-worker`

- [ ] **Step 2.1: Create branch**

```bash
git checkout main && git pull --rebase
git checkout -b refactor/issue-51-phase5-pr2-trash-delete-worker
```

- [ ] **Step 2.2: Inspect current injection in TrashDeleteWorker**

Run:
```bash
grep -n "documentRepository\|DocumentRepository" \
  app/src/main/java/com/paperless/scanner/worker/TrashDeleteWorker.kt
```

Note the constructor `@Inject` site and the single call at line 77.

- [ ] **Step 2.3: Swap injection and call**

In `TrashDeleteWorker.kt`:
- Replace `private val documentRepository: DocumentRepository` (in the @AssistedInject ctor) with `private val trashRepository: TrashRepository`.
- Update the import: remove `import com.paperless.scanner.data.repository.DocumentRepository`, add `import com.paperless.scanner.data.repository.TrashRepository`.
- Replace `documentRepository.permanentlyDeleteDocument(documentId)` at line 77 with `trashRepository.permanentlyDeleteDocument(documentId)`.

- [ ] **Step 2.4: Update test (if it exists)**

Run:
```bash
ls app/src/test/java/com/paperless/scanner/worker/TrashDeleteWorkerTest.kt 2>/dev/null
```

If the file exists: swap the mock from `mockk<DocumentRepository>()` to `mockk<TrashRepository>()`, update the constructor argument and `every` blocks. If it doesn't exist, skip — no test to update.

- [ ] **Step 2.5: Run worker tests + Hilt-relevant tests**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.5.11-hotspot"
./gradlew testReleaseUnitTest --no-daemon
```

Expected: green.

- [ ] **Step 2.6: Run full CI validation**

```bash
./scripts/validate-ci.sh
```

Expected: all phases green.

- [ ] **Step 2.7: Commit**

```bash
git add app/src/main/java/com/paperless/scanner/worker/TrashDeleteWorker.kt
# If test was updated, add it too:
# git add app/src/test/java/com/paperless/scanner/worker/TrashDeleteWorkerTest.kt
git commit -m "$(cat <<'EOF'
refactor: migrate TrashDeleteWorker to TrashRepository (Phase 5 PR2 of #51)

Replace DocumentRepository façade injection with direct TrashRepository
injection. Single call site at L77. No behavior change.

Refs #171

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

- [ ] **Step 2.8: Push, PR, merge**

```bash
git push -u origin refactor/issue-51-phase5-pr2-trash-delete-worker

gh pr create --base main \
  --title "refactor: migrate TrashDeleteWorker to TrashRepository (Phase 5 PR2 of #51)" \
  --body "$(cat <<'EOF'
## Summary
- Swap `DocumentRepository` injection for direct `TrashRepository`.
- Single call: `permanentlyDeleteDocument` (line 77).
- No behavior change; façade method still exists (deleted in PR7).

## Test plan
- [x] testReleaseUnitTest green
- [ ] CI all checks green
- [ ] Manual smoke test: enqueue + run a trash deletion, verify document removed

Refs #171, #51

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"

gh pr checks --watch
gh pr merge --squash --delete-branch
git checkout main && git pull --rebase
```

---

## Task 3: PR3 — Migrate `TrashViewModel`

**Files:**
- Modify: `app/src/main/java/com/paperless/scanner/ui/screens/trash/TrashViewModel.kt`
- Modify: `app/src/test/java/com/paperless/scanner/ui/screens/trash/TrashViewModelTest.kt`

**Branch name:** `refactor/issue-51-phase5-pr3-trash-viewmodel`

- [ ] **Step 3.1: Create branch**

```bash
git checkout main && git pull --rebase
git checkout -b refactor/issue-51-phase5-pr3-trash-viewmodel
```

- [ ] **Step 3.2: Swap injection in TrashViewModel.kt**

In the primary constructor (typically annotated `@HiltViewModel`):
- Replace `private val documentRepository: DocumentRepository` with `private val trashRepository: TrashRepository`.
- Update imports.

- [ ] **Step 3.3: Replace all 8 call sites**

The 8 calls (current line numbers):

| Line | Before | After |
|------|--------|-------|
| 104 | `documentRepository.observeTrashedDocuments()` | `trashRepository.observeTrashedDocuments()` |
| 292 | `documentRepository.getTrashDocuments(page = page, pageSize = 100)` | `trashRepository.getTrashDocuments(page = page, pageSize = 100)` |
| 312 | `documentRepository.cleanupOrphanedTrashDocs(serverTrashIds)` | `trashRepository.cleanupOrphanedTrashDocs(serverTrashIds)` |
| 341 | `documentRepository.observeTrashedDocumentsCount()` | `trashRepository.observeTrashedDocumentsCount()` |
| 360 | `documentRepository.restoreDocument(documentId)` | `trashRepository.restoreDocument(documentId)` |
| 385 | `documentRepository.permanentlyDeleteDocument(documentId)` | `trashRepository.permanentlyDeleteDocument(documentId)` |
| 430 | `documentRepository.restoreDocuments(documentIdsToRestore)` | `trashRepository.restoreDocuments(documentIdsToRestore)` |
| 470 | `documentRepository.permanentlyDeleteDocuments(documentIds)` | `trashRepository.permanentlyDeleteDocuments(documentIds)` |

After replacement, run:
```bash
grep -n "documentRepository" app/src/main/java/com/paperless/scanner/ui/screens/trash/TrashViewModel.kt
```
Expected: zero matches.

- [ ] **Step 3.4: Update TrashViewModelTest.kt**

Replace all `mockk<DocumentRepository>` / `every { documentRepository... }` with `mockk<TrashRepository>` / `every { trashRepository... }`. Update the SUT constructor invocation in `setup()`.

After replacement, run:
```bash
grep -n "DocumentRepository\|documentRepository" \
  app/src/test/java/com/paperless/scanner/ui/screens/trash/TrashViewModelTest.kt
```
Expected: zero matches.

- [ ] **Step 3.5: Run TrashViewModelTest**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.5.11-hotspot"
./gradlew testReleaseUnitTest --tests "com.paperless.scanner.ui.screens.trash.TrashViewModelTest" --no-daemon
```

Expected: all cases pass.

- [ ] **Step 3.6: Full CI validation**

```bash
./scripts/validate-ci.sh
```

- [ ] **Step 3.7: Commit, push, PR, merge**

```bash
git add app/src/main/java/com/paperless/scanner/ui/screens/trash/TrashViewModel.kt \
        app/src/test/java/com/paperless/scanner/ui/screens/trash/TrashViewModelTest.kt
git commit -m "$(cat <<'EOF'
refactor: migrate TrashViewModel to TrashRepository (Phase 5 PR3 of #51)

Swap DocumentRepository façade injection for direct TrashRepository.
8 call sites updated 1:1. No behavior change.

Refs #171

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"

git push -u origin refactor/issue-51-phase5-pr3-trash-viewmodel

gh pr create --base main \
  --title "refactor: migrate TrashViewModel to TrashRepository (Phase 5 PR3 of #51)" \
  --body "$(cat <<'EOF'
## Summary
- Swap injection: `DocumentRepository` → `TrashRepository`.
- 8 call sites migrated 1:1 (all on the same sub-repo, mechanical replacement).
- No behavior change; façade methods still exist until PR7.

## Test plan
- [x] TrashViewModelTest green
- [x] validate-ci.sh green
- [ ] CI all checks green
- [ ] Manual smoke test: open Trash screen, restore + permanently-delete flows

Refs #171, #51

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"

gh pr checks --watch
gh pr merge --squash --delete-branch
git checkout main && git pull --rebase
```

---

## Task 4: PR4 — Migrate `DocumentsViewModel`

**Files:**
- Modify: `app/src/main/java/com/paperless/scanner/ui/screens/documents/DocumentsViewModel.kt`
- Modify: `app/src/test/java/com/paperless/scanner/ui/screens/documents/DocumentsViewModelTest.kt`

**Branch name:** `refactor/issue-51-phase5-pr4-documents-viewmodel`

- [ ] **Step 4.1: Create branch**

```bash
git checkout main && git pull --rebase
git checkout -b refactor/issue-51-phase5-pr4-documents-viewmodel
```

- [ ] **Step 4.2: Swap injections (3 new repos)**

In `DocumentsViewModel.kt` primary constructor: replace `private val documentRepository: DocumentRepository` with three injections:
```kotlin
    private val documentListRepository: DocumentListRepository,
    private val documentCountRepository: DocumentCountRepository,
    private val trashRepository: TrashRepository,
```
Update imports accordingly.

- [ ] **Step 4.3: Replace 5 call sites**

| Line | Before | After |
|------|--------|-------|
| 137 | `documentRepository.getDocumentsPaged(...)` | `documentListRepository.getDocumentsPaged(...)` |
| 205 | `documentRepository.observeCountWithFilter(...)` | `documentCountRepository.observeCountWithFilter(...)` |
| 242 | `documentRepository.getDocuments(...)` | `documentListRepository.getDocuments(...)` |
| 365 | `documentRepository.deleteDocument(documentId)` | `trashRepository.deleteDocument(documentId)` |
| 392 | `documentRepository.restoreDocument(deletedDoc.id)` | `trashRepository.restoreDocument(deletedDoc.id)` |

Verify zero remaining `documentRepository` references in the file.

- [ ] **Step 4.4: Update DocumentsViewModelTest.kt**

Replace single `mockk<DocumentRepository>()` with three mocks: `mockk<DocumentListRepository>()`, `mockk<DocumentCountRepository>()`, `mockk<TrashRepository>()`. Update each `every { documentRepository... }` to point at the right sub-repo according to the table in step 4.3. Update SUT constructor call.

- [ ] **Step 4.5: Run DocumentsViewModelTest**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.5.11-hotspot"
./gradlew testReleaseUnitTest --tests "com.paperless.scanner.ui.screens.documents.DocumentsViewModelTest" --no-daemon
```

Expected: green. If a test fails because a `coEvery` was on the wrong mock, fix the mock target — the test logic itself is unchanged.

- [ ] **Step 4.6: Full CI validation**

```bash
./scripts/validate-ci.sh
```

- [ ] **Step 4.7: Commit, push, PR, merge**

```bash
git add app/src/main/java/com/paperless/scanner/ui/screens/documents/DocumentsViewModel.kt \
        app/src/test/java/com/paperless/scanner/ui/screens/documents/DocumentsViewModelTest.kt
git commit -m "$(cat <<'EOF'
refactor: migrate DocumentsViewModel to specialized repos (Phase 5 PR4 of #51)

Swap DocumentRepository façade for direct injection of
DocumentListRepository, DocumentCountRepository, TrashRepository.
5 call sites migrated. No behavior change.

Refs #171

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"

git push -u origin refactor/issue-51-phase5-pr4-documents-viewmodel

gh pr create --base main \
  --title "refactor: migrate DocumentsViewModel to specialized repos (Phase 5 PR4 of #51)" \
  --body "$(cat <<'EOF'
## Summary
- Swap façade injection for direct `DocumentListRepository` + `DocumentCountRepository` + `TrashRepository`.
- 5 call sites migrated according to spec §6.
- No behavior change.

## Test plan
- [x] DocumentsViewModelTest green
- [x] validate-ci.sh green
- [ ] CI all checks green
- [ ] Manual smoke test: open Documents screen, scroll-paginate, delete-then-restore

Refs #171, #51

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"

gh pr checks --watch
gh pr merge --squash --delete-branch
git checkout main && git pull --rebase
```

---

## Task 5: PR5 — Migrate `HomeViewModel`

**Files:**
- Modify: `app/src/main/java/com/paperless/scanner/ui/screens/home/HomeViewModel.kt`
- Modify: `app/src/test/java/com/paperless/scanner/ui/screens/home/HomeViewModelTest.kt`

**Branch name:** `refactor/issue-51-phase5-pr5-home-viewmodel`

- [ ] **Step 5.1: Create branch**

```bash
git checkout main && git pull --rebase
git checkout -b refactor/issue-51-phase5-pr5-home-viewmodel
```

- [ ] **Step 5.2: Swap injections (4 new repos)**

In `HomeViewModel.kt` primary constructor: replace `private val documentRepository: DocumentRepository` with four injections:
```kotlin
    private val documentListRepository: DocumentListRepository,
    private val documentCountRepository: DocumentCountRepository,
    private val trashRepository: TrashRepository,
    private val documentMetadataRepository: DocumentMetadataRepository,
```
Update imports.

- [ ] **Step 5.3: Replace all 17 call sites**

Use the spec §6 HomeViewModel section as ground truth. Each call gets a 1:1 prefix swap. Concretely:

| Line | Repository |
|------|-----------|
| 268 | `documentListRepository.observeDocuments(...)` |
| 360 | `documentCountRepository.observeUntaggedDocumentsCount()` |
| 372 | `trashRepository.observeTrashedDocumentsCount()` |
| 384 | `trashRepository.observeOldestDeletedTimestamp()` |
| 427 | `documentCountRepository.getUntaggedCount()` |
| 432 | `documentListRepository.getDocuments(...)` |
| 440 | `trashRepository.getTrashDocuments(...)` |
| 504 | `documentMetadataRepository.getDocument(docId, forceRefresh = true)` |
| 553 | `documentCountRepository.getUntaggedCount()` |
| 558 | `documentListRepository.getDocuments(...)` |
| 581 | `trashRepository.getTrashDocuments(...)` |
| 594 | `trashRepository.cleanupOrphanedTrashDocs(...)` |
| 702 | `documentCountRepository.getDocumentCount(forceRefresh)` |
| 707 | `documentListRepository.getDocuments(...)` |
| 799 | `trashRepository.deleteDocument(documentId)` |
| 834 | `trashRepository.restoreDocument(deletedDoc.id)` |
| 918 | `documentListRepository.getUntaggedDocuments()` |
| 1135 | `documentMetadataRepository.updateDocument(documentId, tags = tagIds)` |

After replacement:
```bash
grep -n "documentRepository" app/src/main/java/com/paperless/scanner/ui/screens/home/HomeViewModel.kt
```
Expected: zero matches.

**Note:** The duplicate `cleanupOrphanedTrashDocs` calls at L312 and L594 (mentioned in the spec) — the actual file may only have L594 in the cleanup path; if you find both, leave both as-is. VM-internal hygiene is out of scope per spec §8.

- [ ] **Step 5.4: Update HomeViewModelTest.kt**

Replace one mock with four. Update each `every`/`coEvery` block to target the correct sub-repo per the table above. SUT constructor must be updated.

- [ ] **Step 5.5: Run HomeViewModelTest**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.5.11-hotspot"
./gradlew testReleaseUnitTest --tests "com.paperless.scanner.ui.screens.home.HomeViewModelTest" --no-daemon
```

Expected: green.

- [ ] **Step 5.6: Full CI validation**

```bash
./scripts/validate-ci.sh
```

- [ ] **Step 5.7: Commit, push, PR, merge**

```bash
git add app/src/main/java/com/paperless/scanner/ui/screens/home/HomeViewModel.kt \
        app/src/test/java/com/paperless/scanner/ui/screens/home/HomeViewModelTest.kt
git commit -m "$(cat <<'EOF'
refactor: migrate HomeViewModel to specialized repos (Phase 5 PR5 of #51)

Swap DocumentRepository façade for direct injection of List, Count,
Trash, and Metadata repos. 17 call sites migrated. No behavior change;
existing patterns (e.g., dual cleanupOrphanedTrashDocs) preserved.

Refs #171

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"

git push -u origin refactor/issue-51-phase5-pr5-home-viewmodel

gh pr create --base main \
  --title "refactor: migrate HomeViewModel to specialized repos (Phase 5 PR5 of #51)" \
  --body "$(cat <<'EOF'
## Summary
- Swap façade for 4 direct sub-repo injections.
- 17 call sites migrated per spec §6.
- VM-internal patterns (e.g., duplicate cleanup-trash call) preserved as-is — out of Phase-5 scope.

## Test plan
- [x] HomeViewModelTest green
- [x] validate-ci.sh green
- [ ] CI all checks green
- [ ] Manual smoke test: home screen loads, untagged count, trash count, oldest-deleted, document tile delete + undo

Refs #171, #51

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"

gh pr checks --watch
gh pr merge --squash --delete-branch
git checkout main && git pull --rebase
```

---

## Task 6: PR6 — Migrate `DocumentDetailViewModel`

**Files:**
- Modify: `app/src/main/java/com/paperless/scanner/ui/screens/documents/DocumentDetailViewModel.kt`
- Modify: `app/src/test/java/com/paperless/scanner/ui/screens/documents/DocumentDetailViewModelTest.kt`

**Branch name:** `refactor/issue-51-phase5-pr6-document-detail-viewmodel`

- [ ] **Step 6.1: Create branch**

```bash
git checkout main && git pull --rebase
git checkout -b refactor/issue-51-phase5-pr6-document-detail-viewmodel
```

- [ ] **Step 6.2: Swap injections (4 new repos)**

In `DocumentDetailViewModel.kt` primary constructor: replace `private val documentRepository: DocumentRepository` with:
```kotlin
    private val documentMetadataRepository: DocumentMetadataRepository,
    private val auditRepository: AuditRepository,
    private val trashRepository: TrashRepository,
    private val permissionRepository: PermissionRepository,
```
Update imports.

- [ ] **Step 6.3: Replace 10 call sites**

| Line | Repository |
|------|-----------|
| 271 | `documentMetadataRepository.observeDocument(documentId)` |
| 336 | `documentMetadataRepository.getDocument(documentId, forceRefresh = true)` |
| 350 | `auditRepository.getDocumentHistory(documentId)` |
| 379 | `trashRepository.deleteDocument(documentId)` |
| 418 | `documentMetadataRepository.updateDocument(...)` |
| 467 | `documentMetadataRepository.addNote(documentId, noteText)` |
| 498 | `documentMetadataRepository.deleteNote(documentId, noteId)` |
| 539 | `permissionRepository.getUsers()` |
| 543 | `permissionRepository.getGroups()` |
| 570 | `permissionRepository.updateDocumentPermissions(...)` |

Verify zero remaining `documentRepository` references.

- [ ] **Step 6.4: Update DocumentDetailViewModelTest.kt**

Replace single mock with four; update mock targets per the table above; update SUT constructor.

- [ ] **Step 6.5: Run DocumentDetailViewModelTest**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.5.11-hotspot"
./gradlew testReleaseUnitTest --tests "com.paperless.scanner.ui.screens.documents.DocumentDetailViewModelTest" --no-daemon
```

Expected: green.

- [ ] **Step 6.6: Full CI validation**

```bash
./scripts/validate-ci.sh
```

- [ ] **Step 6.7: Commit, push, PR, merge**

```bash
git add app/src/main/java/com/paperless/scanner/ui/screens/documents/DocumentDetailViewModel.kt \
        app/src/test/java/com/paperless/scanner/ui/screens/documents/DocumentDetailViewModelTest.kt
git commit -m "$(cat <<'EOF'
refactor: migrate DocumentDetailViewModel to specialized repos (Phase 5 PR6 of #51)

Swap DocumentRepository façade for direct injection of Metadata, Audit,
Trash, and Permission repos. 10 call sites migrated. No behavior change.

Refs #171

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"

git push -u origin refactor/issue-51-phase5-pr6-document-detail-viewmodel

gh pr create --base main \
  --title "refactor: migrate DocumentDetailViewModel to specialized repos (Phase 5 PR6 of #51)" \
  --body "$(cat <<'EOF'
## Summary
- Swap façade for 4 direct sub-repo injections (Metadata, Audit, Trash, Permission).
- 10 call sites migrated per spec §6.
- No behavior change.

## Test plan
- [x] DocumentDetailViewModelTest green
- [x] validate-ci.sh green
- [ ] CI all checks green
- [ ] Manual smoke test: open document detail, edit metadata, add/delete note, change permissions, view history, soft-delete

Refs #171, #51

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"

gh pr checks --watch
gh pr merge --squash --delete-branch
git checkout main && git pull --rebase
```

---

## Task 7: PR7 — Façade-Shrink

**Files:**
- Modify: `app/src/main/java/com/paperless/scanner/data/repository/DocumentRepository.kt`
- Modify: `app/src/test/java/com/paperless/scanner/data/repository/DocumentRepositoryTest.kt`
- Modify: `docs/TECHNICAL.md`

**Branch name:** `refactor/issue-51-phase5-pr7-facade-shrink`

- [ ] **Step 7.1: Pre-check — confirm no caller uses delegated methods**

```bash
git checkout main && git pull --rebase
grep -rn "documentRepository\." app/src/main | grep -v "uploadDocument\|uploadMultiPageDocument\|downloadDocument"
```

Expected: **zero matches**. If any match found → STOP. PR2-PR6 missed a caller; investigate before proceeding.

- [ ] **Step 7.2: Pre-check — same in tests**

```bash
grep -rn "documentRepository\." app/src/test | grep -v "uploadDocument\|uploadMultiPageDocument\|downloadDocument"
```

Expected: matches **only** in `DocumentRepositoryTest.kt` (those tests are about to be deleted). Zero matches in any other test file.

- [ ] **Step 7.3: Create branch**

```bash
git checkout -b refactor/issue-51-phase5-pr7-facade-shrink
```

- [ ] **Step 7.4: Delete delegation methods from DocumentRepository.kt**

Open `app/src/main/java/com/paperless/scanner/data/repository/DocumentRepository.kt`. Identify the contiguous block of delegation methods (currently lines ~233–440 — `observeDocuments` through `cleanupOrphanedTrashDocs`). Delete every method that is a 1-liner of the form `... = subRepo.method(args)`.

Keep:
- `uploadDocument` (~L58)
- `uploadMultiPageDocument` (~L133)
- `downloadDocument` (~L331)
- Any private helpers used by those three methods.

After deletion, run:
```bash
wc -l app/src/main/java/com/paperless/scanner/data/repository/DocumentRepository.kt
```
Expected: ≤ 200 lines.

- [ ] **Step 7.5: Trim constructor**

Remove these now-unused params from the primary constructor:
```kotlin
    private val count: DocumentCountRepository,
    private val metadata: DocumentMetadataRepository,
    private val list: DocumentListRepository,
    private val trash: TrashRepository,
    private val audit: AuditRepository,
    private val permission: PermissionRepository,
```

Remove the corresponding imports.

Final constructor must match spec §2:
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

- [ ] **Step 7.6: Delete delegation tests from DocumentRepositoryTest.kt**

Open `DocumentRepositoryTest.kt`. Delete every test that targets a deleted method. Keep tests for `uploadDocument`, `uploadMultiPageDocument`, `downloadDocument`. Drop the now-unused mocks (`countRepo`, `metadataRepo`, `listRepo`, `trashRepo`, `auditRepo`, `permissionRepo`) from `setup()` and from the SUT constructor call. Drop now-unused imports.

- [ ] **Step 7.7: Update `docs/TECHNICAL.md` architecture section**

Find the section that describes the data-layer/repository structure. Update it to reflect:
- `DocumentRepository` is now upload/download-only.
- Specialized sub-repositories: `DocumentCountRepository`, `DocumentMetadataRepository`, `DocumentListRepository`, `TrashRepository`, `AuditRepository`, `PermissionRepository`, `DocumentSyncRepository`.
- ViewModels inject the specialized repos directly.

If the doc has no current architecture section, add a brief one matching spec §2.

- [ ] **Step 7.8: Run DocumentRepositoryTest**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.5.11-hotspot"
./gradlew testReleaseUnitTest --tests "com.paperless.scanner.data.repository.DocumentRepositoryTest" --no-daemon
```

Expected: green, only upload/download tests run.

- [ ] **Step 7.9: Full CI validation**

```bash
./scripts/validate-ci.sh
```

Expected: all phases green. If any test references a deleted delegation, fix the test reference (it was missed in PR2-PR6).

- [ ] **Step 7.10: Final acceptance check — LOC + grep**

```bash
wc -l app/src/main/java/com/paperless/scanner/data/repository/DocumentRepository.kt
# Expected: ≤200 (target ~120)

grep -rn "documentRepository\." app/src
# Expected: only uploadDocument / uploadMultiPageDocument / downloadDocument calls
```

- [ ] **Step 7.11: Commit, push, PR**

```bash
git add app/src/main/java/com/paperless/scanner/data/repository/DocumentRepository.kt \
        app/src/test/java/com/paperless/scanner/data/repository/DocumentRepositoryTest.kt \
        docs/TECHNICAL.md
git commit -m "$(cat <<'EOF'
refactor: shrink DocumentRepository to upload/download-only (Phase 5 PR7 of #51)

Final phase of Issue #51. All delegation methods deleted (~25 methods);
sub-repo ctor params removed. DocumentRepository.kt reduced to upload/
upload-multipage/download. Specialized sub-repos handle everything else
via direct injection in ViewModels (PRs 2-6).

DocumentRepository.kt: 1405 → ~120 LOC (-91%) over Phases 1-5.

Closes #171
Closes #51

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"

git push -u origin refactor/issue-51-phase5-pr7-facade-shrink

gh pr create --base main \
  --title "refactor: shrink DocumentRepository to upload/download-only (Phase 5 PR7 of #51)" \
  --body "$(cat <<'EOF'
## Summary
- Delete ~25 delegation methods now unreachable after PR2-PR6 caller migrations.
- Trim constructor: drop 6 sub-repo params (count, metadata, list, trash, audit, permission).
- Update `DocumentRepositoryTest.kt`: keep only upload/download tests.
- Update `docs/TECHNICAL.md` architecture section.
- Final LOC: ≤200 (target ~120).

## Test plan
- [x] DocumentRepositoryTest green (upload/download only)
- [x] validate-ci.sh green
- [x] grep verification: only upload/download calls remain in app/src
- [ ] CI all checks green
- [ ] Manual smoke test: full app session — upload, scan, view, edit, delete, restore

## Closure
Closes #171 (Phase 5 sub-issue).
Closes #51 (parent — DocumentRepository God-class refactor).

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"

gh pr checks --watch
```

- [ ] **Step 7.12: Address CodeRabbit + merge**

After CodeRabbit review, address actionable findings (or post deferred-rationale comments per yesterday's pattern), then:
```bash
gh pr merge --squash --delete-branch
git checkout main && git pull --rebase
```

---

## Task 8: Issue #51 closure

After PR7 merges:

- [ ] **Step 8.1: Verify both issues are closed**

```bash
gh issue view 171 --repo napoleonmm83/paperless-scanner --json state -q .state
gh issue view 51  --repo napoleonmm83/paperless-scanner --json state -q .state
```

Expected: both `CLOSED`. If GitHub didn't auto-close (e.g., commit message phrasing wasn't recognized), close manually:
```bash
gh issue close 171 --repo napoleonmm83/paperless-scanner --comment "Closed by PR7"
gh issue close 51  --repo napoleonmm83/paperless-scanner --comment "All phases complete; sub-issues closed"
```

- [ ] **Step 8.2: Open follow-up issue — Context removal**

```bash
gh issue create --repo napoleonmm83/paperless-scanner \
  --title "[refactor] Remove Context from DocumentRepository data layer" \
  --label "area:refactor" \
  --body "$(cat <<'EOF'
## Why
After Issue #51 closed, `DocumentRepository` still injects `android.content.Context` for two reasons:
- `context.getString(R.string.error_*)` in `uploadMultiPageDocument` (3 sites)
- `context.cacheDir` in `downloadDocument` (1 site)

Both are legitimate Context uses, but they couple the data layer to Android resources. Cleaner architecture:
- Replace `getString` calls with typed `PaperlessException` errors; resolve to user-facing strings in the UI layer.
- Inject `cacheDir: File` via Hilt provider instead of Context.

## Scope
- Touch DocumentRepository.kt + the UI layer that consumes its errors (DocumentDetail flow).
- Add Hilt provider for `cacheDir`.
- Remove `Context` from DocumentRepository ctor.

## Out of scope
- Other repos that legitimately use Context (none currently identified).

## Effort
S–M
EOF
)"
```

- [ ] **Step 8.3: Open follow-up issue — DTO→Domain mapping**

```bash
gh issue create --repo napoleonmm83/paperless-scanner \
  --title "[refactor] DTO→Domain mapping for Permission and Trash repositories" \
  --label "area:refactor" \
  --body "$(cat <<'EOF'
## Why
After Issue #51 closed, two API leaks remain in the repository layer:
- `PermissionRepository.getUsers()` returns `Result<List<data.api.models.User>>` (DTO).
- `PermissionRepository.getGroups()` returns `Result<List<data.api.models.Group>>` (DTO).
- `TrashRepository.observeTrashedDocuments()` returns `Flow<List<data.database.entities.CachedDocument>>` (DB entity).

Domain layer should not see DTO/entity types.

## Scope
- Define domain types `User`, `Group` in `domain/model` (if missing).
- Add mapper functions DTO → domain.
- Update repo signatures + all callers (DocumentDetailViewModel for Permission; TrashViewModel for entity).

## Effort
S
EOF
)"
```

- [ ] **Step 8.4: Memory update**

Rename the resume file and update the index pointer.

```bash
cd "C:/Users/marcu/.claude/projects/E--Git-paperless-client/memory/"
mv issue_51_phase3_3_complete.md issue_51_complete.md
```

Edit `issue_51_complete.md` frontmatter:
- `name: Issue #51 — DocumentRepository decomposition complete`
- `description: Resume notes — Issue #51 fully closed on 2026-05-XX. All extraction + façade-shrink + caller migration complete. Two follow-up issues opened.`

Edit body to reflect Phase 5 completion (replace "Only Phase 5 (#171) remains" with "All phases complete on 2026-05-XX. PRs #184–#190 (or actual PR numbers).").

Edit `MEMORY.md` pointer line:
```
- [Issue #51 DocumentRepository refactor — fully complete](issue_51_complete.md) — all 8 phases done; 2 follow-up issues opened (Context removal, DTO→Domain mapping)
```

- [ ] **Step 8.5: Final state verification**

```bash
cd "E:/Git/paperless client"
git log --oneline -10
wc -l app/src/main/java/com/paperless/scanner/data/repository/*.kt
```

Expected:
- Recent commits show all 7 Phase 5 PRs merged.
- DocumentRepository.kt ≤ 200 LOC.
- Sub-repos at expected sizes per spec §2.

---

## Notes for the engineer

- **Pre-push hook.** The repo has a pre-push hook that auto-rebases against remote and runs `validate-ci.sh`. Do NOT use `--no-verify`. If the hook reports a rebase failure, resolve manually and retry.
- **CodeRabbit pattern.** After opening each PR, CodeRabbit posts review comments within ~5 minutes. Triage:
  - Actionable + correct → fix in a new commit, push, no force-push.
  - Deferred (out-of-scope per spec §8) → post a brief comment with rationale citing the spec.
  - False positive → post a comment with reasoning, request "review again" if needed.
- **Per-PR memory writes.** Optional but proven useful: after each PR merges, append a brief observation to memory (which PR landed, any surprise findings).
- **Internal release per merge.** GitHub Actions auto-bumps version + uploads to Internal Track. Each PR yields one internal release; that's expected.
- **Rollback.** If a migration PR causes a regression discovered post-merge, the rollback is a `git revert` of the merge commit (not the squashed commit hash). The façade method still exists in main during PR2-PR6 — so a revert is safe.
- **JDK 21 export.** Every Bash invocation that runs Gradle MUST start with the JAVA_HOME export — system JAVA_HOME points at JDK 25 which breaks the Kotlin compiler. See feedback memory `feedback_jdk_path.md`.
