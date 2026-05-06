# DocumentRepository Refactor — Phase 4 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract `PermissionRepository` (2 thin online-only API wrappers — `getUsers`, `getGroups`) from `DocumentRepository` into `data/repository/`. Single PR.

**Architecture:** One new repository in `data/repository/`. `DocumentRepository` becomes a 1:1 façade for 2 permission methods. Mirrors Phase 3.2 (AuditRepository) pattern. DTO-typed return preserved byte-identical (Phase-5 will migrate to domain types).

**Tech Stack:** Kotlin 2.0, Hilt DI, Retrofit (PaperlessApi), Robolectric + mockk, Gradle (`testReleaseUnitTest`, `lintRelease`, `assembleRelease`), `validate-ci.sh`.

**Spec reference:** [`docs/superpowers/specs/2026-05-06-document-repository-refactor-phase4-design.md`](../specs/2026-05-06-document-repository-refactor-phase4-design.md)

**Phase 3.2 references (templates):**
- `app/src/main/java/com/paperless/scanner/data/repository/AuditRepository.kt`
- `app/src/test/java/com/paperless/scanner/data/repository/AuditRepositoryTest.kt`

**API signatures (verified):** `PaperlessApi.getUsers(page=1, pageSize=100): UsersResponse` and `PaperlessApi.getGroups(page=1, pageSize=100): GroupsResponse`. Existing `DocumentRepository.getUsers/getGroups` calls them with no args (defaults).

---

## File Structure

| Path | Action |
|---|---|
| `app/src/main/java/com/paperless/scanner/data/repository/PermissionRepository.kt` | Create (~60 LOC) |
| `app/src/test/java/com/paperless/scanner/data/repository/PermissionRepositoryTest.kt` | Create (5 tests) |
| `app/src/main/java/com/paperless/scanner/data/repository/DocumentRepository.kt` | Modify (2 method bodies → 1-line delegations; constructor +1 dep) |
| `app/src/main/java/com/paperless/scanner/di/AppModule.kt` | Modify (`provideDocumentRepository` +1 param + import) |
| `app/src/test/java/com/paperless/scanner/data/repository/DocumentRepositoryTest.kt` | Modify (19th constructor arg) |

---

## Task 1: Create branch

- [ ] **Step 1.1**

```bash
cd "E:/Git/paperless client" && git checkout main && git pull --rebase --autostash && git checkout -b refactor/51-extract-permission-repository
```

---

## Task 2: Create PermissionRepository

**Files:** Create `app/src/main/java/com/paperless/scanner/data/repository/PermissionRepository.kt`

- [ ] **Step 2.1: Write the file**

```kotlin
package com.paperless.scanner.data.repository

import android.content.Context
import com.paperless.scanner.R
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.api.models.Group
import com.paperless.scanner.data.api.models.User
import com.paperless.scanner.data.network.NetworkMonitor
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 4 of #51 — extracted from DocumentRepository.
 *
 * Owns user/group lookup for permission management: getUsers, getGroups.
 * Both methods are online-only thin wrappers around PaperlessApi; offline
 * branches return PaperlessException.NetworkError.
 *
 * NOTE: Return types `Result<List<User>>` and `Result<List<Group>>` use the
 * data-layer DTO types (not domain models). This is pre-existing behavior
 * preserved byte-identically; Phase 5 (#171) will migrate the call site
 * (DocumentDetailViewModel) and switch the return types to domain.
 */
@Singleton
class PermissionRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: PaperlessApi,
    private val networkMonitor: NetworkMonitor,
) {

    suspend fun getUsers(): Result<List<User>> {
        return try {
            if (networkMonitor.checkOnlineStatus()) {
                val response = api.getUsers()
                Result.success(response.results)
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

    suspend fun getGroups(): Result<List<Group>> {
        return try {
            if (networkMonitor.checkOnlineStatus()) {
                val response = api.getGroups()
                Result.success(response.results)
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

```bash
export JAVA_HOME="$(ls -d '/c/Program Files/Eclipse Adoptium/jdk-21.'* 2>/dev/null | head -1)"
./gradlew :app:compileReleaseKotlin --no-daemon
```
Expected: BUILD SUCCESSFUL.

---

## Task 3: Update DocumentRepository

**Files:** Modify `app/src/main/java/com/paperless/scanner/data/repository/DocumentRepository.kt`

- [ ] **Step 3.1: Add `permission` to constructor**

Append `private val permission: PermissionRepository,` as the LAST constructor parameter (after `audit` from Phase 3.2). Position 19.

- [ ] **Step 3.2: Replace `getUsers` body**

Locate `getUsers` (around L394). Replace with:
```kotlin
    suspend fun getUsers(): Result<List<com.paperless.scanner.data.api.models.User>> = permission.getUsers()
```

- [ ] **Step 3.3: Replace `getGroups` body**

Locate `getGroups` (around L409). Replace with:
```kotlin
    suspend fun getGroups(): Result<List<com.paperless.scanner.data.api.models.Group>> = permission.getGroups()
```

- [ ] **Step 3.4: Verify compile**

```bash
./gradlew :app:compileReleaseKotlin --no-daemon
```
Expected: BUILD FAILED with `parameter not provided` (fixed in Task 4).

---

## Task 4: Update AppModule

**Files:** Modify `app/src/main/java/com/paperless/scanner/di/AppModule.kt`

- [ ] **Step 4.1: Add `permission` parameter**

Add `permission: PermissionRepository,` as the LAST parameter to `provideDocumentRepository` and pass `permission` to the `DocumentRepository(...)` constructor call. Add the import:
```kotlin
import com.paperless.scanner.data.repository.PermissionRepository
```

- [ ] **Step 4.2: Verify compile**

```bash
./gradlew :app:compileReleaseKotlin --no-daemon
```
Expected: BUILD SUCCESSFUL.

---

## Task 5: Update DocumentRepositoryTest minimally

**Files:** Modify `app/src/test/java/com/paperless/scanner/data/repository/DocumentRepositoryTest.kt`

- [ ] **Step 5.1: Add `permissionRepository` to setup() and ctor call**

In `setup()`, after the construction of `auditRepository` (the 18th-arg field added in Phase 3.2), add:
```kotlin
val permissionRepository = PermissionRepository(
    context = context,
    api = api,
    networkMonitor = networkMonitor,
)
```

Append `permissionRepository,` as the 19th argument to the `DocumentRepository(...)` constructor call. Add the import:
```kotlin
import com.paperless.scanner.data.repository.PermissionRepository
```

- [ ] **Step 5.2: Run DocumentRepositoryTest**

```bash
./gradlew :app:testReleaseUnitTest --tests "*DocumentRepositoryTest*" --no-daemon
```
Expected: BUILD SUCCESSFUL.

---

## Task 6: Write PermissionRepository tests

**Files:** Create `app/src/test/java/com/paperless/scanner/data/repository/PermissionRepositoryTest.kt`

- [ ] **Step 6.1: Write the test file (5 cases)**

```kotlin
package com.paperless.scanner.data.repository

import android.content.Context
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.api.models.Group
import com.paperless.scanner.data.api.models.GroupsResponse
import com.paperless.scanner.data.api.models.User
import com.paperless.scanner.data.api.models.UsersResponse
import com.paperless.scanner.data.network.NetworkMonitor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.HttpException
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
class PermissionRepositoryTest {

    private lateinit var context: Context
    private lateinit var api: PaperlessApi
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var repo: PermissionRepository

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        every { context.getString(any()) } returns "offline"
        api = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)
        repo = PermissionRepository(context, api, networkMonitor)
    }

    @Test
    fun `getUsers online returns DTO list from response results`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        val users = listOf(mockk<User>(relaxed = true), mockk<User>(relaxed = true))
        coEvery { api.getUsers(any(), any()) } returns
            UsersResponse(count = 2, next = null, previous = null, results = users)

        val result = repo.getUsers()

        assertTrue(result.isSuccess)
        assertEquals(users, result.getOrNull())
        coVerify { api.getUsers(any(), any()) }
    }

    @Test
    fun `getUsers offline returns NetworkError`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns false

        val result = repo.getUsers()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is PaperlessException.NetworkError)
        coVerify(exactly = 0) { api.getUsers(any(), any()) }
    }

    @Test
    fun `getGroups online returns DTO list from response results`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        val groups = listOf(mockk<Group>(relaxed = true))
        coEvery { api.getGroups(any(), any()) } returns
            GroupsResponse(count = 1, next = null, previous = null, results = groups)

        val result = repo.getGroups()

        assertTrue(result.isSuccess)
        assertEquals(groups, result.getOrNull())
        coVerify { api.getGroups(any(), any()) }
    }

    @Test
    fun `getGroups offline returns NetworkError`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns false

        val result = repo.getGroups()

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is PaperlessException.NetworkError)
        coVerify(exactly = 0) { api.getGroups(any(), any()) }
    }

    @Test
    fun `getUsers online HttpException maps to PaperlessException via fromHttpCode`() = runTest {
        // Covers the HttpException catch branch (shared by both methods); one test
        // is sufficient because the catch logic is identical across getUsers and
        // getGroups. (Same DRY pattern as AuditRepositoryTest in Phase 3.2.)
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        val errorBody = "{}".toResponseBody("application/json".toMediaTypeOrNull())
        coEvery { api.getUsers(any(), any()) } throws HttpException(Response.error<Any>(403, errorBody))

        val result = repo.getUsers()

        assertTrue(result.isFailure)
        assertTrue("expected PaperlessException, got ${result.exceptionOrNull()}", result.exceptionOrNull() is PaperlessException)
    }
}
```

If `UsersResponse` / `GroupsResponse` DTO field names differ (e.g., the spec assumes `count, next, previous, results` like the other API responses but the actual DTO might use different names), inspect `app/src/main/java/com/paperless/scanner/data/api/models/` and adjust. Production code is correct — fix the test fixture.

- [ ] **Step 6.2: Run new tests**

```bash
./gradlew :app:testReleaseUnitTest --tests "*PermissionRepositoryTest*" --no-daemon
```
Expected: 5/5 pass.

---

## Task 7: validate-ci.sh

- [ ] **Step 7.1**

```bash
cd "E:/Git/paperless client" && ./scripts/validate-ci.sh
```
Expected: green.

---

## Task 8: Commit

- [ ] **Step 8.1**

```bash
cd "E:/Git/paperless client" && \
  git add app/src/main/java/com/paperless/scanner/data/repository/PermissionRepository.kt \
          app/src/test/java/com/paperless/scanner/data/repository/PermissionRepositoryTest.kt \
          app/src/main/java/com/paperless/scanner/data/repository/DocumentRepository.kt \
          app/src/main/java/com/paperless/scanner/di/AppModule.kt \
          app/src/test/java/com/paperless/scanner/data/repository/DocumentRepositoryTest.kt && \
  git commit -m "$(cat <<'EOF'
refactor: extract PermissionRepository (Phase 4 of #51)

Extracts two thin online-only API wrappers (getUsers, getGroups) from
DocumentRepository into a dedicated PermissionRepository under
data/repository/. Mirrors Phase 3.2 (AuditRepository) pattern — ~30 LOC
moved, no offline-queue, no cascade, no consolidation.

DTO-typed return preserved byte-identically (Result<List<User|Group>>
where User/Group are data-layer DTOs); Phase 5 (#171) will migrate the
call site and switch return types to domain.

DocumentRepository becomes a thin façade for these methods. No public
API changes, no caller migration.

Closes #170

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: Manual on-device smoke

- [ ] **Step 9.1: Build and install**

```bash
./gradlew :app:installDebug --no-daemon
```

- [ ] **Step 9.2: Smoke test permission flows**

On-device:
1. Open a document detail → permissions section → **Add user** dropdown loads with users (calls `getUsers`).
2. Same view → **Add group** dropdown loads with groups (calls `getGroups`).
3. Airplane mode → retry → expect offline error message, no crash.

---

## Task 10: Push and open PR

- [ ] **Step 10.1: Push**

```bash
git push -u origin refactor/51-extract-permission-repository
```

- [ ] **Step 10.2: Open the PR**

```bash
gh pr create \
  --base main \
  --head refactor/51-extract-permission-repository \
  --title "refactor: extract PermissionRepository (Phase 4 of #51)" \
  --body "$(cat <<'EOF'
## Summary
- Extracts the 2 permission lookup methods (\`getUsers\`, \`getGroups\`) into a dedicated \`PermissionRepository\`.
- Mirrors Phase 3.2 (AuditRepository) pattern: thin online-only API wrappers, no offline-queue, no cascade, no consolidation.
- \`DocumentRepository\` becomes a façade for these methods. Zero caller changes.
- DocumentRepository: 465 → ~410 LOC.

## Phase
Phase 4 of #51 — DocumentRepository God-class refactor. See [spec](docs/superpowers/specs/2026-05-06-document-repository-refactor-phase4-design.md).

## Pre-existing concern (deferred to Phase 5)
Return types use data-layer DTOs (\`User\`, \`Group\`) instead of domain models — pre-existing escape preserved byte-identically. Phase 5 (#171) will migrate the call site (\`DocumentDetailViewModel\`) and switch the return types.

## Tests
- ✅ Existing \`DocumentRepositoryTest\` stays green (façade delegates 1:1; 19th ctor arg added).
- ✅ New \`PermissionRepositoryTest\` adds 5 cases: online + offline for each method, plus shared HttpException error-mapping (DRY from Phase 3.2).

## Test plan
- [x] \`./scripts/validate-ci.sh\` (RELEASE variants) green locally
- [ ] On-device smoke: users dropdown, groups dropdown, offline behavior

Closes #170
EOF
)"
```

- [ ] **Step 10.3: Address CodeRabbit and merge**

Action small ≤5 LOC inline real bugs; skip architectural / Phase-5 nitpicks with rationale.
```bash
gh pr merge --squash --delete-branch
```

---

## Task 11: Post-merge — update memory

- [ ] **Step 11.1: Sync local main**

```bash
cd "E:/Git/paperless client" && git checkout main && git fetch origin && git rebase origin/main
```
Use `git rebase --skip` for any add/add doc conflicts.

- [ ] **Step 11.2: Rename + update memory file**

Rename `C:\Users\marcu\.claude\projects\E--Git-paperless-client\memory\issue_51_phase3_2_complete.md` → `issue_51_phase4_complete.md`. Add Phase 4 row:

```
| <PR#> | #170 | PermissionRepository (Phase 4) | ~60 | 5 | <merge SHA> |
```

Update LOC progression: `465 (Phase 3.2) → ~410 (Phase 4). Cumulative: 1405 → 410 (-71 %).`

Update "How to resume" to recommend Phase 3.3 (#169 — HIGHEST RISK, dedicated brainstorm) OR Phase 5 (#171 — façade cleanup + caller migration). After Phase 4, only #169 and #171 remain in scope #51.

- [ ] **Step 11.3: Update MEMORY.md pointer**

```
- [Issue #51 DocumentRepository refactor — Phase 4 done, Phase 3.3 + Phase 5 stubbed](issue_51_phase4_complete.md) — resume: brainstorm Phase 3.3 (#169 — HIGHEST RISK, DocumentSyncRepository) or Phase 5 (#171 — façade cleanup) next; Phase-3.3 debt = 5 inline markers
```

---

## Acceptance Criteria (from spec §11)

- [ ] 1 new file `PermissionRepository.kt` with `@Inject constructor` + `@Singleton`
- [ ] DocumentRepository.kt reduced by ≥ 50 LOC (465 → ≤ 410)
- [ ] DocumentRepository constructor extended by exactly 1 field (`permission`) at LAST position
- [ ] Both affected façade methods are one-line delegations to `permission.*`
- [ ] DocumentRepositoryTest green (existing API unchanged; 19th ctor arg)
- [ ] 5 new tests in `PermissionRepositoryTest.kt`, all green
- [ ] `./scripts/validate-ci.sh` green before push
- [ ] CodeRabbit findings actioned or skipped with rationale
- [ ] Sub-issue #170 merged via "Closes #170"
- [ ] Manual on-device smoke per Task 9.2 completed
- [ ] Memory file + MEMORY.md pointer updated post-merge
