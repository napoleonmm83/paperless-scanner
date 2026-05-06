# DocumentRepository Refactor — Phase 3.1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract `TrashRepository` from `DocumentRepository`, including the soft-delete (`deleteDocument`), trash reads, restore + permanent-delete (with internal single→bulk consolidation), and maintenance helpers. Delete two confirmed-dead methods. Single PR.

**Architecture:** One new repository in `data/repository/`. `DocumentRepository` becomes a 1:1 façade for 11 trash methods. Single+bulk mutation pairs are consolidated internally — single methods are 1-line wrappers delegating to the bulk variant. The offline-queue logic moves with the methods as Phase-3.3 debt, marked inline with `// PHASE-3.3:`.

**Tech Stack:** Kotlin 2.0, Hilt DI, Kotlin Flow, Room (CachedDocumentDao, CachedTaskDao, PendingChangeDao), Robolectric + mockk, Gradle (`testReleaseUnitTest`, `lintRelease`, `assembleRelease`), `validate-ci.sh`.

**Spec reference:** [`docs/superpowers/specs/2026-05-06-document-repository-refactor-phase3-1-design.md`](../specs/2026-05-06-document-repository-refactor-phase3-1-design.md)

**Phase 1/2 references (templates):**
- Repos pattern: `app/src/main/java/com/paperless/scanner/data/repository/{DocumentCountRepository,DocumentMetadataRepository,DocumentListRepository}.kt`
- Tests: `app/src/test/java/com/paperless/scanner/data/repository/{DocumentCountRepositoryTest,DocumentMetadataRepositoryTest,DocumentListRepositoryTest}.kt`

---

## File Structure

| Path | Action | Notes |
|---|---|---|
| `app/src/main/java/com/paperless/scanner/data/repository/TrashRepository.kt` | Create | ~280 LOC, single class, 11 public methods + consolidation |
| `app/src/test/java/com/paperless/scanner/data/repository/TrashRepositoryTest.kt` | Create | 15 test cases |
| `app/src/main/java/com/paperless/scanner/data/repository/DocumentRepository.kt` | Modify | Constructor +1 dep; 11 method bodies become 1-line delegations; 2 dead methods deleted; stale imports removed |
| `app/src/main/java/com/paperless/scanner/di/AppModule.kt` | Modify | `provideDocumentRepository` +1 param + import |
| `app/src/test/java/com/paperless/scanner/data/repository/DocumentRepositoryTest.kt` | Modify | 17th constructor arg (minimal) |

---

## Pre-PR Setup

- [ ] **Step P.1: Confirm baseline state**

Run:
```bash
cd "E:/Git/paperless client" && git status && git log --oneline -3
```

Expected: working tree clean (untracked AI tool files OK), on `main`, latest commit is `41782b5 docs(superpowers): add Phase 3.1 design spec for #51 (TrashRepository)` with `6fd2ef6 refactor: extract DocumentListRepository (Phase 2.1 of #51) (#179)` shortly behind.

- [ ] **Step P.2: Verify existing test suite green on main**

Run:
```bash
export JAVA_HOME="$(ls -d '/c/Program Files/Eclipse Adoptium/jdk-21.'* 2>/dev/null | head -1)"
./gradlew :app:testReleaseUnitTest --tests "*DocumentRepositoryTest*" --no-daemon
```

Expected: BUILD SUCCESSFUL. If any fail, STOP and investigate before starting Phase 3.1.

---

## Task 1: Create branch

- [ ] **Step 1.1: Create and checkout the branch**

Run:
```bash
cd "E:/Git/paperless client" && git checkout main && git pull --rebase --autostash && git checkout -b refactor/51-extract-trash-repository
```

Expected: `Switched to a new branch 'refactor/51-extract-trash-repository'`. The pull may fast-forward or no-op depending on whether main moved; either is fine.

---

## Task 2: Create TrashRepository

**Files:**
- Create: `app/src/main/java/com/paperless/scanner/data/repository/TrashRepository.kt`

- [ ] **Step 2.1: Write the new repository file**

Create `app/src/main/java/com/paperless/scanner/data/repository/TrashRepository.kt` with this exact content:

```kotlin
package com.paperless.scanner.data.repository

import android.content.Context
import android.util.Log
import com.paperless.scanner.R
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.api.models.AcknowledgeTasksRequest
import com.paperless.scanner.data.api.models.TrashBulkActionRequest
import com.paperless.scanner.data.database.dao.CachedDocumentDao
import com.paperless.scanner.data.database.dao.CachedTaskDao
import com.paperless.scanner.data.database.dao.PendingChangeDao
import com.paperless.scanner.data.database.entities.CachedDocument
import com.paperless.scanner.data.database.entities.PendingChange
import com.paperless.scanner.data.database.mappers.toCachedEntity
import com.paperless.scanner.data.network.NetworkMonitor
import com.paperless.scanner.domain.mapper.toDomain
import com.paperless.scanner.domain.model.DocumentsResponse
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * Phase 3.1 of #51 — extracted from DocumentRepository.
 *
 * Owns trash-related operations: soft-delete (deleteDocument), trash reads
 * (observe* + getTrashDocuments), restore + permanent-delete (single delegates
 * to bulk), and maintenance helpers (getOldDeletedDocumentIds,
 * cleanupOrphanedTrashDocs).
 *
 * PHASE-3.3 DEBT: Three offline-queue branches (deleteDocument, restoreDocuments,
 * permanentlyDeleteDocuments) belong to the future DocumentSyncRepository (#169).
 * They are moved here together with the methods for now; pendingChangeDao becomes
 * unused the moment Phase 3.3 introduces DocumentSyncRepository.executeOrQueue { ... }.
 * Inline `// PHASE-3.3:` markers flag the exact extraction points.
 */
@Singleton
class TrashRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: PaperlessApi,
    private val cachedDocumentDao: CachedDocumentDao,
    private val cachedTaskDao: CachedTaskDao,                  // for deleteDocument cascade only
    private val pendingChangeDao: PendingChangeDao,            // PHASE-3.3 debt
    private val networkMonitor: NetworkMonitor,
) {

    companion object {
        private const val TAG = "TrashRepository"
    }

    // ===== Soft-delete (Phase-3.3 debt at offline branch) =====

    suspend fun deleteDocument(documentId: Int): Result<Unit> {
        return try {
            if (networkMonitor.checkOnlineStatus()) {
                // CASCADE CLEANUP STEP 1: Get all unacknowledged task IDs for this document
                // Must happen BEFORE deletion to get task IDs
                val tasks = cachedTaskDao.getAllTasks()
                    .filter { it.relatedDocument == documentId.toString() && !it.acknowledged }
                val taskIds = tasks.map { it.id }

                // OPTIMISTIC UI: Soft-delete locally FIRST for immediate UI feedback
                // This is critical for Gmail-style swipe animations where the card
                // slides off-screen before the API call completes
                val deletedAt = System.currentTimeMillis()
                cachedDocumentDao.softDelete(documentId, deletedAt = deletedAt)
                cachedTaskDao.acknowledgeTasksForDocument(documentId.toString())

                // Online: Delete via API (wrapped in try-catch for rollback on exception)
                try {
                    val response = api.deleteDocument(documentId)

                    if (response.isSuccessful) {
                        // CASCADE CLEANUP STEP 2: Acknowledge tasks on SERVER
                        if (taskIds.isNotEmpty()) {
                            try {
                                val ackRequest = AcknowledgeTasksRequest(taskIds)
                                api.acknowledgeTasks(ackRequest)
                            } catch (e: Exception) {
                                Log.w(TAG, "Failed to acknowledge tasks on server: ${e.message}")
                                // Continue anyway - local cleanup is more important for UX
                            }
                        }
                        Result.success(Unit)
                    } else {
                        // API FAILED: Rollback optimistic delete by restoring the document
                        Log.w(TAG, "deleteDocument API failed (HTTP ${response.code()}), rolling back optimistic delete")
                        cachedDocumentDao.restoreDocument(documentId)

                        val errorBody = try {
                            response.errorBody()?.string()
                        } catch (_: Exception) {
                            null
                        }
                        Log.e(TAG, "deleteDocument failed: HTTP ${response.code()}, body: $errorBody")
                        Result.failure(
                            PaperlessException.fromHttpCode(
                                response.code(),
                                errorBody ?: response.message()
                            )
                        )
                    }
                } catch (e: Exception) {
                    // API EXCEPTION (timeout, network error): Rollback optimistic delete
                    Log.w(TAG, "deleteDocument API exception, rolling back optimistic delete: ${e.message}")
                    cachedDocumentDao.restoreDocument(documentId)
                    throw e
                }
            } else {
                // PHASE-3.3: extract via DocumentSyncRepository.executeOrQueue { ... }
                val pendingChange = PendingChange(
                    entityType = "document",
                    entityId = documentId,
                    changeType = "delete",
                    changeData = "{}"
                )
                pendingChangeDao.insert(pendingChange)

                // CASCADE CLEANUP: Acknowledge tasks for this document
                // CRITICAL: Must happen BEFORE soft delete so reactivity works
                cachedTaskDao.acknowledgeTasksForDocument(documentId.toString())

                cachedDocumentDao.softDelete(documentId, deletedAt = System.currentTimeMillis())

                Result.success(Unit)
            }
        } catch (e: retrofit2.HttpException) {
            Result.failure(PaperlessException.fromHttpCode(e.code(), e.message()))
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    // ===== Reads =====

    fun observeTrashedDocuments(): Flow<List<CachedDocument>> {
        return cachedDocumentDao.observeDeletedDocuments()
    }

    fun observeTrashedDocumentsCount(): Flow<Int> {
        return cachedDocumentDao.observeDeletedCount()
    }

    fun observeOldestDeletedTimestamp(): Flow<Long?> {
        return cachedDocumentDao.getOldestDeletedTimestamp()
    }

    suspend fun getTrashDocuments(
        page: Int = 1,
        pageSize: Int = 25,
    ): Result<DocumentsResponse> {
        return try {
            if (networkMonitor.checkOnlineStatus()) {
                val response = api.getTrash(page = page, pageSize = pageSize)

                // Update cache with deleted documents (keep isDeleted = true)
                val cachedEntities = response.results.map { doc ->
                    val deletionTimestamp = try {
                        java.time.Instant.parse(doc.modified).toEpochMilli()
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse modified date: ${doc.modified}", e)
                        System.currentTimeMillis()
                    }
                    doc.toCachedEntity().copy(
                        isDeleted = true,
                        deletedAt = deletionTimestamp,
                    )
                }
                cachedDocumentDao.insertAll(cachedEntities)

                Result.success(response.toDomain())
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

    // ===== Restore (single delegates to bulk; bulk = canonical) =====

    suspend fun restoreDocument(documentId: Int): Result<Unit> =
        restoreDocuments(listOf(documentId))

    suspend fun restoreDocuments(documentIds: List<Int>): Result<Unit> {
        return try {
            if (networkMonitor.checkOnlineStatus()) {
                val request = TrashBulkActionRequest(
                    documents = documentIds,
                    action = "restore",
                )
                val response = api.trashBulkAction(request)

                if (response.isSuccessful) {
                    cachedDocumentDao.restoreDocuments(documentIds)
                    Result.success(Unit)
                } else {
                    val errorBody = try {
                        response.errorBody()?.string()
                    } catch (_: Exception) {
                        null
                    }
                    Log.e(TAG, "restoreDocuments failed: HTTP ${response.code()}, body: $errorBody")
                    Result.failure(
                        PaperlessException.fromHttpCode(
                            response.code(),
                            errorBody ?: response.message()
                        )
                    )
                }
            } else {
                // PHASE-3.3: extract via DocumentSyncRepository.executeOrQueue { ... }
                documentIds.forEach { docId ->
                    val pendingChange = PendingChange(
                        entityType = "trash",
                        entityId = docId,
                        changeType = "restore",
                        changeData = "{}",
                    )
                    pendingChangeDao.insert(pendingChange)
                }

                cachedDocumentDao.restoreDocuments(documentIds)

                Result.success(Unit)
            }
        } catch (e: retrofit2.HttpException) {
            Result.failure(PaperlessException.fromHttpCode(e.code(), e.message()))
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    // ===== Permanent Delete (single delegates to bulk; bulk = canonical) =====

    suspend fun permanentlyDeleteDocument(documentId: Int): Result<Unit> =
        permanentlyDeleteDocuments(listOf(documentId))

    suspend fun permanentlyDeleteDocuments(documentIds: List<Int>): Result<Unit> {
        return try {
            if (networkMonitor.checkOnlineStatus()) {
                val request = TrashBulkActionRequest(
                    documents = documentIds,
                    action = "empty",
                )
                val response = api.trashBulkAction(request)

                if (response.isSuccessful) {
                    cachedDocumentDao.deleteByIds(documentIds)
                    Result.success(Unit)
                } else {
                    val errorBody = try {
                        response.errorBody()?.string()
                    } catch (_: Exception) {
                        null
                    }
                    Log.e(TAG, "permanentlyDeleteDocuments failed: HTTP ${response.code()}, body: $errorBody")
                    Result.failure(
                        PaperlessException.fromHttpCode(
                            response.code(),
                            errorBody ?: response.message()
                        )
                    )
                }
            } else {
                // PHASE-3.3: extract via DocumentSyncRepository.executeOrQueue { ... }
                documentIds.forEach { docId ->
                    val pendingChange = PendingChange(
                        entityType = "trash",
                        entityId = docId,
                        changeType = "delete",
                        changeData = "{}",
                    )
                    pendingChangeDao.insert(pendingChange)
                }

                cachedDocumentDao.deleteByIds(documentIds)

                Result.success(Unit)
            }
        } catch (e: retrofit2.HttpException) {
            Result.failure(PaperlessException.fromHttpCode(e.code(), e.message()))
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    // ===== Maintenance helpers =====

    suspend fun getOldDeletedDocumentIds(retentionDays: Int = 30): Result<List<Int>> {
        return try {
            val cutoffTime = System.currentTimeMillis() - (retentionDays * 24 * 60 * 60 * 1000L)
            val ids = cachedDocumentDao.getOldDeletedDocumentIds(cutoffTime)
            Result.success(ids)
        } catch (e: Exception) {
            Result.failure(PaperlessException.from(e))
        }
    }

    suspend fun cleanupOrphanedTrashDocs(serverTrashIds: Set<Int>) {
        val localDeletedIds = cachedDocumentDao.getDeletedIds().toSet()
        val orphanedIds = localDeletedIds - serverTrashIds
        if (orphanedIds.isNotEmpty()) {
            cachedDocumentDao.deleteByIds(orphanedIds.toList())
            Log.d(TAG, "Cleaned up ${orphanedIds.size} orphaned trash docs: $orphanedIds")
        }
    }
}
```

- [ ] **Step 2.2: Verify the new file compiles**

Run:
```bash
export JAVA_HOME="$(ls -d '/c/Program Files/Eclipse Adoptium/jdk-21.'* 2>/dev/null | head -1)"
./gradlew :app:compileReleaseKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL. If `unresolved reference` for any DAO method (`observeDeletedDocuments`, `observeDeletedCount`, `getOldestDeletedTimestamp`, `softDelete`, `restoreDocument`, `restoreDocuments`, `hardDelete`, `deleteByIds`, `getOldDeletedDocumentIds`, `getDeletedIds`), inspect `CachedDocumentDao.kt` to confirm the actual method names and adjust.

---

## Task 3: Update DocumentRepository to delegate

**Files:**
- Modify: `app/src/main/java/com/paperless/scanner/data/repository/DocumentRepository.kt`

- [ ] **Step 3.1: Add `trash` parameter to constructor**

Add `private val trash: TrashRepository,` as the LAST constructor parameter (after `metadata`). Constructor tail becomes:
```kotlin
    private val list: DocumentListRepository,
    private val count: DocumentCountRepository,
    private val metadata: DocumentMetadataRepository,
    private val trash: TrashRepository,
) {
```

- [ ] **Step 3.2: Replace `deleteDocument` body with delegation**

Locate `deleteDocument` (around L375). Replace the entire function (signature + body) with:
```kotlin
    suspend fun deleteDocument(documentId: Int): Result<Unit> = trash.deleteDocument(documentId)
```

(Drop the now-orphan KDoc that describes the inline cascade — it's documented in TrashRepository now.)

- [ ] **Step 3.3: Delete `observeTrashDocuments` (dead code)**

Locate `observeTrashDocuments` (around L572) including its KDoc. **Delete** the entire function and its KDoc. No replacement.

- [ ] **Step 3.4: Delete `observeTrashCount` (dead code)**

Locate `observeTrashCount` (around L583) including its KDoc. **Delete** the entire function and its KDoc. No replacement.

- [ ] **Step 3.5: Replace `getTrashDocuments` body**

Locate `getTrashDocuments` (around L595). Replace with:
```kotlin
    suspend fun getTrashDocuments(page: Int = 1, pageSize: Int = 25): Result<DocumentsResponse> =
        trash.getTrashDocuments(page, pageSize)
```

- [ ] **Step 3.6: Replace `restoreDocument` body**

Locate `restoreDocument` (around L641). Replace with:
```kotlin
    suspend fun restoreDocument(documentId: Int): Result<Unit> = trash.restoreDocument(documentId)
```

- [ ] **Step 3.7: Replace `restoreDocuments` body**

Locate `restoreDocuments` (around L699). Replace with:
```kotlin
    suspend fun restoreDocuments(documentIds: List<Int>): Result<Unit> =
        trash.restoreDocuments(documentIds)
```

- [ ] **Step 3.8: Replace `permanentlyDeleteDocument` body**

Locate `permanentlyDeleteDocument` (around L759). Replace with:
```kotlin
    suspend fun permanentlyDeleteDocument(documentId: Int): Result<Unit> =
        trash.permanentlyDeleteDocument(documentId)
```

- [ ] **Step 3.9: Replace `permanentlyDeleteDocuments` body**

Locate `permanentlyDeleteDocuments` (around L817). Replace with:
```kotlin
    suspend fun permanentlyDeleteDocuments(documentIds: List<Int>): Result<Unit> =
        trash.permanentlyDeleteDocuments(documentIds)
```

- [ ] **Step 3.10: Replace `getOldDeletedDocumentIds` body**

Locate `getOldDeletedDocumentIds` (around L877). Replace with:
```kotlin
    suspend fun getOldDeletedDocumentIds(retentionDays: Int = 30): Result<List<Int>> =
        trash.getOldDeletedDocumentIds(retentionDays)
```

- [ ] **Step 3.11: Replace `observeTrashedDocuments` body**

Locate `observeTrashedDocuments` (around L897). Replace with:
```kotlin
    fun observeTrashedDocuments(): Flow<List<com.paperless.scanner.data.database.entities.CachedDocument>> =
        trash.observeTrashedDocuments()
```

- [ ] **Step 3.12: Replace `observeTrashedDocumentsCount` body**

Locate `observeTrashedDocumentsCount` (around L907). Replace with:
```kotlin
    fun observeTrashedDocumentsCount(): Flow<Int> = trash.observeTrashedDocumentsCount()
```

- [ ] **Step 3.13: Replace `observeOldestDeletedTimestamp` body**

Locate `observeOldestDeletedTimestamp` (around L917). Replace with:
```kotlin
    fun observeOldestDeletedTimestamp(): Flow<Long?> = trash.observeOldestDeletedTimestamp()
```

- [ ] **Step 3.14: Replace `cleanupOrphanedTrashDocs` body**

Locate `cleanupOrphanedTrashDocs` (around L927). Replace with:
```kotlin
    suspend fun cleanupOrphanedTrashDocs(serverTrashIds: Set<Int>) =
        trash.cleanupOrphanedTrashDocs(serverTrashIds)
```

- [ ] **Step 3.15: Clean up unused imports**

After all delegations, the following imports in `DocumentRepository.kt` likely become unused. **Verify each via grep before removing** (`grep -n '<symbol>' app/src/main/java/com/paperless/scanner/data/repository/DocumentRepository.kt`):
- `import com.paperless.scanner.data.api.models.AcknowledgeTasksRequest`
- `import com.paperless.scanner.data.api.models.TrashBulkActionRequest`

`PendingChange` is still imported by other methods? Verify with grep — if no remaining uses, remove it. (Note: after Phase 2.3 it was kept for delete/restore branches; after Phase 3.1 those are all delegated, so this import should now be removable.)

`Log` (android.util.Log): grep before removing — may still be used by other DocumentRepository methods.

`java.time.Instant` and `IOException`: grep before removing.

For each import that grep returns 0 uses for: delete the import line.

- [ ] **Step 3.16: Verify DocumentRepository compiles**

Run:
```bash
export JAVA_HOME="$(ls -d '/c/Program Files/Eclipse Adoptium/jdk-21.'* 2>/dev/null | head -1)"
./gradlew :app:compileReleaseKotlin --no-daemon
```

Expected: BUILD FAILED with "parameter not provided" in `AppModule.provideDocumentRepository` — the `trash` ctor param isn't wired yet. (Fixed in Task 4.) If you get `unresolved reference: trash`, double-check the constructor change in 3.1.

---

## Task 4: Update AppModule

**Files:**
- Modify: `app/src/main/java/com/paperless/scanner/di/AppModule.kt`

- [ ] **Step 4.1: Add `trash` parameter to provideDocumentRepository**

Locate `provideDocumentRepository`. Add `trash: TrashRepository,` as the LAST parameter and pass `trash` to the `DocumentRepository(...)` call. Add the import at the top of the file:
```kotlin
import com.paperless.scanner.data.repository.TrashRepository
```

- [ ] **Step 4.2: Verify compile passes**

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

- [ ] **Step 5.1: Add `trashRepository` to setup() and ctor call**

In the `setup()` block, after constructing `listRepository` (the 16th-arg field added in PR3 of Phase 2), add:
```kotlin
        val trashRepository = TrashRepository(
            context = context,
            api = api,
            cachedDocumentDao = cachedDocumentDao,
            cachedTaskDao = cachedTaskDao,
            pendingChangeDao = pendingChangeDao,
            networkMonitor = networkMonitor,
        )
```

Then append `trashRepository,` as the 17th argument to the `DocumentRepository(...)` constructor call. Add the import:
```kotlin
import com.paperless.scanner.data.repository.TrashRepository
```

If the test class doesn't yet have `cachedTaskDao` or `pendingChangeDao` fields (because earlier phases didn't need them at the test level), declare them at the top of the class:
```kotlin
    private lateinit var cachedTaskDao: CachedTaskDao
    private lateinit var pendingChangeDao: PendingChangeDao
```
And construct them in `setup()` with `mockk(relaxed = true)`. Add imports for `CachedTaskDao` and `PendingChangeDao` from `com.paperless.scanner.data.database.dao.*`.

- [ ] **Step 5.2: Run DocumentRepositoryTest**

Run:
```bash
export JAVA_HOME="$(ls -d '/c/Program Files/Eclipse Adoptium/jdk-21.'* 2>/dev/null | head -1)"
./gradlew :app:testReleaseUnitTest --tests "*DocumentRepositoryTest*" --no-daemon
```

Expected: BUILD SUCCESSFUL.

---

## Task 6: Write TrashRepository tests

**Files:**
- Create: `app/src/test/java/com/paperless/scanner/data/repository/TrashRepositoryTest.kt`

- [ ] **Step 6.1: Write the test file**

Create `app/src/test/java/com/paperless/scanner/data/repository/TrashRepositoryTest.kt`. Required structure (Robolectric + mockk relaxed). Follow `DocumentMetadataRepositoryTest.kt` for fixture patterns (DTO/entity builders, slot capture, coVerify). Required 15 cases (use these exact test names):

```kotlin
package com.paperless.scanner.data.repository

import androidx.test.core.app.ApplicationProvider
import com.paperless.scanner.data.api.PaperlessApi
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.api.models.AcknowledgeTasksRequest
import com.paperless.scanner.data.api.models.TrashBulkActionRequest
import com.paperless.scanner.data.database.dao.CachedDocumentDao
import com.paperless.scanner.data.database.dao.CachedTaskDao
import com.paperless.scanner.data.database.dao.PendingChangeDao
import com.paperless.scanner.data.database.entities.CachedDocument
import com.paperless.scanner.data.database.entities.PendingChange
import com.paperless.scanner.data.network.NetworkMonitor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.Response

@RunWith(RobolectricTestRunner::class)
class TrashRepositoryTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var api: PaperlessApi
    private lateinit var dao: CachedDocumentDao
    private lateinit var taskDao: CachedTaskDao
    private lateinit var pendingDao: PendingChangeDao
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var repo: TrashRepository

    @Before
    fun setup() {
        api = mockk(relaxed = true)
        dao = mockk(relaxed = true)
        taskDao = mockk(relaxed = true)
        pendingDao = mockk(relaxed = true)
        networkMonitor = mockk(relaxed = true)
        repo = TrashRepository(context, api, dao, taskDao, pendingDao, networkMonitor)
    }

    // ---- Reads ----

    @Test
    fun `observeTrashedDocuments delegates to dao observeDeletedDocuments`() = runTest {
        val sample = listOf(cachedDoc(id = 1, isDeleted = true))
        every { dao.observeDeletedDocuments() } returns flowOf(sample)
        val result = repo.observeTrashedDocuments().first()
        assertEquals(sample, result)
    }

    @Test
    fun `observeTrashedDocumentsCount delegates to dao observeDeletedCount`() = runTest {
        every { dao.observeDeletedCount() } returns flowOf(7)
        assertEquals(7, repo.observeTrashedDocumentsCount().first())
    }

    @Test
    fun `observeOldestDeletedTimestamp delegates to dao`() = runTest {
        every { dao.getOldestDeletedTimestamp() } returns flowOf(123_456L)
        assertEquals(123_456L, repo.observeOldestDeletedTimestamp().first())
    }

    @Test
    fun `getTrashDocuments online inserts cache and returns response`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        // mock api.getTrash(...) → DTO with results, then assert dao.insertAll called and result.isSuccess
        // (use existing fixture builder apiTrashDoc / apiDoc from sister tests)
    }

    @Test
    fun `getTrashDocuments offline returns NetworkError`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns false
        val result = repo.getTrashDocuments()
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is PaperlessException.NetworkError)
    }

    // ---- deleteDocument ----

    @Test
    fun `deleteDocument online happy path soft-deletes locally then API succeeds`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        coEvery { taskDao.getAllTasks() } returns emptyList()
        coEvery { api.deleteDocument(42) } returns Response.success(Unit)

        val result = repo.deleteDocument(42)

        assertTrue(result.isSuccess)
        coVerify { dao.softDelete(42, deletedAt = any()) }
        coVerify { taskDao.acknowledgeTasksForDocument("42") }
    }

    @Test
    fun `deleteDocument online API failure rolls back optimistic delete`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        coEvery { taskDao.getAllTasks() } returns emptyList()
        val errorBody = "{}".toResponseBody("application/json".toMediaTypeOrNull())
        coEvery { api.deleteDocument(42) } returns Response.error(500, errorBody)

        val result = repo.deleteDocument(42)

        assertTrue(result.isFailure)
        coVerify { dao.softDelete(42, deletedAt = any()) }
        coVerify { dao.restoreDocument(42) }  // rollback
    }

    @Test
    fun `deleteDocument online cascade-acknowledges tasks for document`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        // taskDao.getAllTasks returns 2 unack'd tasks for docId=42 + 1 already-acked
        // (use a small fixture builder for CachedTask: id="t1", relatedDocument="42", acknowledged=false)
        // assert: api.acknowledgeTasks(AcknowledgeTasksRequest(listOf("t1","t2"))) called once
        coEvery { api.deleteDocument(42) } returns Response.success(Unit)
        val ackSlot = slot<AcknowledgeTasksRequest>()
        coEvery { api.acknowledgeTasks(capture(ackSlot)) } returns Response.success(Unit)

        repo.deleteDocument(42)

        coVerify { api.acknowledgeTasks(any()) }
        // Optionally inspect ackSlot.captured.tasks for task ids
    }

    @Test
    fun `deleteDocument offline writes PendingChange and softDeletes`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns false
        val pendingSlot = slot<PendingChange>()
        coEvery { pendingDao.insert(capture(pendingSlot)) } returns 1L

        val result = repo.deleteDocument(42)

        assertTrue(result.isSuccess)
        assertEquals("document", pendingSlot.captured.entityType)
        assertEquals(42, pendingSlot.captured.entityId)
        assertEquals("delete", pendingSlot.captured.changeType)
        coVerify { dao.softDelete(42, deletedAt = any()) }
        coVerify { taskDao.acknowledgeTasksForDocument("42") }
    }

    // ---- restore (consolidation) ----

    @Test
    fun `restoreDocument single delegates to bulk with one-element list`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        val requestSlot = slot<TrashBulkActionRequest>()
        coEvery { api.trashBulkAction(capture(requestSlot)) } returns Response.success(Unit)

        repo.restoreDocument(99)

        assertEquals(listOf(99), requestSlot.captured.documents)
        assertEquals("restore", requestSlot.captured.action)
    }

    @Test
    fun `restoreDocuments online happy path`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        coEvery { api.trashBulkAction(any()) } returns Response.success(Unit)

        val result = repo.restoreDocuments(listOf(1, 2, 3))

        assertTrue(result.isSuccess)
        coVerify { dao.restoreDocuments(listOf(1, 2, 3)) }
    }

    @Test
    fun `restoreDocuments offline writes one PendingChange per id`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns false

        val result = repo.restoreDocuments(listOf(1, 2, 3))

        assertTrue(result.isSuccess)
        coVerify(exactly = 3) { pendingDao.insert(any()) }
        coVerify { dao.restoreDocuments(listOf(1, 2, 3)) }
    }

    // ---- permanently delete (consolidation) ----

    @Test
    fun `permanentlyDeleteDocument single delegates to bulk`() = runTest {
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        val requestSlot = slot<TrashBulkActionRequest>()
        coEvery { api.trashBulkAction(capture(requestSlot)) } returns Response.success(Unit)

        repo.permanentlyDeleteDocument(99)

        assertEquals(listOf(99), requestSlot.captured.documents)
        assertEquals("empty", requestSlot.captured.action)
    }

    @Test
    fun `permanentlyDeleteDocuments online + offline branches`() = runTest {
        // Online branch
        coEvery { networkMonitor.checkOnlineStatus() } returns true
        coEvery { api.trashBulkAction(any()) } returns Response.success(Unit)
        val online = repo.permanentlyDeleteDocuments(listOf(5, 6))
        assertTrue(online.isSuccess)
        coVerify { dao.deleteByIds(listOf(5, 6)) }

        // Offline branch (re-stub network)
        coEvery { networkMonitor.checkOnlineStatus() } returns false
        val offline = repo.permanentlyDeleteDocuments(listOf(7))
        assertTrue(offline.isSuccess)
        coVerify(exactly = 1) { pendingDao.insert(match { it.entityId == 7 && it.changeType == "delete" }) }
        coVerify { dao.deleteByIds(listOf(7)) }
    }

    // ---- maintenance ----

    @Test
    fun `cleanupOrphanedTrashDocs removes locally-deleted-but-not-on-server`() = runTest {
        // Local: 1, 2, 3 marked deleted; server: 1, 3 still in trash → orphan: 2
        coEvery { dao.getDeletedIds() } returns listOf(1, 2, 3)

        repo.cleanupOrphanedTrashDocs(setOf(1, 3))

        coVerify { dao.deleteByIds(listOf(2)) }
    }

    // ---- fixture helpers ----
    // Implement these by mirroring DocumentMetadataRepositoryTest's apiDoc / cachedDoc helpers.
    // private fun cachedDoc(id: Int, isDeleted: Boolean = false): CachedDocument = ...
    // private fun apiTrashDoc(id: Int, modified: String = "2024-01-27T14:30:00Z"): DocumentDto = ...
}
```

Engineer note: the 4 test stubs marked with comments (cases #4, #8, fixture builders) need concrete fixture implementations — pull them from `DocumentMetadataRepositoryTest.kt` and adapt. The `getTrashDocuments online` test (#4) needs an `api.getTrash(...)` stub returning a `DocumentsResponse` DTO; the `cascade-acknowledges` test (#8) needs a `CachedTask` builder. Both patterns exist in sister test files.

If tests fail with `Response.success(Unit)` type issues, the `api.deleteDocument` returns `Response<Unit>` — verify by inspecting `PaperlessApi.kt` and adjust the `Response.success(...)` argument.

- [ ] **Step 6.2: Run new tests**

Run:
```bash
export JAVA_HOME="$(ls -d '/c/Program Files/Eclipse Adoptium/jdk-21.'* 2>/dev/null | head -1)"
./gradlew :app:testReleaseUnitTest --tests "*TrashRepositoryTest*" --no-daemon
```

Expected: 15 tests pass. If a test fails because of mock signature mismatch, adapt the test (NOT the production code). If a fixture builder is missing, add a small one to the test file (or import from `DocumentMetadataRepositoryTest`).

---

## Task 7: Local CI validation

- [ ] **Step 7.1: Run validate-ci.sh**

Run:
```bash
cd "E:/Git/paperless client" && ./scripts/validate-ci.sh
```

Expected: green for translation/duplicate/empty checks, `testReleaseUnitTest`, `assembleRelease`, `lintRelease`. If lint flags new issues introduced by the new file, fix them; do not add to baseline.

---

## Task 8: Commit

- [ ] **Step 8.1: Stage and commit**

Run:
```bash
cd "E:/Git/paperless client" && \
  git add app/src/main/java/com/paperless/scanner/data/repository/TrashRepository.kt \
          app/src/test/java/com/paperless/scanner/data/repository/TrashRepositoryTest.kt \
          app/src/main/java/com/paperless/scanner/data/repository/DocumentRepository.kt \
          app/src/main/java/com/paperless/scanner/di/AppModule.kt \
          app/src/test/java/com/paperless/scanner/data/repository/DocumentRepositoryTest.kt && \
  git commit -m "$(cat <<'EOF'
refactor: extract TrashRepository (Phase 3.1 of #51)

Extracts trash-related operations from DocumentRepository into a
dedicated TrashRepository under data/repository/. Includes deleteDocument
(soft-delete), getTrashDocuments, restore + permanentlyDelete (single
methods consolidated as 1-line wrappers delegating to bulk variants),
trash Flow reads, retention helpers, and orphan cleanup.

Side cleanups in this PR:
- Delete two confirmed-dead methods (observeTrashDocuments,
  observeTrashCount) — duplicates of observeTrashedDocuments /
  observeTrashedDocumentsCount with zero callers.
- Internally consolidate single+bulk pairs to drop ~110 LOC duplication.

The offline-queue branches (deleteDocument, restoreDocuments,
permanentlyDeleteDocuments) move with the methods as Phase-3.3 debt;
inline // PHASE-3.3: comments mark the extraction points for the
future DocumentSyncRepository (#169).

DocumentRepository becomes a thin façade for these methods. No public
API changes (except removing 2 dead methods), no caller migration.

Closes #167

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

Expected: pre-commit hook passes, commit created.

---

## Task 9: Manual on-device smoke

- [ ] **Step 9.1: Build and install debug APK**

Run:
```bash
export JAVA_HOME="$(ls -d '/c/Program Files/Eclipse Adoptium/jdk-21.'* 2>/dev/null | head -1)"
./gradlew :app:installDebug --no-daemon
```

Expected: APK installs.

- [ ] **Step 9.2: Smoke test trash flows on the device**

On-device, verify each flow:
1. **Soft-delete (online)**: open a document, swipe to delete (Gmail-style animation). Document disappears from list, appears in Trash. Server confirms deletion (re-open Documents → still gone; re-open Trash → still there).
2. **Soft-delete (offline)**: enable airplane mode, swipe-delete. Optimistic UI shows deletion. Disable airplane mode → SyncManager replays the PendingChange → server-side state matches.
3. **Restore (single + bulk)**: in Trash, restore one document (single API call, returns to Documents). Restore multiple (multi-select + bulk action).
4. **Permanent delete (single + bulk)**: in Trash, permanently delete one + multiple. Document gone from local cache; server confirms.
5. **Home Screen "Expires in X days"**: confirm `observeOldestDeletedTimestamp` Flow drives the countdown UI correctly.

If any flow breaks, STOP and diff the moved methods against the originals (`git show 6fd2ef6:app/src/main/java/com/paperless/scanner/data/repository/DocumentRepository.kt` shows the post-Phase-2 base).

---

## Task 10: Push and open PR

- [ ] **Step 10.1: Push branch**

Run:
```bash
cd "E:/Git/paperless client" && git push -u origin refactor/51-extract-trash-repository
```

Expected: pre-push hook runs full validate-ci.sh (auto-rebase if origin/main moved), then pushes. If hook fails, fix and retry — do NOT use `--no-verify`.

- [ ] **Step 10.2: Open the PR**

Run:
```bash
cd "E:/Git/paperless client" && gh pr create \
  --base main \
  --head refactor/51-extract-trash-repository \
  --title "refactor: extract TrashRepository (Phase 3.1 of #51)" \
  --body "$(cat <<'EOF'
## Summary
- Extracts 11 trash methods (`deleteDocument`, `getTrashDocuments`, `restoreDocument(s)`, `permanentlyDeleteDocument(s)`, `observeTrashedDocuments(Count)`, `observeOldestDeletedTimestamp`, `getOldDeletedDocumentIds`, `cleanupOrphanedTrashDocs`) into a dedicated `TrashRepository`.
- Delete 2 confirmed-dead methods (`observeTrashDocuments`, `observeTrashCount`) — zero callers in the codebase, duplicates of the live `observeTrashed*` Flows.
- Internally consolidate single+bulk pairs (`restoreDocument` / `permanentlyDeleteDocument` are 1-line wrappers delegating to their bulk variants) — saves ~110 LOC duplication.
- `DocumentRepository` becomes a façade for these methods. Zero caller changes.

## Phase
Phase 3.1 of #51 — DocumentRepository God-class refactor. See [spec](docs/superpowers/specs/2026-05-06-document-repository-refactor-phase3-1-design.md).

## Phase-3.3 debt (deferred)
Three offline-queue branches (`deleteDocument`, `restoreDocuments`, `permanentlyDeleteDocuments`) move with the methods. Inline `// PHASE-3.3:` markers flag the exact extraction points for the future `DocumentSyncRepository.executeOrQueue { ... }` (#169). After Phase 3.1, the cumulative Phase-3.3 debt is 5 markers across `DocumentMetadataRepository` (2) + `TrashRepository` (3).

## Tests
- ✅ Existing `DocumentRepositoryTest` stays green (façade delegates 1:1; 17th ctor arg added).
- ✅ New `TrashRepositoryTest` adds 15 cases covering all 3 mutation branches with online + offline paths, cascade-task-ack, optimistic-rollback, single-delegates-to-bulk consolidation contracts, and orphan cleanup.

## Test plan
- [x] `./scripts/validate-ci.sh` (RELEASE variants) green locally
- [ ] On-device smoke: soft-delete (online + offline), restore single + bulk, permanently delete single + bulk, Home expires-in-X countdown

Closes #167
EOF
)"
```

Expected: PR opened, gh prints the URL.

- [ ] **Step 10.3: Address CodeRabbit review**

Wait for CodeRabbit. Action small real bugs (≤5 LOC) inside this PR. Skip architectural suggestions (Dispatchers.IO, interface introduction, error-string-in-data-layer) with a short rationale comment referencing §9 (Out-of-Scope) of the spec.

- [ ] **Step 10.4: Squash-merge after CI green and review approved**

```bash
gh pr merge --squash --delete-branch
```

Expected: PR merged, branch deleted, `main` advances.

---

## Task 11: Post-merge — update memory

- [ ] **Step 11.1: Sync local main**

Run:
```bash
cd "E:/Git/paperless client" && git checkout main && git fetch origin && git rebase origin/main
```

Use `git rebase --skip` for any add/add doc conflicts (the squashed merge contains your local commits — no work lost).

- [ ] **Step 11.2: Update memory file**

Rename `C:\Users\marcu\.claude\projects\E--Git-paperless-client\memory\issue_51_phase2_complete.md` to `issue_51_phase3_1_complete.md` (or update title in-place). Add the Phase 3.1 row to the State table:

```
| #180 (or actual #) | #167 | TrashRepository (Phase 3.1) | ~280 | 15 | <merge SHA> |
```

Update "Where things live" to include the Phase 3.1 spec + plan paths. Update "How to resume" to point at Phase 3.2 AuditRepository (#168) as the next recommended phase. Note that Phase-3.3 debt now has 5 inline markers (2 in DocumentMetadata + 3 in TrashRepository).

- [ ] **Step 11.3: Update MEMORY.md pointer**

Edit `C:\Users\marcu\.claude\projects\E--Git-paperless-client\memory\MEMORY.md`. Change the existing `[Issue #51 ...]` pointer line to reference the new file:
```
- [Issue #51 DocumentRepository refactor — Phase 3.1 done, Phase 3.2-5 stubbed](issue_51_phase3_1_complete.md) — resume: brainstorm Phase 3.2 AuditRepository (#168) spec next; #168-#171 sub-issues open
```

---

## Acceptance Criteria (from spec §11)

- [ ] 1 new file `app/src/main/java/com/paperless/scanner/data/repository/TrashRepository.kt` with `@Inject constructor` + `@Singleton`
- [ ] DocumentRepository.kt reduced by ≥ 350 LOC (935 → ≤ 585)
- [ ] DocumentRepository constructor extended by exactly 1 field (`trash`) at position 17
- [ ] 11 affected façade methods are one-line delegations to `trash.*`
- [ ] 2 dead-code methods (`observeTrashDocuments`, `observeTrashCount`) removed without replacement
- [ ] `restoreDocument` and `permanentlyDeleteDocument` are 1-line wrappers delegating to their bulk counterparts
- [ ] `DocumentRepositoryTest.kt` green on every push (existing API unchanged; minimal 17th ctor arg)
- [ ] 15 new tests in `TrashRepositoryTest.kt`, all green
- [ ] `./scripts/validate-ci.sh` green before push (RELEASE variants)
- [ ] CodeRabbit ASSERTIVE findings actioned or skipped with rationale
- [ ] Sub-issue #167 merged via "Closes #167"
- [ ] Inline `// PHASE-3.3:` markers at exactly 3 offline-branch sites + class-level KDoc
- [ ] Manual on-device smoke per Task 9.2 completed
- [ ] Memory file + MEMORY.md pointer updated post-merge
