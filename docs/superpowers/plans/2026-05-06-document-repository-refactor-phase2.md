# DocumentRepository Refactor — Phase 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract `DocumentCountRepository`, `DocumentMetadataRepository`, and `DocumentListRepository` from `DocumentRepository` while keeping the latter as a 1:1 façade. No public API changes, no caller migration.

**Architecture:** Three sequential PRs (#165 → #166 → #164). Each PR creates one new repository in `data/repository/`, updates `DocumentRepository` to delegate to it, extends `AppModule.provideDocumentRepository`, and adds dedicated tests. The façade keeps current signatures byte-identical so callers (ViewModels, Workers, SyncManager) need zero changes.

**Tech Stack:** Kotlin 2.0, Hilt DI, Kotlin Flow + Paging 3, Room (CachedDocumentDao), Robolectric + mockk for tests, Gradle (`testReleaseUnitTest`, `lintRelease`, `assembleRelease`), `validate-ci.sh`.

**Spec reference:** [`docs/superpowers/specs/2026-05-06-document-repository-refactor-phase2-design.md`](../specs/2026-05-06-document-repository-refactor-phase2-design.md)

**Phase 1 reference (templates):**
- Service classes: `app/src/main/java/com/paperless/scanner/data/service/{ImageProcessorService,PdfGeneratorService,DocumentSerializer}.kt`
- Service tests: `app/src/test/java/com/paperless/scanner/data/service/{*Test}.kt`
- Existing repo tests (mockk + Robolectric pattern): `app/src/test/java/com/paperless/scanner/data/repository/{TagRepositoryTest,DocumentRepositoryTest}.kt`

---

## File Structure

### Files created (across all 3 PRs)

| Path | PR | Responsibility |
|---|---|---|
| `app/src/main/java/com/paperless/scanner/data/repository/DocumentCountRepository.kt` | PR1 (#165) | 4 count methods (2 Flow consolidated via private `CountFilter` sealed class, 2 suspend separate) |
| `app/src/test/java/com/paperless/scanner/data/repository/DocumentCountRepositoryTest.kt` | PR1 | 8 test cases |
| `app/src/main/java/com/paperless/scanner/data/repository/DocumentMetadataRepository.kt` | PR2 (#166) | `observeDocument`, `getDocument`, `updateDocument`, `updateDocumentPermissions` + 2 private helpers |
| `app/src/test/java/com/paperless/scanner/data/repository/DocumentMetadataRepositoryTest.kt` | PR2 | 12 test cases |
| `app/src/main/java/com/paperless/scanner/data/repository/DocumentListRepository.kt` | PR3 (#164) | `observeDocuments`, `getUntaggedDocuments`, `getDocumentsPaged`, `getDocuments`, `searchDocuments`, `getRecentDocuments` |
| `app/src/test/java/com/paperless/scanner/data/repository/DocumentListRepositoryTest.kt` | PR3 | 10 test cases |

### Files modified (across all 3 PRs)

| Path | PR1 | PR2 | PR3 | What changes |
|---|---|---|---|---|
| `app/src/main/java/com/paperless/scanner/data/repository/DocumentRepository.kt` | ✓ | ✓ | ✓ | Constructor +1 dep per PR; matching method bodies become one-line delegations; private helpers removed in PR2 |
| `app/src/main/java/com/paperless/scanner/di/AppModule.kt` | ✓ | ✓ | ✓ | `provideDocumentRepository` signature gains 1 param per PR |

---

## Pre-PR Setup (run once before PR1)

- [ ] **Step P.1: Confirm baseline state**

Run:
```bash
cd "E:/Git/paperless client" && git status && git log --oneline -3
```

Expected: working tree clean, on `main`, latest commit is `ea31e5f docs(superpowers): add Phase 2 design spec for #51`.

- [ ] **Step P.2: Verify the existing test suite is green on main**

Run:
```bash
export JAVA_HOME="$(/c/Program\ Files/Eclipse\ Adoptium/jdk-21.*/bin/java -XshowSettings:properties -version 2>&1 | grep 'java.home' | awk -F'= ' '{print $2}')"
./gradlew :app:testReleaseUnitTest --tests "*DocumentRepositoryTest*" --no-daemon
```

Expected: BUILD SUCCESSFUL, all tests pass. If any fail, STOP and investigate before starting Phase 2.

(Per CLAUDE.md `feedback_jdk_path.md` memory: system JAVA_HOME points at JDK 25 which breaks the Kotlin compiler. Always export JDK 21 in each Bash invocation. Adjust the path glob if your local installation differs.)

---

## PR 1 — Extract DocumentCountRepository (#165)

**Branch:** `refactor/51-extract-document-count`
**LOC moved:** ~110 (4 methods + private sealed class)
**Effort:** S
**Pilot PR — validates the Phase-2 pattern.**

### Task 1.1: Create branch

- [ ] **Step 1.1.1: Create and checkout the branch**

Run:
```bash
cd "E:/Git/paperless client" && git checkout -b refactor/51-extract-document-count
```

Expected: `Switched to a new branch 'refactor/51-extract-document-count'`.

### Task 1.2: Create DocumentCountRepository

**Files:**
- Create: `app/src/main/java/com/paperless/scanner/data/repository/DocumentCountRepository.kt`

- [ ] **Step 1.2.1: Write the new repository file**

Create `app/src/main/java/com/paperless/scanner/data/repository/DocumentCountRepository.kt` with this exact content:

```kotlin
package com.paperless.scanner.data.repository

import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.api.safeApiCall
import com.paperless.scanner.data.database.DocumentFilterQueryBuilder
import com.paperless.scanner.data.database.dao.CachedDocumentDao
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.domain.model.DocumentFilter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * Phase 2.2 of #51 — extracted from DocumentRepository.
 *
 * Owns the four document-count methods. Two reactive Flow methods are unified
 * internally via a private CountFilter sealed class; the two suspend methods
 * keep their distinct cache/API semantics.
 */
@Singleton
class DocumentCountRepository @Inject constructor(
    private val api: PaperlessApi,
    private val cachedDocumentDao: CachedDocumentDao,
    private val networkMonitor: NetworkMonitor,
) {

    private sealed class CountFilter {
        data class WithFilter(
            val searchQuery: String?,
            val filter: DocumentFilter,
        ) : CountFilter()
        data object Untagged : CountFilter()
    }

    private fun observeCountInternal(countFilter: CountFilter): Flow<Int> = when (countFilter) {
        is CountFilter.WithFilter -> {
            val query = DocumentFilterQueryBuilder.buildCountQuery(
                searchQuery = countFilter.searchQuery,
                filter = countFilter.filter,
            )
            cachedDocumentDao.getCountWithFilter(query)
        }
        is CountFilter.Untagged -> cachedDocumentDao.observeUntaggedCount()
    }

    fun observeCountWithFilter(
        searchQuery: String? = null,
        filter: DocumentFilter = DocumentFilter.empty(),
    ): Flow<Int> = observeCountInternal(CountFilter.WithFilter(searchQuery, filter))

    fun observeUntaggedDocumentsCount(): Flow<Int> =
        observeCountInternal(CountFilter.Untagged)

    suspend fun getDocumentCount(forceRefresh: Boolean = false): Result<Int> {
        return try {
            if (!forceRefresh) {
                val count = cachedDocumentDao.getCount()
                if (count > 0 || !networkMonitor.checkOnlineStatus()) {
                    return Result.success(count)
                }
            }
            if (networkMonitor.checkOnlineStatus()) {
                safeApiCall {
                    api.getDocuments(page = 1, pageSize = 1).count
                }
            } else {
                Result.success(cachedDocumentDao.getCount())
            }
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    suspend fun getUntaggedCount(): Result<Int> = safeApiCall {
        api.getDocuments(
            page = 1,
            pageSize = 1,
            tagsIsNull = true,
        ).count
    }
}
```

- [ ] **Step 1.2.2: Verify the new file compiles**

Run:
```bash
export JAVA_HOME="$(/c/Program\ Files/Eclipse\ Adoptium/jdk-21.*/bin/java -XshowSettings:properties -version 2>&1 | grep 'java.home' | awk -F'= ' '{print $2}')"
./gradlew :app:compileReleaseKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL. If `unresolved reference` appears for any import, double-check the imports against existing repos like `TagRepository.kt`.

### Task 1.3: Update DocumentRepository to delegate

**Files:**
- Modify: `app/src/main/java/com/paperless/scanner/data/repository/DocumentRepository.kt`

- [ ] **Step 1.3.1: Add the new repository to the constructor**

Locate the constructor (around line 50). Add `private val count: DocumentCountRepository,` as the LAST parameter (after `private val serializer: DocumentSerializer`).

Resulting tail of the constructor:
```kotlin
    private val imageProcessor: ImageProcessorService,
    private val pdfGenerator: PdfGeneratorService,
    private val serializer: DocumentSerializer,
    private val count: DocumentCountRepository,
) {
```

- [ ] **Step 1.3.2: Replace `observeCountWithFilter` body with delegation**

Locate `observeCountWithFilter` (around L262). Replace the entire function (signature + body) with:

```kotlin
    fun observeCountWithFilter(
        searchQuery: String? = null,
        filter: com.paperless.scanner.domain.model.DocumentFilter = com.paperless.scanner.domain.model.DocumentFilter.empty()
    ): Flow<Int> = count.observeCountWithFilter(searchQuery, filter)
```

(The KDoc above the original function may stay — it documents the behavior and is still accurate.)

- [ ] **Step 1.3.3: Replace `observeUntaggedDocumentsCount` body with delegation**

Locate `observeUntaggedDocumentsCount` (around L279). Replace the function body with:

```kotlin
    fun observeUntaggedDocumentsCount(): Flow<Int> = count.observeUntaggedDocumentsCount()
```

- [ ] **Step 1.3.4: Replace `getDocumentCount` body with delegation**

Locate `getDocumentCount` (around L468). Replace the function body with:

```kotlin
    suspend fun getDocumentCount(forceRefresh: Boolean = false): Result<Int> =
        count.getDocumentCount(forceRefresh)
```

- [ ] **Step 1.3.5: Replace `getUntaggedCount` body with delegation**

Locate `getUntaggedCount` (around L515). Replace the function body with:

```kotlin
    suspend fun getUntaggedCount(): Result<Int> = count.getUntaggedCount()
```

- [ ] **Step 1.3.6: Verify DocumentRepository compiles**

Run:
```bash
export JAVA_HOME="$(/c/Program\ Files/Eclipse\ Adoptium/jdk-21.*/bin/java -XshowSettings:properties -version 2>&1 | grep 'java.home' | awk -F'= ' '{print $2}')"
./gradlew :app:compileReleaseKotlin --no-daemon
```

Expected: BUILD FAILED with `parameter not provided` or similar Hilt / wiring error in `AppModule.provideDocumentRepository`. (This is expected — fixed in Task 1.4.) If you get `unresolved reference: count`, double-check the constructor change in 1.3.1.

### Task 1.4: Update AppModule

**Files:**
- Modify: `app/src/main/java/com/paperless/scanner/di/AppModule.kt`

- [ ] **Step 1.4.1: Add `count` parameter to provideDocumentRepository**

Locate `provideDocumentRepository` (around L333-344). Add `count: DocumentCountRepository,` as the LAST parameter and pass it to the `DocumentRepository(...)` call.

Example (only the changed lines shown — preserve all others verbatim):

```kotlin
    @Provides
    @Singleton
    fun provideDocumentRepository(
        // ... all existing args ...
        imageProcessor: ImageProcessorService,
        pdfGenerator: PdfGeneratorService,
        serializer: DocumentSerializer,
        count: DocumentCountRepository,                    // NEW
    ): DocumentRepository = DocumentRepository(
        // ... all existing args passed through ...
        imageProcessor,
        pdfGenerator,
        serializer,
        count,                                              // NEW
    )
```

If the file uses an import for `DocumentCountRepository`, add:
```kotlin
import com.paperless.scanner.data.repository.DocumentCountRepository
```

- [ ] **Step 1.4.2: Verify compile passes**

Run:
```bash
export JAVA_HOME="$(/c/Program\ Files/Eclipse\ Adoptium/jdk-21.*/bin/java -XshowSettings:properties -version 2>&1 | grep 'java.home' | awk -F'= ' '{print $2}')"
./gradlew :app:compileReleaseKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL.

### Task 1.5: Existing test suite stays green

- [ ] **Step 1.5.1: Run DocumentRepositoryTest**

Run:
```bash
export JAVA_HOME="$(/c/Program\ Files/Eclipse\ Adoptium/jdk-21.*/bin/java -XshowSettings:properties -version 2>&1 | grep 'java.home' | awk -F'= ' '{print $2}')"
./gradlew :app:testReleaseUnitTest --tests "*DocumentRepositoryTest*" --no-daemon
```

Expected: BUILD SUCCESSFUL, all tests pass. If any test fails, the delegation in Task 1.3 has a typo or signature drift — diff the changed methods against the original bodies.

### Task 1.6: Write DocumentCountRepository tests

**Files:**
- Create: `app/src/test/java/com/paperless/scanner/data/repository/DocumentCountRepositoryTest.kt`

- [ ] **Step 1.6.1: Write the failing test file**

Create `app/src/test/java/com/paperless/scanner/data/repository/DocumentCountRepositoryTest.kt` with this exact content:

```kotlin
package com.paperless.scanner.data.repository

import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.database.dao.CachedDocumentDao
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.data.api.models.DocumentsResponse
import com.paperless.scanner.domain.model.DocumentFilter
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DocumentCountRepositoryTest {

    private lateinit var api: PaperlessApi
    private lateinit var dao: CachedDocumentDao
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var repo: DocumentCountRepository

    @Before
    fun setup() {
        api = mockk(relaxed = true)
        dao = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)
        repo = DocumentCountRepository(api, dao, networkMonitor)
    }

    // ---- observeCountWithFilter (CountFilter.WithFilter branch) ----

    @Test
    fun `observeCountWithFilter delegates to dao getCountWithFilter`() = runTest {
        every { dao.getCountWithFilter(any()) } returns flowOf(7)
        val result = repo.observeCountWithFilter(searchQuery = "tax", filter = DocumentFilter.empty()).first()
        assertEquals(7, result)
    }

    // ---- observeUntaggedDocumentsCount (CountFilter.Untagged branch) ----

    @Test
    fun `observeUntaggedDocumentsCount delegates to dao observeUntaggedCount`() = runTest {
        every { dao.observeUntaggedCount() } returns flowOf(3)
        val result = repo.observeUntaggedDocumentsCount().first()
        assertEquals(3, result)
    }

    // ---- getDocumentCount (suspend) ----

    @Test
    fun `getDocumentCount with forceRefresh true and online fetches from API`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        coEvery { api.getDocuments(page = 1, pageSize = 1) } returns
            DocumentsResponse(count = 42, next = null, previous = null, results = emptyList())
        val result = repo.getDocumentCount(forceRefresh = true)
        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `getDocumentCount without forceRefresh and cache positive returns cached value`() = runTest {
        coEvery { dao.getCount() } returns 11
        val result = repo.getDocumentCount(forceRefresh = false)
        assertEquals(11, result.getOrNull())
    }

    @Test
    fun `getDocumentCount offline with empty cache returns success of 0`() = runTest {
        coEvery { dao.getCount() } returns 0
        coEvery { networkMonitor.checkOnlineStatus() } returns false
        val result = repo.getDocumentCount(forceRefresh = false)
        assertEquals(0, result.getOrNull())
    }

    @Test
    fun `getDocumentCount with forceRefresh true and offline falls back to cache`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns false
        coEvery { dao.getCount() } returns 5
        val result = repo.getDocumentCount(forceRefresh = true)
        assertEquals(5, result.getOrNull())
    }

    // ---- getUntaggedCount (suspend, API-only) ----

    @Test
    fun `getUntaggedCount returns count from API`() = runTest {
        coEvery { api.getDocuments(page = 1, pageSize = 1, tagsIsNull = true) } returns
            DocumentsResponse(count = 9, next = null, previous = null, results = emptyList())
        val result = repo.getUntaggedCount()
        assertEquals(9, result.getOrNull())
    }

    @Test
    fun `getUntaggedCount returns failure when API throws`() = runTest {
        coEvery { api.getDocuments(page = 1, pageSize = 1, tagsIsNull = true) } throws RuntimeException("boom")
        val result = repo.getUntaggedCount()
        assertTrue(result.isFailure)
    }
}
```

- [ ] **Step 1.6.2: Run the new tests — expect them to pass on first run**

Run:
```bash
export JAVA_HOME="$(/c/Program\ Files/Eclipse\ Adoptium/jdk-21.*/bin/java -XshowSettings:properties -version 2>&1 | grep 'java.home' | awk -F'= ' '{print $2}')"
./gradlew :app:testReleaseUnitTest --tests "*DocumentCountRepositoryTest*" --no-daemon
```

Expected: BUILD SUCCESSFUL, 8 tests pass. (This is a refactor — code already works, tests just verify the moved logic.) If `safeApiCall` interaction is unmockable, look at how `TagRepositoryTest.kt` mocks `api` calls and adopt the same pattern. If a test fails because the code branch was misread during the move, fix the production code first, not the test.

### Task 1.7: Local CI validation

- [ ] **Step 1.7.1: Run validate-ci.sh**

Run:
```bash
cd "E:/Git/paperless client" && ./scripts/validate-ci.sh
```

Expected: green checks for translation/duplicate/empty checks, `testReleaseUnitTest`, `assembleRelease`, `lintRelease`. If lint flags new issues introduced by the new file, fix them; do not add to baseline.

### Task 1.8: Commit

- [ ] **Step 1.8.1: Stage and commit**

Run:
```bash
cd "E:/Git/paperless client" && \
  git add app/src/main/java/com/paperless/scanner/data/repository/DocumentCountRepository.kt \
          app/src/test/java/com/paperless/scanner/data/repository/DocumentCountRepositoryTest.kt \
          app/src/main/java/com/paperless/scanner/data/repository/DocumentRepository.kt \
          app/src/main/java/com/paperless/scanner/di/AppModule.kt && \
  git commit -m "$(cat <<'EOF'
refactor: extract DocumentCountRepository (Phase 2.2 of #51)

Extracts the four document-count methods from DocumentRepository into a
dedicated DocumentCountRepository under data/repository/. The two reactive
Flow counts are unified internally via a private CountFilter sealed class;
the two suspend counts keep their distinct cache/API semantics.

DocumentRepository becomes a thin façade for these four methods. No public
API changes, no caller migration. Existing DocumentRepositoryTest stays
green; new DocumentCountRepositoryTest adds 8 dedicated test cases.

Closes #165

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

Expected: pre-commit hook passes, commit created.

### Task 1.9: Manual on-device smoke

- [ ] **Step 1.9.1: Build and install debug APK on device**

Run:
```bash
export JAVA_HOME="$(/c/Program\ Files/Eclipse\ Adoptium/jdk-21.*/bin/java -XshowSettings:properties -version 2>&1 | grep 'java.home' | awk -F'= ' '{print $2}')"
./gradlew :app:installDebug --no-daemon
```

Expected: APK installs.

- [ ] **Step 1.9.2: Smoke test count flows on the device**

Manually verify on-device:
1. Open the **Documents** screen — total count is displayed in the header. Apply a filter (e.g., correspondent) — count updates correctly.
2. Open the **Smart-Tagging** entry point — untagged count is displayed and matches what the previous build showed.
3. (Optional) Force-refresh by pulling-to-refresh — count reflects server state.

If any count is wrong or missing, STOP and diff the moved methods against the original bodies before the move.

### Task 1.10: Push and open PR

- [ ] **Step 1.10.1: Push branch (auto-rebase via pre-push hook)**

Run:
```bash
cd "E:/Git/paperless client" && git push -u origin refactor/51-extract-document-count
```

Expected: pre-push hook runs full validate-ci.sh, then pushes. If hook fails, fix and retry — DO NOT use `--no-verify`.

- [ ] **Step 1.10.2: Open the PR**

Run:
```bash
cd "E:/Git/paperless client" && gh pr create \
  --base main \
  --head refactor/51-extract-document-count \
  --title "refactor: extract DocumentCountRepository (Phase 2.2 of #51)" \
  --body "$(cat <<'EOF'
## Summary
- Extracts the 4 count methods from `DocumentRepository` into a new `DocumentCountRepository`.
- The 2 Flow methods are unified internally via a private `CountFilter` sealed class.
- The 2 suspend methods keep distinct cache/API semantics (deliberate, see spec).
- `DocumentRepository` becomes a façade for these methods. Zero caller changes.

## Phase
Phase 2.2 of #51 — DocumentRepository God-class refactor. See [spec](docs/superpowers/specs/2026-05-06-document-repository-refactor-phase2-design.md).

## Tests
- ✅ Existing `DocumentRepositoryTest` stays green (façade delegates 1:1).
- ✅ New `DocumentCountRepositoryTest` adds 8 cases covering both `CountFilter` branches and all 4 paths of the suspend variants.

## Test plan
- [x] `./scripts/validate-ci.sh` (RELEASE variants) green locally
- [x] On-device smoke: documents count + untagged count display correctly

Closes #165
EOF
)"
```

Expected: PR opened, gh prints the URL.

- [ ] **Step 1.10.3: Address CodeRabbit review**

Wait for CodeRabbit. Action small real bugs (≤5 LOC) inside this PR. Skip architectural suggestions (Dispatchers.IO, interface introduction, etc.) with a short rationale comment referencing Section 9 (Out-of-Scope) of the spec.

- [ ] **Step 1.10.4: Squash-merge after CI green and review approved**

Use the GitHub UI or:
```bash
gh pr merge --squash --delete-branch
```

Expected: PR merged, branch deleted, `main` advances. Update parent issue #51 checklist to mark Phase 2.2 done.

---

## PR 2 — Extract DocumentMetadataRepository (#166)

**Branch:** `refactor/51-extract-document-metadata`
**LOC moved:** ~250 (4 public methods + 2 private helpers)
**Effort:** M
**Depends on:** PR1 merged.

### Task 2.1: Create branch from updated main

- [ ] **Step 2.1.1: Pull latest main and create branch**

Run:
```bash
cd "E:/Git/paperless client" && git checkout main && git pull --rebase --autostash && git checkout -b refactor/51-extract-document-metadata
```

Expected: on `main` with PR1 merge commit, then on new branch.

### Task 2.2: Create DocumentMetadataRepository

**Files:**
- Create: `app/src/main/java/com/paperless/scanner/data/repository/DocumentMetadataRepository.kt`

- [ ] **Step 2.2.1: Write the new repository file**

Create `app/src/main/java/com/paperless/scanner/data/repository/DocumentMetadataRepository.kt` with this exact content:

```kotlin
package com.paperless.scanner.data.repository

import android.content.Context
import com.paperless.scanner.R
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.api.models.PermissionSet
import com.paperless.scanner.data.api.models.SetPermissionsRequest
import com.paperless.scanner.data.api.models.UpdateDocumentRequest
import com.paperless.scanner.data.api.models.UpdateDocumentWithPermissionsRequest
import com.paperless.scanner.data.database.dao.CachedDocumentDao
import com.paperless.scanner.data.database.dao.CachedTagDao
import com.paperless.scanner.data.database.dao.PendingChangeDao
import com.paperless.scanner.data.database.entities.PendingChange
import com.paperless.scanner.data.database.mappers.toCachedEntity
import com.paperless.scanner.data.database.mappers.toDomain as toCachedDomain
import com.paperless.scanner.data.health.ServerHealthMonitor
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.data.service.DocumentSerializer
import com.paperless.scanner.domain.mapper.toDomain
import com.paperless.scanner.domain.model.Document
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Phase 2.3 of #51 — extracted from DocumentRepository.
 *
 * Owns single-document read + write operations: observeDocument, getDocument,
 * updateDocument, updateDocumentPermissions.
 *
 * PHASE-3.3 DEBT: updateDocument's offline-queue branch (PendingChange) belongs
 * to the future DocumentSyncRepository (#169). It is moved here together with
 * updateDocument for now; pendingChangeDao + serverHealthMonitor become unused
 * the moment Phase 3.3 introduces DocumentSyncRepository.executeOrQueue { ... }.
 */
@Singleton
class DocumentMetadataRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: PaperlessApi,
    private val cachedDocumentDao: CachedDocumentDao,
    private val cachedTagDao: CachedTagDao,
    private val pendingChangeDao: PendingChangeDao,           // PHASE-3.3 debt
    private val networkMonitor: NetworkMonitor,
    private val serverHealthMonitor: ServerHealthMonitor,     // PHASE-3.3 debt
    private val serializer: DocumentSerializer,
) {

    fun observeDocument(id: Int): Flow<Document?> {
        return cachedDocumentDao.observeDocument(id).map { it?.toCachedDomain() }
    }

    suspend fun getDocument(id: Int, forceRefresh: Boolean = false): Result<Document> {
        return try {
            if (forceRefresh || networkMonitor.checkOnlineStatus()) {
                return try {
                    val doc = api.getDocument(id)
                    cachedDocumentDao.insert(doc.toCachedEntity())
                    Result.success(doc.toDomain())
                } catch (e: retrofit2.HttpException) {
                    val cached = cachedDocumentDao.getDocument(id)
                    if (cached != null) Result.success(cached.toCachedDomain())
                    else Result.failure(PaperlessException.fromHttpCode(e.code(), e.message()))
                } catch (e: Exception) {
                    val cached = cachedDocumentDao.getDocument(id)
                    if (cached != null) Result.success(cached.toCachedDomain())
                    else Result.failure(PaperlessException.from(e))
                }
            }
            val cached = cachedDocumentDao.getDocument(id)
            if (cached != null) Result.success(cached.toCachedDomain())
            else Result.failure(
                PaperlessException.ClientError(404, context.getString(R.string.error_document_not_cached))
            )
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    suspend fun updateDocument(
        documentId: Int,
        title: String? = null,
        tags: List<Int>? = null,
        correspondent: Int? = null,
        documentType: Int? = null,
        archiveSerialNumber: Int? = null,
        created: String? = null,
    ): Result<Document> {
        return try {
            if (serverHealthMonitor.isServerReachable.value) {
                val oldTagIds = if (tags != null) getOldTagIds(documentId) else null
                val request = UpdateDocumentRequest(
                    title = title,
                    tags = tags,
                    correspondent = correspondent,
                    documentType = documentType,
                    archiveSerialNumber = archiveSerialNumber,
                    created = created,
                )
                val updatedDocument = api.updateDocument(documentId, request)
                cachedDocumentDao.insert(updatedDocument.toCachedEntity())
                if (tags != null && oldTagIds != null) {
                    updateTagDocumentCounts(oldTagIds, tags)
                }
                Result.success(updatedDocument.toDomain())
            } else {
                // PHASE-3.3: extract via DocumentSyncRepository.executeOrQueue { ... }
                val changeData = buildString {
                    append("{")
                    title?.let { append("\"title\":\"$it\",") }
                    tags?.let { append("\"tags\":$it,") }
                    correspondent?.let { append("\"correspondent\":$it,") }
                    documentType?.let { append("\"documentType\":$it,") }
                    archiveSerialNumber?.let { append("\"archiveSerialNumber\":$it,") }
                    created?.let { append("\"created\":\"$it\",") }
                    if (endsWith(",")) deleteCharAt(length - 1)
                    append("}")
                }
                val pendingChange = PendingChange(
                    entityType = "document",
                    entityId = documentId,
                    changeType = "update",
                    changeData = changeData,
                )
                pendingChangeDao.insert(pendingChange)
                val cached = cachedDocumentDao.getDocument(documentId)
                if (cached != null) Result.success(cached.toCachedDomain())
                else Result.failure(
                    PaperlessException.ClientError(404, context.getString(R.string.error_document_not_cached))
                )
            }
        } catch (e: retrofit2.HttpException) {
            Result.failure(PaperlessException.fromHttpCode(e.code(), e.message()))
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    suspend fun updateDocumentPermissions(
        documentId: Int,
        owner: Int?,
        viewUsers: List<Int>,
        viewGroups: List<Int>,
        changeUsers: List<Int>,
        changeGroups: List<Int>,
    ): Result<Document> {
        return try {
            if (networkMonitor.checkOnlineStatus()) {
                val request = UpdateDocumentWithPermissionsRequest(
                    owner = owner,
                    setPermissions = SetPermissionsRequest(
                        view = PermissionSet(users = viewUsers, groups = viewGroups),
                        change = PermissionSet(users = changeUsers, groups = changeGroups),
                    ),
                )
                val updatedDocument = api.updateDocumentPermissions(documentId, request)
                cachedDocumentDao.insert(updatedDocument.toCachedEntity())
                Result.success(updatedDocument.toDomain())
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

    private suspend fun getOldTagIds(documentId: Int): List<Int> {
        return try {
            val cached = cachedDocumentDao.getDocument(documentId)
            serializer.deserializeCachedTagIds(cached?.tags)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun updateTagDocumentCounts(oldTagIds: List<Int>, newTagIds: List<Int>) {
        try {
            val oldSet = oldTagIds.toSet()
            val newSet = newTagIds.toSet()
            (oldSet - newSet).forEach { tagId -> cachedTagDao.updateDocumentCount(tagId, -1) }
            (newSet - oldSet).forEach { tagId -> cachedTagDao.updateDocumentCount(tagId, 1) }
        } catch (_: Exception) {
            // best effort — server is in sync, cache will reconcile on next full sync
        }
    }
}
```

- [ ] **Step 2.2.2: Verify the new file compiles**

Run:
```bash
export JAVA_HOME="$(/c/Program\ Files/Eclipse\ Adoptium/jdk-21.*/bin/java -XshowSettings:properties -version 2>&1 | grep 'java.home' | awk -F'= ' '{print $2}')"
./gradlew :app:compileReleaseKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL.

### Task 2.3: Update DocumentRepository

**Files:**
- Modify: `app/src/main/java/com/paperless/scanner/data/repository/DocumentRepository.kt`

- [ ] **Step 2.3.1: Add `metadata` to constructor**

Append `private val metadata: DocumentMetadataRepository,` AFTER the `count` parameter from PR1.

- [ ] **Step 2.3.2: Replace `getDocument` body**

```kotlin
    suspend fun getDocument(id: Int, forceRefresh: Boolean = false): Result<Document> =
        metadata.getDocument(id, forceRefresh)
```

- [ ] **Step 2.3.3: Replace `observeDocument` body**

```kotlin
    fun observeDocument(id: Int): Flow<Document?> = metadata.observeDocument(id)
```

- [ ] **Step 2.3.4: Replace `updateDocument` body**

```kotlin
    suspend fun updateDocument(
        documentId: Int,
        title: String? = null,
        tags: List<Int>? = null,
        correspondent: Int? = null,
        documentType: Int? = null,
        archiveSerialNumber: Int? = null,
        created: String? = null
    ): Result<Document> = metadata.updateDocument(
        documentId, title, tags, correspondent, documentType, archiveSerialNumber, created
    )
```

- [ ] **Step 2.3.5: Replace `updateDocumentPermissions` body**

```kotlin
    suspend fun updateDocumentPermissions(
        documentId: Int,
        owner: Int?,
        viewUsers: List<Int>,
        viewGroups: List<Int>,
        changeUsers: List<Int>,
        changeGroups: List<Int>
    ): Result<Document> = metadata.updateDocumentPermissions(
        documentId, owner, viewUsers, viewGroups, changeUsers, changeGroups
    )
```

- [ ] **Step 2.3.6: Remove the now-orphaned private helpers from DocumentRepository**

Delete these two functions from `DocumentRepository.kt`:
- `private suspend fun getOldTagIds(documentId: Int): List<Int>` (was around L735)
- `private suspend fun updateTagDocumentCounts(oldTagIds: List<Int>, newTagIds: List<Int>)` (was around L745)

They now live inside `DocumentMetadataRepository`.

### Task 2.4: Update AppModule

- [ ] **Step 2.4.1: Add `metadata` parameter to provideDocumentRepository**

In `AppModule.kt:provideDocumentRepository`, append `metadata: DocumentMetadataRepository,` AFTER the `count` parameter, and pass it to the `DocumentRepository(...)` call. Add the import:
```kotlin
import com.paperless.scanner.data.repository.DocumentMetadataRepository
```

- [ ] **Step 2.4.2: Verify compile passes**

Run:
```bash
export JAVA_HOME="$(/c/Program\ Files/Eclipse\ Adoptium/jdk-21.*/bin/java -XshowSettings:properties -version 2>&1 | grep 'java.home' | awk -F'= ' '{print $2}')"
./gradlew :app:compileReleaseKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL.

### Task 2.5: Existing test suite stays green

- [ ] **Step 2.5.1: Run DocumentRepositoryTest**

Run:
```bash
export JAVA_HOME="$(/c/Program\ Files/Eclipse\ Adoptium/jdk-21.*/bin/java -XshowSettings:properties -version 2>&1 | grep 'java.home' | awk -F'= ' '{print $2}')"
./gradlew :app:testReleaseUnitTest --tests "*DocumentRepositoryTest*" --no-daemon
```

Expected: BUILD SUCCESSFUL.

### Task 2.6: Write DocumentMetadataRepository tests

**Files:**
- Create: `app/src/test/java/com/paperless/scanner/data/repository/DocumentMetadataRepositoryTest.kt`

- [ ] **Step 2.6.1: Write the test file**

Create `app/src/test/java/com/paperless/scanner/data/repository/DocumentMetadataRepositoryTest.kt`. Required test cases (12 total — copy the structure from `DocumentCountRepositoryTest.kt` (Task 1.6.1) and adapt):

```kotlin
package com.paperless.scanner.data.repository

import androidx.test.core.app.ApplicationProvider
import com.google.gson.Gson
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.models.UpdateDocumentRequest
import com.paperless.scanner.data.api.models.UpdateDocumentWithPermissionsRequest
import com.paperless.scanner.data.database.dao.CachedDocumentDao
import com.paperless.scanner.data.database.dao.CachedTagDao
import com.paperless.scanner.data.database.dao.PendingChangeDao
import com.paperless.scanner.data.database.entities.CachedDocument
import com.paperless.scanner.data.database.entities.PendingChange
import com.paperless.scanner.data.health.ServerHealthMonitor
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.data.service.DocumentSerializer
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DocumentMetadataRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var api: PaperlessApi
    private lateinit var dao: CachedDocumentDao
    private lateinit var tagDao: CachedTagDao
    private lateinit var pendingDao: PendingChangeDao
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var serverHealth: ServerHealthMonitor
    private lateinit var serializer: DocumentSerializer
    private lateinit var repo: DocumentMetadataRepository

    @Before
    fun setup() {
        api = mockk(relaxed = true)
        dao = mockk(relaxed = true)
        tagDao = mockk(relaxed = true)
        pendingDao = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)
        serverHealth = mockk(relaxed = true)
        serializer = DocumentSerializer(Gson())
        every { serverHealth.isServerReachable } returns MutableStateFlow(true)
        repo = DocumentMetadataRepository(
            context, api, dao, tagDao, pendingDao,
            networkMonitor, serverHealth, serializer
        )
    }

    // 1. observeDocument — flow maps null to null and entity to domain
    @Test
    fun `observeDocument maps null cached entity to null domain`() = runTest {
        every { dao.observeDocument(1) } returns flowOf(null)
        assertEquals(null, repo.observeDocument(1).first())
    }

    // 2. getDocument online happy path inserts cache and returns domain
    @Test
    fun `getDocument online fetches from API and inserts cache`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        // mock api.getDocument(...) → DocumentDto with id=1, title="t" — refer to fixtures in
        // existing DocumentRepositoryTest for DTO construction patterns
        // assert: result.isSuccess and dao.insert called once with toCachedEntity-converted DTO
    }

    // 3. getDocument forceRefresh true triggers network even when offline cache exists
    // 4. getDocument online with HttpException falls back to cache
    // 5. getDocument offline with cache hit returns cached domain
    // 6. getDocument offline with no cache returns ClientError 404
    // 7. updateDocument online fetches API, inserts cache, calls updateTagDocumentCounts when tags differ
    // 8. updateDocument offline (serverHealth false) writes PendingChange with non-null fields only
    // 9. updateDocument with tags=null does NOT compute oldTagIds (no tagDao calls)
    // 10. updateDocumentPermissions online inserts cache and returns domain
    // 11. updateDocumentPermissions offline returns NetworkError
    // 12. getOldTagIds via updateDocument: cached.tags = "[1,2,3]" → tagDao decrements removed, increments added

    // Implement each TODO above with coVerify { ... } / assertEquals patterns.
    // Use `slot<UpdateDocumentRequest>()` to capture and inspect API request bodies.
    // Use `slot<PendingChange>()` for the offline branch JSON shape assertion.
    // Reference DocumentRepositoryTest for fixture builders (sample DocumentDto, CachedDocument).
}
```

Engineer note: the test stubs above call out the 12 required cases. Implement each with concrete fixtures. Cross-reference `DocumentRepositoryTest.kt` for DTO/entity builder patterns already in use.

- [ ] **Step 2.6.2: Run the new tests**

Run:
```bash
export JAVA_HOME="$(/c/Program\ Files/Eclipse\ Adoptium/jdk-21.*/bin/java -XshowSettings:properties -version 2>&1 | grep 'java.home' | awk -F'= ' '{print $2}')"
./gradlew :app:testReleaseUnitTest --tests "*DocumentMetadataRepositoryTest*" --no-daemon
```

Expected: 12 tests pass.

### Task 2.7: Local CI

- [ ] **Step 2.7.1: Run validate-ci.sh**

Run:
```bash
cd "E:/Git/paperless client" && ./scripts/validate-ci.sh
```

Expected: green.

### Task 2.8: Commit

- [ ] **Step 2.8.1: Stage and commit**

Run:
```bash
cd "E:/Git/paperless client" && \
  git add app/src/main/java/com/paperless/scanner/data/repository/DocumentMetadataRepository.kt \
          app/src/test/java/com/paperless/scanner/data/repository/DocumentMetadataRepositoryTest.kt \
          app/src/main/java/com/paperless/scanner/data/repository/DocumentRepository.kt \
          app/src/main/java/com/paperless/scanner/di/AppModule.kt && \
  git commit -m "$(cat <<'EOF'
refactor: extract DocumentMetadataRepository (Phase 2.3 of #51)

Extracts observeDocument, getDocument, updateDocument, and
updateDocumentPermissions from DocumentRepository into a dedicated
DocumentMetadataRepository under data/repository/.

The offline-queue branch in updateDocument is moved together with the
method as Phase-3.3 debt; inline // PHASE-3.3: comments mark the
extraction points for the future DocumentSyncRepository.

Closes #166

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 2.9: Manual on-device smoke

- [ ] **Step 2.9.1: Build, install, smoke**

Run install command from Step 1.9.1, then on-device:
1. Open a document detail → change a tag → save → verify it persists after refresh.
2. Toggle Airplane mode → change document title → reconnect → verify the change syncs (PendingChange path).
3. Open document permissions → change owner / view-users → save → reload → verify.

### Task 2.10: Push and PR

- [ ] **Step 2.10.1: Push**

Run:
```bash
cd "E:/Git/paperless client" && git push -u origin refactor/51-extract-document-metadata
```

- [ ] **Step 2.10.2: Open the PR**

Run:
```bash
cd "E:/Git/paperless client" && gh pr create \
  --base main \
  --head refactor/51-extract-document-metadata \
  --title "refactor: extract DocumentMetadataRepository (Phase 2.3 of #51)" \
  --body "$(cat <<'EOF'
## Summary
- Extracts single-document read/write methods (`observeDocument`, `getDocument`, `updateDocument`, `updateDocumentPermissions`) into a dedicated `DocumentMetadataRepository`.
- The offline-queue branch in `updateDocument` moves with the method; inline `// PHASE-3.3:` markers flag the extraction points for the future `DocumentSyncRepository` (#169).
- `DocumentRepository` becomes a façade for these methods. Zero caller changes.

## Phase
Phase 2.3 of #51 — DocumentRepository God-class refactor. See [spec](docs/superpowers/specs/2026-05-06-document-repository-refactor-phase2-design.md).

## Tests
- ✅ Existing `DocumentRepositoryTest` stays green.
- ✅ New `DocumentMetadataRepositoryTest` adds 12 cases covering online/offline branches, tag-count delta, permissions, and edge cases.

## Test plan
- [x] `./scripts/validate-ci.sh` (RELEASE variants) green
- [x] On-device: tag/title edit (online), offline edit + reconnect sync, permission update

Closes #166
EOF
)"
```

- [ ] **Step 2.10.3: Address CodeRabbit, squash-merge after green**

Same pattern as 1.10.3–1.10.4.

---

## PR 3 — Extract DocumentListRepository (#164)

**Branch:** `refactor/51-extract-document-list`
**LOC moved:** ~200 (6 methods)
**Effort:** M
**Depends on:** PR2 merged.

### Task 3.1: Create branch from updated main

- [ ] **Step 3.1.1: Pull latest and branch**

Run:
```bash
cd "E:/Git/paperless client" && git checkout main && git pull --rebase --autostash && git checkout -b refactor/51-extract-document-list
```

### Task 3.2: Create DocumentListRepository

**Files:**
- Create: `app/src/main/java/com/paperless/scanner/data/repository/DocumentListRepository.kt`

- [ ] **Step 3.2.1: Write the new repository file**

Create `app/src/main/java/com/paperless/scanner/data/repository/DocumentListRepository.kt` with this exact content:

```kotlin
package com.paperless.scanner.data.repository

import android.content.Context
import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.paperless.scanner.R
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.api.safeApiCall
import com.paperless.scanner.data.database.DocumentFilterQueryBuilder
import com.paperless.scanner.data.database.dao.CachedDocumentDao
import com.paperless.scanner.data.database.mappers.toCachedEntity
import com.paperless.scanner.data.database.mappers.toDomain as toCachedDomain
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.domain.mapper.toDomain
import com.paperless.scanner.domain.model.Document
import com.paperless.scanner.domain.model.DocumentFilter
import com.paperless.scanner.domain.model.DocumentsResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Phase 2.1 of #51 — extracted from DocumentRepository.
 *
 * Owns list / paging / search operations: observeDocuments, getDocumentsPaged,
 * getDocuments, searchDocuments, getRecentDocuments, getUntaggedDocuments.
 */
@Singleton
class DocumentListRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: PaperlessApi,
    private val cachedDocumentDao: CachedDocumentDao,
    private val networkMonitor: NetworkMonitor,
) {

    fun observeDocuments(page: Int = 1, pageSize: Int = 25): Flow<List<Document>> {
        return cachedDocumentDao.observeDocuments(
            limit = pageSize,
            offset = (page - 1) * pageSize,
        ).map { cachedList -> cachedList.map { it.toCachedDomain() } }
    }

    suspend fun getUntaggedDocuments(): Result<List<Document>> {
        return try {
            val cachedDocs = cachedDocumentDao.getUntaggedDocuments()
            Result.success(cachedDocs.map { it.toCachedDomain() })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun getDocumentsPaged(
        searchQuery: String? = null,
        filter: DocumentFilter = DocumentFilter.empty(),
    ): Flow<PagingData<Document>> {
        return Pager(
            config = PagingConfig(
                pageSize = 100,
                maxSize = 500,
                enablePlaceholders = false,
            ),
            pagingSourceFactory = {
                val query = DocumentFilterQueryBuilder.buildPagingQuery(
                    searchQuery = searchQuery,
                    filter = filter,
                )
                cachedDocumentDao.getDocumentsPagingSource(query)
            },
        ).flow.map { pagingData -> pagingData.map { it.toCachedDomain() } }
    }

    suspend fun getDocuments(
        page: Int = 1,
        pageSize: Int = 25,
        query: String? = null,
        tagIds: List<Int>? = null,
        correspondentId: Int? = null,
        documentTypeId: Int? = null,
        ordering: String = "-created",
        forceRefresh: Boolean = false,
    ): Result<DocumentsResponse> {
        return try {
            if (!forceRefresh || !networkMonitor.checkOnlineStatus()) {
                val cachedDocs = cachedDocumentDao.getDocuments(
                    limit = pageSize,
                    offset = (page - 1) * pageSize,
                )
                if (cachedDocs.isNotEmpty()) {
                    val totalCount = cachedDocumentDao.getCount()
                    val domainDocs = cachedDocs.map { it.toCachedDomain() }
                    return Result.success(
                        DocumentsResponse(
                            count = totalCount,
                            next = if ((page * pageSize) < totalCount) "next" else null,
                            previous = if (page > 1) "prev" else null,
                            results = domainDocs,
                        )
                    )
                }
            }
            if (networkMonitor.checkOnlineStatus()) {
                val tagIdsString = tagIds?.takeIf { it.isNotEmpty() }?.joinToString(",")
                val response = api.getDocuments(
                    page = page,
                    pageSize = pageSize,
                    query = query,
                    tagIds = tagIdsString,
                    correspondentId = correspondentId,
                    documentTypeId = documentTypeId,
                    ordering = ordering,
                )
                val cachedEntities = response.results.map { it.toCachedEntity() }
                cachedDocumentDao.insertAll(cachedEntities)
                Result.success(response.toDomain())
            } else {
                Result.failure(
                    PaperlessException.NetworkError(
                        IOException(context.getString(R.string.error_offline_no_cache))
                    )
                )
            }
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    suspend fun searchDocuments(query: String): Result<List<Document>> {
        return try {
            val cachedResults = cachedDocumentDao.searchDocuments(query)
            Result.success(cachedResults.map { it.toCachedDomain() })
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    suspend fun getRecentDocuments(limit: Int = 5): Result<List<Document>> {
        return try {
            val cached = cachedDocumentDao.getDocuments(limit = limit, offset = 0)
            if (cached.isNotEmpty() || !networkMonitor.checkOnlineStatus()) {
                return Result.success(cached.map { it.toCachedDomain() })
            }
            safeApiCall {
                api.getDocuments(
                    page = 1,
                    pageSize = limit,
                    ordering = "-added",
                ).results.toDomain()
            }
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }
}
```

- [ ] **Step 3.2.2: Verify compile**

Run:
```bash
export JAVA_HOME="$(/c/Program\ Files/Eclipse\ Adoptium/jdk-21.*/bin/java -XshowSettings:properties -version 2>&1 | grep 'java.home' | awk -F'= ' '{print $2}')"
./gradlew :app:compileReleaseKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL.

### Task 3.3: Update DocumentRepository

- [ ] **Step 3.3.1: Add `list` to constructor**

Append `private val list: DocumentListRepository,` AFTER `metadata`.

- [ ] **Step 3.3.2: Replace each list/search/paging body with delegation**

Replace the bodies of each of these 6 methods in `DocumentRepository.kt` with one-line delegations to `list.<methodName>(...)`. Keep the signatures byte-identical:

- `observeDocuments(page, pageSize)` → `list.observeDocuments(page, pageSize)`
- `getUntaggedDocuments()` → `list.getUntaggedDocuments()`
- `getDocumentsPaged(searchQuery, filter)` → `list.getDocumentsPaged(searchQuery, filter)`
- `getDocuments(page, pageSize, query, tagIds, correspondentId, documentTypeId, ordering, forceRefresh)` → `list.getDocuments(page, pageSize, query, tagIds, correspondentId, documentTypeId, ordering, forceRefresh)`
- `searchDocuments(query)` → `list.searchDocuments(query)`
- `getRecentDocuments(limit)` → `list.getRecentDocuments(limit)`

### Task 3.4: Update AppModule

- [ ] **Step 3.4.1: Add `list` parameter**

Append `list: DocumentListRepository,` AFTER `metadata` in `provideDocumentRepository`. Add the import.

- [ ] **Step 3.4.2: Compile**

Run:
```bash
export JAVA_HOME="$(/c/Program\ Files/Eclipse\ Adoptium/jdk-21.*/bin/java -XshowSettings:properties -version 2>&1 | grep 'java.home' | awk -F'= ' '{print $2}')"
./gradlew :app:compileReleaseKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL.

### Task 3.5: Existing tests stay green

- [ ] **Step 3.5.1: Run DocumentRepositoryTest**

Run:
```bash
export JAVA_HOME="$(/c/Program\ Files/Eclipse\ Adoptium/jdk-21.*/bin/java -XshowSettings:properties -version 2>&1 | grep 'java.home' | awk -F'= ' '{print $2}')"
./gradlew :app:testReleaseUnitTest --tests "*DocumentRepositoryTest*" --no-daemon
```

Expected: BUILD SUCCESSFUL.

### Task 3.6: Write DocumentListRepository tests

**Files:**
- Create: `app/src/test/java/com/paperless/scanner/data/repository/DocumentListRepositoryTest.kt`

- [ ] **Step 3.6.1: Write the test file (10 cases)**

Create `app/src/test/java/com/paperless/scanner/data/repository/DocumentListRepositoryTest.kt` using the same Robolectric + mockk pattern as `DocumentCountRepositoryTest`. Required cases:

1. `observeDocuments` — flow maps cached entities to domain and applies pageSize/offset
2. `getDocumentsPaged` — Pager flow emits PagingData with mapped domain documents (use `androidx.paging.testing.asSnapshot`)
3. `getDocuments` cache hit — returns response with totalCount + next/previous derived correctly
4. `getDocuments` forceRefresh + online — calls api.getDocuments and `dao.insertAll`
5. `getDocuments` offline + no cache — returns NetworkError
6. `getDocuments` cache empty + online forceRefresh false → falls back to network (skip cache branch)
7. `searchDocuments` happy path — dao.searchDocuments returns 2 entities → result has 2 domain docs
8. `searchDocuments` exception path — DAO throws → Result.failure(PaperlessException.from(...))
9. `getRecentDocuments` cache non-empty — returns cached, never hits API
10. `getUntaggedDocuments` — dao.getUntaggedDocuments returns 3 entities → result list has 3 domain docs

Implement each case with `mockk` stubs and `coEvery`/`every`. Use `runTest { }` for suspend tests. Reference `DocumentCountRepositoryTest.kt` (Task 1.6.1) for DI setup boilerplate.

- [ ] **Step 3.6.2: Run new tests**

Run:
```bash
export JAVA_HOME="$(/c/Program\ Files/Eclipse\ Adoptium/jdk-21.*/bin/java -XshowSettings:properties -version 2>&1 | grep 'java.home' | awk -F'= ' '{print $2}')"
./gradlew :app:testReleaseUnitTest --tests "*DocumentListRepositoryTest*" --no-daemon
```

Expected: 10 tests pass.

### Task 3.7: Local CI

- [ ] **Step 3.7.1: validate-ci.sh**

Run:
```bash
cd "E:/Git/paperless client" && ./scripts/validate-ci.sh
```

Expected: green.

### Task 3.8: Commit

- [ ] **Step 3.8.1: Stage and commit**

Run:
```bash
cd "E:/Git/paperless client" && \
  git add app/src/main/java/com/paperless/scanner/data/repository/DocumentListRepository.kt \
          app/src/test/java/com/paperless/scanner/data/repository/DocumentListRepositoryTest.kt \
          app/src/main/java/com/paperless/scanner/data/repository/DocumentRepository.kt \
          app/src/main/java/com/paperless/scanner/di/AppModule.kt && \
  git commit -m "$(cat <<'EOF'
refactor: extract DocumentListRepository (Phase 2.1 of #51)

Extracts list/paging/search methods from DocumentRepository into a
dedicated DocumentListRepository under data/repository/. Six public
methods migrate: observeDocuments, getUntaggedDocuments, getDocumentsPaged,
getDocuments, searchDocuments, getRecentDocuments. DocumentRepository
becomes a façade for these methods. No public API changes, no caller
migration.

Closes #164

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

### Task 3.9: Manual on-device smoke

- [ ] **Step 3.9.1: Smoke test list flows**

Build/install per Step 1.9.1, then on-device:
1. Documents list scrolls smoothly past first page (paging works).
2. Search bar finds expected documents.
3. Filter by correspondent / tag / type updates the list.
4. Untagged Smart-Tagging list shows expected documents.
5. Home screen "Recent" section displays correctly.

### Task 3.10: Push and PR

- [ ] **Step 3.10.1: Push**

Run:
```bash
cd "E:/Git/paperless client" && git push -u origin refactor/51-extract-document-list
```

- [ ] **Step 3.10.2: Open the PR**

Run:
```bash
cd "E:/Git/paperless client" && gh pr create \
  --base main \
  --head refactor/51-extract-document-list \
  --title "refactor: extract DocumentListRepository (Phase 2.1 of #51)" \
  --body "$(cat <<'EOF'
## Summary
- Extracts the 6 list/paging/search methods (`observeDocuments`, `getUntaggedDocuments`, `getDocumentsPaged`, `getDocuments`, `searchDocuments`, `getRecentDocuments`) into a dedicated `DocumentListRepository`.
- `DocumentRepository` becomes a façade for these methods. Zero caller changes.
- Final extraction in Phase 2 — Phases 3–5 remain open.

## Phase
Phase 2.1 of #51 — DocumentRepository God-class refactor. See [spec](docs/superpowers/specs/2026-05-06-document-repository-refactor-phase2-design.md).

## Tests
- ✅ Existing `DocumentRepositoryTest` stays green.
- ✅ New `DocumentListRepositoryTest` adds 10 cases covering reactive observe, Paging, cache-vs-network branches, search, and recent.

## Test plan
- [x] `./scripts/validate-ci.sh` (RELEASE variants) green
- [x] On-device: list scroll/paging, search, filter, untagged list, recent

Closes #164
EOF
)"
```

- [ ] **Step 3.10.3: Address CodeRabbit, squash-merge after green**

Same pattern as 1.10.3–1.10.4.

---

## Post-PR3: Update Parent Issue

- [ ] **Step P3.1: Update #51 checklist**

In the GitHub UI (or via `gh issue edit 51`), tick the Phase 2 checkboxes for sub-issues #164, #165, #166. Do NOT close #51 — Phases 3–5 remain.

- [ ] **Step P3.2: Update memory file**

Edit `C:\Users\marcu\.claude\projects\E--Git-paperless-client\memory\issue_51_phase1_complete.md`:
- Rename to `issue_51_phase2_complete.md` (or update the title in-place)
- Update the State section to mark Phase 2 complete with PR SHAs
- Update "How to resume" to point at Phase 3 (TrashRepository / AuditRepository / DocumentSyncRepository — note that 3.3 needs its own brainstorm, highest risk)

Update `C:\Users\marcu\.claude\projects\E--Git-paperless-client\memory\MEMORY.md` pointer:
```
- [Issue #51 DocumentRepository refactor — Phase 2 done, Phase 3-5 stubbed](issue_51_phase2_complete.md) — resume: brainstorm Phase 3.3 spec next; #167-#171 sub-issues open
```

---

## Acceptance Criteria (overall, copied from spec Section 11)

- [ ] 3 new files at `data/repository/` with `@Inject constructor` + `@Singleton`
- [ ] `DocumentRepository.kt` reduced by ≥ 350 LOC (1248 → ≤ 900)
- [ ] `DocumentRepository.kt` constructor extended by exactly 3 fields (`list`, `count`, `metadata`)
- [ ] All 14 affected methods are one-line delegations after Phase 2
- [ ] `DocumentRepositoryTest.kt` green on every PR
- [ ] 30 new tests across 3 new test files, all green
- [ ] `./scripts/validate-ci.sh` green before each push (RELEASE variants)
- [ ] CodeRabbit ASSERTIVE findings per PR actioned or skipped with rationale
- [ ] All 3 sub-issues (#164, #165, #166) merged via "Closes #..."
- [ ] #51 parent-issue checklist updated; #167–#171 remain open
- [ ] Manual on-device smoke per PR completed
- [ ] Inline `// PHASE-3.3:` markers in `DocumentMetadataRepository` flag offline-queue extraction points
