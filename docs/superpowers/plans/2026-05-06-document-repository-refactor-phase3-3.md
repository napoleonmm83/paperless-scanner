# DocumentRepository Refactor — Phase 3.3 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract `DocumentSyncRepository` and migrate 4 inline offline-queue branches (1 in DocumentMetadata + 3 in Trash) to use it. Fix 2 production bugs as part of the move (JSON-injection + TOCTOU race).

**Architecture:** New `DocumentSyncRepository` owns `executeOrQueue<T>(online, offline): Result<T>` (snapshot + IOException-fallback) plus 3 typed queue helpers (`queueDocumentUpdate`, `queueDocumentDelete`, `queueTrashAction`). DocumentMetadata + Trash drop their direct `pendingChangeDao` (and `serverHealthMonitor` for Metadata) dependencies and route through the new repo.

**Tech Stack:** Kotlin 2.0, Hilt DI, Gson, Robolectric + mockk, kotlinx-coroutines-test, Gradle, `validate-ci.sh`.

**Spec reference:** [`docs/superpowers/specs/2026-05-06-document-repository-refactor-phase3-3-design.md`](../specs/2026-05-06-document-repository-refactor-phase3-3-design.md)

**HIGHEST RISK PHASE.** Master plan flagged this as the riskiest extraction; implements 2 production bug fixes; touches existing test files.

---

## File Structure

| Path | Action |
|---|---|
| `app/src/main/java/com/paperless/scanner/data/repository/DocumentSyncRepository.kt` | Create (~150 LOC) |
| `app/src/test/java/com/paperless/scanner/data/repository/DocumentSyncRepositoryTest.kt` | Create (10 tests) |
| `app/src/main/java/com/paperless/scanner/data/repository/DocumentMetadataRepository.kt` | Modify (constructor: -2 deps +1 dep; updateDocument body wrapped in executeOrQueue; class KDoc updated; markers removed) |
| `app/src/main/java/com/paperless/scanner/data/repository/TrashRepository.kt` | Modify (constructor: -1 dep +1 dep; 3 mutation bodies wrapped in executeOrQueue; markers removed) |
| `app/src/test/java/com/paperless/scanner/data/repository/DocumentMetadataRepositoryTest.kt` | Modify (mock setup: pendingChangeDao→sync; verify-assertions migrated) |
| `app/src/test/java/com/paperless/scanner/data/repository/TrashRepositoryTest.kt` | Modify (mock setup: pendingChangeDao→sync; verify-assertions migrated) |
| `app/src/test/java/com/paperless/scanner/data/repository/DocumentRepositoryTest.kt` | Modify (constructor wiring of metadataRepository + trashRepository updated to pass sync) |

---

## Task 1: Create branch

- [ ] **Step 1.1**

```bash
cd "E:/Git/paperless client" && git checkout main && git pull --rebase --autostash && git checkout -b refactor/51-extract-document-sync
```

---

## Task 2: Create DocumentSyncRepository

**Files:** Create `app/src/main/java/com/paperless/scanner/data/repository/DocumentSyncRepository.kt`

- [ ] **Step 2.1: Write the file**

```kotlin
package com.paperless.scanner.data.repository

import com.google.gson.Gson
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.database.dao.PendingChangeDao
import com.paperless.scanner.data.database.entities.PendingChange
import com.paperless.scanner.data.health.ServerHealthMonitor
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 3.3 of #51 — centralizes the offline-queue + serverHealth pattern that
 * previously lived inline in DocumentMetadataRepository.updateDocument and three
 * TrashRepository methods (deleteDocument, restoreDocuments, permanentlyDeleteDocuments).
 *
 * Fixes two production bugs as part of the centralization:
 * 1. JSON injection in updateDocument PendingChange payload — buildString
 *    with raw "$it" interpolation produced invalid JSON for titles containing
 *    embedded `"`. queueDocumentUpdate uses gson.toJson instead.
 * 2. TOCTOU race on serverHealthMonitor.isServerReachable — was read inline
 *    without IOException fallback; if state flipped between check and API call,
 *    the user's edit was lost rather than queued. executeOrQueue snapshots
 *    once and recovers via IOException-fallback.
 */
@Singleton
class DocumentSyncRepository @Inject constructor(
    private val pendingChangeDao: PendingChangeDao,
    private val serverHealthMonitor: ServerHealthMonitor,
    private val gson: Gson,
) {

    /**
     * Snapshot serverHealth once, run [online]; on IOException fall back to
     * [offlineQueueAndOptimistic]. HttpException (4xx/5xx) does NOT trigger
     * fallback — the server responded, queueing would just re-fail.
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
     * Queue a document-update for offline sync. Uses gson to serialize the
     * payload, fixing the JSON-injection bug that affected the previous
     * buildString implementation. Property names match SyncManager.pushDocumentChange's
     * expected keys (camelCase: title, tags, correspondent, documentType,
     * archiveSerialNumber, created). Null fields are omitted by Gson default.
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

- [ ] **Step 2.2: Verify compile**

```bash
export JAVA_HOME="$(ls -d '/c/Program Files/Eclipse Adoptium/jdk-21.'* 2>/dev/null | head -1)"
./gradlew :app:compileReleaseKotlin --no-daemon
```
Expected: BUILD SUCCESSFUL.

---

## Task 3: Migrate DocumentMetadataRepository

**Files:** Modify `app/src/main/java/com/paperless/scanner/data/repository/DocumentMetadataRepository.kt`

- [ ] **Step 3.1: Update constructor**

Replace `pendingChangeDao` and `serverHealthMonitor` with `sync`. Final ctor:

```kotlin
@Singleton
class DocumentMetadataRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: PaperlessApi,
    private val cachedDocumentDao: CachedDocumentDao,
    private val cachedTagDao: CachedTagDao,
    private val networkMonitor: NetworkMonitor,
    private val serializer: DocumentSerializer,
    private val sync: DocumentSyncRepository,
) {
```

Update class KDoc — remove the "PHASE-3.3 DEBT" paragraph; mention the sync delegation instead.

Remove these imports (now unused):
- `com.paperless.scanner.data.database.dao.PendingChangeDao`
- `com.paperless.scanner.data.database.entities.PendingChange`
- `com.paperless.scanner.data.health.ServerHealthMonitor`

- [ ] **Step 3.2: Replace `updateDocument` body**

Replace the existing `updateDocument` method (containing both `// FIXME (#169):` markers and the `// PHASE-3.3:` marker) with:

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
        updatedDocument.toDomain()
    },
    offlineQueueAndOptimistic = {
        sync.queueDocumentUpdate(
            documentId = documentId,
            payload = DocumentSyncRepository.DocumentUpdatePayload(
                title = title,
                tags = tags,
                correspondent = correspondent,
                documentType = documentType,
                archiveSerialNumber = archiveSerialNumber,
                created = created,
            ),
        )
        val cached = cachedDocumentDao.getDocument(documentId)
        cached?.toCachedDomain()
            ?: throw PaperlessException.ClientError(
                404,
                context.getString(R.string.error_document_not_cached),
            )
    },
)
```

The 2 `// FIXME (#169):` comments and the `// PHASE-3.3:` comment are removed by this replacement.

- [ ] **Step 3.3: Verify compile**

```bash
./gradlew :app:compileReleaseKotlin --no-daemon
```
Expected: BUILD SUCCESSFUL or fail with `serverHealthMonitor` / `pendingChangeDao` unresolved (those should be gone). If unresolved → the constructor change in 3.1 was incomplete, fix.

- [ ] **Step 3.4: Verify Phase-3.3 markers gone from this file**

```bash
grep -n "PHASE-3.3:\|FIXME (#169):" app/src/main/java/com/paperless/scanner/data/repository/DocumentMetadataRepository.kt
```
Expected: 0 matches.

---

## Task 4: Migrate TrashRepository

**Files:** Modify `app/src/main/java/com/paperless/scanner/data/repository/TrashRepository.kt`

- [ ] **Step 4.1: Update constructor**

Replace `pendingChangeDao` with `sync`. Final ctor:

```kotlin
@Singleton
class TrashRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: PaperlessApi,
    private val cachedDocumentDao: CachedDocumentDao,
    private val cachedTaskDao: CachedTaskDao,
    private val networkMonitor: NetworkMonitor,
    private val sync: DocumentSyncRepository,
) {
```

Update class KDoc — remove PHASE-3.3 DEBT paragraph; replace with note that offline branches delegate to `DocumentSyncRepository`.

Remove these imports (now unused):
- `com.paperless.scanner.data.database.dao.PendingChangeDao`
- `com.paperless.scanner.data.database.entities.PendingChange`

- [ ] **Step 4.2: Replace `deleteDocument` body**

```kotlin
suspend fun deleteDocument(documentId: Int): Result<Unit> = sync.executeOrQueue(
    online = {
        // CASCADE STEP 1: collect unack'd task ids BEFORE optimistic delete
        val tasks = cachedTaskDao.getAllTasks()
            .filter { it.relatedDocument == documentId.toString() && !it.acknowledged }
        val taskIds = tasks.map { it.id }

        // OPTIMISTIC UI: softDelete BEFORE API call (Gmail-swipe animation)
        val deletedAt = System.currentTimeMillis()
        cachedDocumentDao.softDelete(documentId, deletedAt = deletedAt)
        cachedTaskDao.acknowledgeTasksForDocument(documentId.toString())

        try {
            val response = api.deleteDocument(documentId)
            if (response.isSuccessful) {
                if (taskIds.isNotEmpty()) {
                    try {
                        api.acknowledgeTasks(AcknowledgeTasksRequest(taskIds))
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to acknowledge tasks on server: ${e.message}")
                    }
                }
                Unit
            } else {
                // ROLLBACK on API failure
                Log.w(TAG, "deleteDocument API failed (HTTP ${response.code()}), rolling back")
                cachedDocumentDao.restoreDocument(documentId)
                val errorBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
                Log.e(TAG, "deleteDocument failed: HTTP ${response.code()}, body: $errorBody")
                throw retrofit2.HttpException(
                    retrofit2.Response.error<Unit>(
                        response.code(),
                        (errorBody ?: "{}").toResponseBody("application/json".toMediaTypeOrNull()),
                    )
                )
            }
        } catch (e: retrofit2.HttpException) {
            // Already rolled back above for non-successful responses;
            // re-throw to land in executeOrQueue's HttpException catch.
            throw e
        } catch (e: Exception) {
            // ROLLBACK on API exception (timeout, IOException, etc.)
            Log.w(TAG, "deleteDocument API exception, rolling back: ${e.message}")
            cachedDocumentDao.restoreDocument(documentId)
            throw e
        }
    },
    offlineQueueAndOptimistic = {
        sync.queueDocumentDelete(documentId)
        cachedTaskDao.acknowledgeTasksForDocument(documentId.toString())
        cachedDocumentDao.softDelete(documentId, deletedAt = System.currentTimeMillis())
    },
)
```

Imports needed for the body:
```kotlin
import com.paperless.scanner.data.api.models.AcknowledgeTasksRequest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
```

- [ ] **Step 4.3: Replace `restoreDocuments` body**

```kotlin
suspend fun restoreDocuments(documentIds: List<Int>): Result<Unit> = sync.executeOrQueue(
    online = {
        val request = TrashBulkActionRequest(documents = documentIds, action = "restore")
        val response = api.trashBulkAction(request)
        if (response.isSuccessful) {
            cachedDocumentDao.restoreDocuments(documentIds)
            Unit
        } else {
            val errorBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
            Log.e(TAG, "restoreDocuments failed: HTTP ${response.code()}, body: $errorBody")
            throw retrofit2.HttpException(
                retrofit2.Response.error<Unit>(
                    response.code(),
                    (errorBody ?: "{}").toResponseBody("application/json".toMediaTypeOrNull()),
                )
            )
        }
    },
    offlineQueueAndOptimistic = {
        sync.queueTrashAction(documentIds, DocumentSyncRepository.TrashAction.RESTORE)
        cachedDocumentDao.restoreDocuments(documentIds)
    },
)
```

- [ ] **Step 4.4: Replace `permanentlyDeleteDocuments` body**

```kotlin
suspend fun permanentlyDeleteDocuments(documentIds: List<Int>): Result<Unit> = sync.executeOrQueue(
    online = {
        val request = TrashBulkActionRequest(documents = documentIds, action = "empty")
        val response = api.trashBulkAction(request)
        if (response.isSuccessful) {
            cachedDocumentDao.deleteByIds(documentIds)
            Unit
        } else {
            val errorBody = try { response.errorBody()?.string() } catch (_: Exception) { null }
            Log.e(TAG, "permanentlyDeleteDocuments failed: HTTP ${response.code()}, body: $errorBody")
            throw retrofit2.HttpException(
                retrofit2.Response.error<Unit>(
                    response.code(),
                    (errorBody ?: "{}").toResponseBody("application/json".toMediaTypeOrNull()),
                )
            )
        }
    },
    offlineQueueAndOptimistic = {
        sync.queueTrashAction(documentIds, DocumentSyncRepository.TrashAction.PERMANENT_DELETE)
        cachedDocumentDao.deleteByIds(documentIds)
    },
)
```

The 1-line wrappers `restoreDocument(id)` and `permanentlyDeleteDocument(id)` (Phase 3.1 consolidation) stay unchanged — they delegate to the bulk variants.

- [ ] **Step 4.5: Verify compile + markers gone**

```bash
./gradlew :app:compileReleaseKotlin --no-daemon
grep -n "PHASE-3.3:\|FIXME (#169):" app/src/main/java/com/paperless/scanner/data/repository/TrashRepository.kt
```
Expected: BUILD SUCCESSFUL; 0 grep matches.

---

## Task 5: Update DocumentMetadataRepositoryTest

**Files:** Modify `app/src/test/java/com/paperless/scanner/data/repository/DocumentMetadataRepositoryTest.kt`

- [ ] **Step 5.1: Update setup() — replace pendingDao + serverHealth mocks with sync mock**

In the test class fields, REMOVE:
```kotlin
private lateinit var pendingDao: PendingChangeDao
private lateinit var serverHealth: ServerHealthMonitor
```

ADD:
```kotlin
private lateinit var sync: DocumentSyncRepository
```

In `setup()`, REMOVE:
```kotlin
pendingDao = mockk(relaxed = true)
serverHealth = mockk(relaxed = true)
every { serverHealth.isServerReachable } returns MutableStateFlow(true)
```

ADD:
```kotlin
sync = mockk(relaxed = true)
// Default: executeOrQueue runs the online lambda (online happy path).
coEvery { sync.executeOrQueue<Document>(any(), any()) } coAnswers {
    Result.success(firstArg<suspend () -> Document>().invoke())
}
```

Update the `repo = DocumentMetadataRepository(...)` call: drop `pendingDao` + `serverHealth`, add `sync`.

Add imports:
```kotlin
import com.paperless.scanner.data.repository.DocumentSyncRepository
```

Remove imports for `PendingChangeDao`, `ServerHealthMonitor`, `MutableStateFlow` (if not used elsewhere).

- [ ] **Step 5.2: Migrate updateDocument tests**

For tests that previously asserted `coVerify { pendingDao.insert(any()) }` (offline branches): change setup to make `executeOrQueue` run the OFFLINE lambda:
```kotlin
coEvery { sync.executeOrQueue<Document>(any(), any()) } coAnswers {
    Result.success(secondArg<suspend () -> Document>().invoke())
}
```

Then assert `coVerify { sync.queueDocumentUpdate(documentId = any(), payload = any()) }` instead.

For tests that captured a `slot<PendingChange>()`: switch to capturing the `payload` argument:
```kotlin
val payloadSlot = slot<DocumentSyncRepository.DocumentUpdatePayload>()
coEvery { sync.queueDocumentUpdate(any(), capture(payloadSlot)) } returns Unit
// ...
assertEquals("New title", payloadSlot.captured.title)
```

Online tests are unchanged — `executeOrQueue` runs the online lambda by default per the setup() stub.

- [ ] **Step 5.3: Run DocumentMetadataRepositoryTest**

```bash
./gradlew :app:testReleaseUnitTest --tests "*DocumentMetadataRepositoryTest*" --no-daemon
```
Expected: 12/12 PASS.

---

## Task 6: Update TrashRepositoryTest

**Files:** Modify `app/src/test/java/com/paperless/scanner/data/repository/TrashRepositoryTest.kt`

- [ ] **Step 6.1: Update setup() — replace pendingDao mock with sync mock**

REMOVE field + setup for `pendingChangeDao`. ADD `sync: DocumentSyncRepository` mock.

In setup(), default-stub `executeOrQueue` to run the online lambda:
```kotlin
sync = mockk(relaxed = true)
coEvery { sync.executeOrQueue<Unit>(any(), any()) } coAnswers {
    Result.success(firstArg<suspend () -> Unit>().invoke())
}
```

Update `repo = TrashRepository(...)` call: drop `pendingChangeDao`, add `sync`.

- [ ] **Step 6.2: Migrate offline-path tests**

Tests that previously verified `pendingChangeDao.insert(...)` (offline branches: `deleteDocument offline`, `restoreDocuments offline`, `permanentlyDeleteDocuments offline + online branches`):

- Switch the `executeOrQueue` stub to run the OFFLINE lambda for those tests:
```kotlin
coEvery { sync.executeOrQueue<Unit>(any(), any()) } coAnswers {
    Result.success(secondArg<suspend () -> Unit>().invoke())
}
```

- Assert sync helper invocations instead of `pendingChangeDao.insert`:
  - `deleteDocument offline` → `coVerify { sync.queueDocumentDelete(7) }`
  - `restoreDocuments offline` → `coVerify { sync.queueTrashAction(listOf(1, 2, 3), DocumentSyncRepository.TrashAction.RESTORE) }`
  - `permanentlyDeleteDocuments offline branch` → `coVerify { sync.queueTrashAction(listOf(7), DocumentSyncRepository.TrashAction.PERMANENT_DELETE) }`

- **PRESERVE the `coVerifyOrder` test from Phase 3.1** (`deleteDocument online happy path soft-deletes locally then API succeeds` and `deleteDocument offline writes PendingChange and softDeletes`). The orderings:
  - Online: `cachedDocumentDao.softDelete(...) → cachedTaskDao.acknowledgeTasksForDocument(...) → api.deleteDocument(...)`
  - Offline: `sync.queueDocumentDelete(...) → cachedTaskDao.acknowledgeTasksForDocument(...) → cachedDocumentDao.softDelete(...)`
- The offline `coVerifyOrder` switches `pendingChangeDao.insert` → `sync.queueDocumentDelete`. Update the assertion accordingly.

- [ ] **Step 6.3: Run TrashRepositoryTest**

```bash
./gradlew :app:testReleaseUnitTest --tests "*TrashRepositoryTest*" --no-daemon
```
Expected: 15/15 PASS.

---

## Task 7: Update DocumentRepositoryTest

**Files:** Modify `app/src/test/java/com/paperless/scanner/data/repository/DocumentRepositoryTest.kt`

- [ ] **Step 7.1: Update DocumentMetadataRepository + TrashRepository construction in setup()**

Both repos lose `pendingChangeDao` (and Metadata loses `serverHealthMonitor`) and gain `sync: DocumentSyncRepository`. The `setup()` block constructs these inline.

Add a `documentSyncRepository` instance before constructing them:
```kotlin
val documentSyncRepository = DocumentSyncRepository(
    pendingChangeDao = pendingChangeDao,
    serverHealthMonitor = serverHealthMonitor,
    gson = gson,
)
```

(The fields `pendingChangeDao`, `serverHealthMonitor`, and `gson` should already exist on the test class for constructing `DocumentRepository`. If `gson` is missing, add `private lateinit var gson: Gson` + `gson = Gson()` in setup().)

Update `metadataRepository = DocumentMetadataRepository(...)` call: drop `pendingChangeDao` + `serverHealthMonitor`, add `documentSyncRepository`.
Update `trashRepository = TrashRepository(...)` call: drop `pendingChangeDao`, add `documentSyncRepository`.

The DocumentRepository constructor itself stays unchanged (still 19 args including the now-unused `pendingChangeDao` and `serverHealthMonitor`). Phase-5 cleanup will remove those.

- [ ] **Step 7.2: Run DocumentRepositoryTest**

```bash
./gradlew :app:testReleaseUnitTest --tests "*DocumentRepositoryTest*" --no-daemon
```
Expected: BUILD SUCCESSFUL.

---

## Task 8: Write DocumentSyncRepository tests

**Files:** Create `app/src/test/java/com/paperless/scanner/data/repository/DocumentSyncRepositoryTest.kt`

- [ ] **Step 8.1: Write the test file**

```kotlin
package com.paperless.scanner.data.repository

import com.google.gson.Gson
import com.paperless.scanner.data.api.PaperlessException
import com.paperless.scanner.data.database.dao.PendingChangeDao
import com.paperless.scanner.data.database.entities.PendingChange
import com.paperless.scanner.data.health.ServerHealthMonitor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException

class DocumentSyncRepositoryTest {

    private lateinit var pendingChangeDao: PendingChangeDao
    private lateinit var serverHealthMonitor: ServerHealthMonitor
    private lateinit var gson: Gson
    private lateinit var repo: DocumentSyncRepository

    @Before
    fun setup() {
        pendingChangeDao = mockk(relaxed = true)
        serverHealthMonitor = mockk(relaxed = true)
        gson = Gson()
        repo = DocumentSyncRepository(pendingChangeDao, serverHealthMonitor, gson)
    }

    // ===== queueDocumentUpdate =====

    @Test
    fun `queueDocumentUpdate writes PendingChange with gson-serialized payload`() = runTest {
        val slot = slot<PendingChange>()
        coEvery { pendingChangeDao.insert(capture(slot)) } returns 1L

        repo.queueDocumentUpdate(
            42,
            DocumentSyncRepository.DocumentUpdatePayload(title = "hello", tags = listOf(1, 2)),
        )

        val captured = slot.captured
        assertEquals("document", captured.entityType)
        assertEquals(42, captured.entityId)
        assertEquals("update", captured.changeType)
        // Verify JSON shape (gson default omits nulls, uses property names as keys)
        assertTrue(captured.changeData.contains("\"title\":\"hello\""))
        assertTrue(captured.changeData.contains("\"tags\":[1,2]"))
        // Null fields are omitted
        assertFalse(captured.changeData.contains("correspondent"))
        assertFalse(captured.changeData.contains("documentType"))
    }

    @Test
    fun `queueDocumentUpdate handles title with embedded quotes safely`() = runTest {
        // BUG FIX VERIFICATION (#169): the previous buildString impl produced
        // invalid JSON for titles containing `"`. gson.toJson escapes correctly.
        val slot = slot<PendingChange>()
        coEvery { pendingChangeDao.insert(capture(slot)) } returns 1L

        repo.queueDocumentUpdate(
            7,
            DocumentSyncRepository.DocumentUpdatePayload(title = """He said "hi""""),
        )

        // The captured changeData must be valid JSON that round-trips back to the
        // original title via the same parser SyncManager uses.
        val parsed = gson.fromJson(slot.captured.changeData, Map::class.java)
        @Suppress("UNCHECKED_CAST")
        val parsedMap = parsed as Map<String, Any?>
        assertEquals("""He said "hi"""", parsedMap["title"])
    }

    @Test
    fun `queueDocumentUpdate omits null payload fields from JSON`() = runTest {
        val slot = slot<PendingChange>()
        coEvery { pendingChangeDao.insert(capture(slot)) } returns 1L

        repo.queueDocumentUpdate(
            1,
            DocumentSyncRepository.DocumentUpdatePayload(title = "only title"),
        )

        val json = slot.captured.changeData
        assertTrue(json.contains("title"))
        assertFalse("tags must be omitted", json.contains("tags"))
        assertFalse("correspondent must be omitted", json.contains("correspondent"))
        assertFalse("documentType must be omitted", json.contains("documentType"))
        assertFalse("archiveSerialNumber must be omitted", json.contains("archiveSerialNumber"))
        assertFalse("created must be omitted", json.contains("created"))
    }

    // ===== queueDocumentDelete =====

    @Test
    fun `queueDocumentDelete writes correct PendingChange`() = runTest {
        val slot = slot<PendingChange>()
        coEvery { pendingChangeDao.insert(capture(slot)) } returns 1L

        repo.queueDocumentDelete(99)

        assertEquals("document", slot.captured.entityType)
        assertEquals(99, slot.captured.entityId)
        assertEquals("delete", slot.captured.changeType)
        assertEquals("{}", slot.captured.changeData)
    }

    // ===== queueTrashAction =====

    @Test
    fun `queueTrashAction RESTORE writes one PendingChange per id with changeType restore`() = runTest {
        repo.queueTrashAction(listOf(1, 2, 3), DocumentSyncRepository.TrashAction.RESTORE)

        coVerify(exactly = 1) { pendingChangeDao.insert(match { it.entityId == 1 && it.changeType == "restore" && it.entityType == "trash" }) }
        coVerify(exactly = 1) { pendingChangeDao.insert(match { it.entityId == 2 && it.changeType == "restore" }) }
        coVerify(exactly = 1) { pendingChangeDao.insert(match { it.entityId == 3 && it.changeType == "restore" }) }
    }

    @Test
    fun `queueTrashAction PERMANENT_DELETE writes one PendingChange per id with changeType delete`() = runTest {
        repo.queueTrashAction(listOf(7, 8), DocumentSyncRepository.TrashAction.PERMANENT_DELETE)

        coVerify(exactly = 1) { pendingChangeDao.insert(match { it.entityId == 7 && it.changeType == "delete" && it.entityType == "trash" }) }
        coVerify(exactly = 1) { pendingChangeDao.insert(match { it.entityId == 8 && it.changeType == "delete" }) }
    }

    // ===== executeOrQueue =====

    @Test
    fun `executeOrQueue online happy path returns Result success of online value`() = runTest {
        every { serverHealthMonitor.isServerReachable } returns MutableStateFlow(true)

        val result = repo.executeOrQueue(
            online = { "online-result" },
            offlineQueueAndOptimistic = { "offline-result" },
        )

        assertTrue(result.isSuccess)
        assertEquals("online-result", result.getOrNull())
    }

    @Test
    fun `executeOrQueue offline runs offlineQueueAndOptimistic path`() = runTest {
        every { serverHealthMonitor.isServerReachable } returns MutableStateFlow(false)

        val result = repo.executeOrQueue(
            online = { "online-result" },
            offlineQueueAndOptimistic = { "offline-result" },
        )

        assertTrue(result.isSuccess)
        assertEquals("offline-result", result.getOrNull())
    }

    @Test
    fun `executeOrQueue online IOException falls back to offlineQueueAndOptimistic`() = runTest {
        // BUG FIX VERIFICATION (#169 TOCTOU): previously, a mid-flight network drop
        // (state was true at entry, IOException thrown during API call) caused
        // the user's edit to be lost. Now, IOException triggers fallback to
        // the offline-queue path.
        every { serverHealthMonitor.isServerReachable } returns MutableStateFlow(true)

        val result = repo.executeOrQueue<String>(
            online = { throw IOException("network drop") },
            offlineQueueAndOptimistic = { "fallback-result" },
        )

        assertTrue(result.isSuccess)
        assertEquals("fallback-result", result.getOrNull())
    }

    @Test
    fun `executeOrQueue online HttpException 4xx returns failure without fallback`() = runTest {
        every { serverHealthMonitor.isServerReachable } returns MutableStateFlow(true)
        val errorBody = "{}".toResponseBody("application/json".toMediaTypeOrNull())
        var offlineCalled = false

        val result = repo.executeOrQueue<String>(
            online = { throw HttpException(Response.error<Any>(403, errorBody)) },
            offlineQueueAndOptimistic = { offlineCalled = true; "should-not-run" },
        )

        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull())
        assertTrue(result.exceptionOrNull() is PaperlessException)
        assertFalse("offline path must NOT run for HttpException", offlineCalled)
    }
}
```

- [ ] **Step 8.2: Run new tests**

```bash
./gradlew :app:testReleaseUnitTest --tests "*DocumentSyncRepositoryTest*" --no-daemon
```
Expected: 10/10 pass.

---

## Task 9: validate-ci.sh

- [ ] **Step 9.1**

```bash
cd "E:/Git/paperless client" && ./scripts/validate-ci.sh
```
Expected: green.

---

## Task 10: Commit

- [ ] **Step 10.1: Stage and commit**

```bash
cd "E:/Git/paperless client" && \
  git add app/src/main/java/com/paperless/scanner/data/repository/DocumentSyncRepository.kt \
          app/src/test/java/com/paperless/scanner/data/repository/DocumentSyncRepositoryTest.kt \
          app/src/main/java/com/paperless/scanner/data/repository/DocumentMetadataRepository.kt \
          app/src/main/java/com/paperless/scanner/data/repository/TrashRepository.kt \
          app/src/test/java/com/paperless/scanner/data/repository/DocumentMetadataRepositoryTest.kt \
          app/src/test/java/com/paperless/scanner/data/repository/TrashRepositoryTest.kt \
          app/src/test/java/com/paperless/scanner/data/repository/DocumentRepositoryTest.kt && \
  git commit -m "$(cat <<'EOF'
refactor: extract DocumentSyncRepository (Phase 3.3 of #51)

Centralizes the offline-queue + serverHealth pattern that lived inline
as Phase-3.3 debt across DocumentMetadataRepository (1 site) and
TrashRepository (3 sites). Fixes 2 production bugs as part of the move:

- JSON injection in updateDocument PendingChange payload — buildString
  with raw "$it" interpolation produced invalid JSON for titles with
  embedded quotes. Now uses gson.toJson(DocumentUpdatePayload) which
  matches SyncManager.pushDocumentChange's parser exactly.
- TOCTOU race on serverHealthMonitor.isServerReachable — was read inline
  without IOException fallback; if state flipped between check and API
  call, the user's edit was lost rather than queued. executeOrQueue
  snapshots once and recovers via IOException-fallback.

DocumentRepository façade is unchanged. Caller code (ViewModels,
SyncManager) is untouched. SyncManager's parser is unchanged — gson
output matches the camelCase keys the parser already reads.

10 new tests verify both bug fixes (JSON-injection title with embedded
quotes; IOException fallback). Existing DocumentMetadata + Trash tests
are migrated to mock DocumentSyncRepository instead of pendingChangeDao.
Phase-3.1 coVerifyOrder cascade-ordering tests preserved.

After this PR: zero `// PHASE-3.3:` markers and zero `// FIXME (#169):`
markers remain in the codebase.

Closes #169

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

Pre-commit hook runs automatically. Never bypass.

---

## Task 11: Manual on-device smoke

- [ ] **Step 11.1: Build and install**

```bash
./gradlew :app:installDebug --no-daemon
```

- [ ] **Step 11.2: Smoke test critical flows**

On-device, verify:

1. **Online update with quote in title** (BUG FIX VERIFICATION): edit a document, set title to `He said "hi"`, save. Server accepts; reload shows correct title.
2. **Offline update with quote in title** (BUG FIX VERIFICATION): airplane mode → edit → set title `He said "hi"` → save (optimistic update visible) → reconnect → SyncManager replays → server gets correct value (no JSON parse error in logcat).
3. **Mid-flight drop simulation** (TOCTOU FIX): start an edit while online → simulate network drop (toggle airplane mid-save, or use slow network) → user-visible: optimistic UI shows the change; reconnect → SyncManager replays.
4. **Trash cascade**: soft-delete a document → server sync confirms; offline soft-delete + reconnect; restore single + bulk; permanently-delete single + bulk.
5. **No new Crashlytics errors** on any of the above.

If any flow breaks, do NOT push. Diff the migrated bodies against the originals.

---

## Task 12: Push and open PR

- [ ] **Step 12.1: Push**

```bash
cd "E:/Git/paperless client" && git push -u origin refactor/51-extract-document-sync
```

- [ ] **Step 12.2: Open the PR**

```bash
gh pr create \
  --base main \
  --head refactor/51-extract-document-sync \
  --title "refactor: extract DocumentSyncRepository (Phase 3.3 of #51)" \
  --body "$(cat <<'EOF'
## Summary
- Extracts `DocumentSyncRepository` and centralizes the offline-queue + serverHealth pattern that lived inline as Phase-3.3 debt across DocumentMetadataRepository (1 site) and TrashRepository (3 sites).
- **Fixes 2 production bugs** as part of the move:
  - **JSON injection** in `updateDocument` offline branch — `buildString` with raw `\"$it\"` interpolation produced invalid JSON for titles containing embedded `\"`. Now uses `gson.toJson(DocumentUpdatePayload)`.
  - **TOCTOU race** on `serverHealthMonitor` — previously read inline without IOException fallback; if state flipped between check and API call, the user's edit was lost. `executeOrQueue` now snapshots + falls back via IOException.
- After this PR: **zero `// PHASE-3.3:` markers** and **zero `// FIXME (#169):` markers** in the codebase.

## Phase
Phase 3.3 of #51 — DocumentRepository God-class refactor. **HIGHEST RISK phase** per master plan. See [spec](docs/superpowers/specs/2026-05-06-document-repository-refactor-phase3-3-design.md).

## What changed
- New `DocumentSyncRepository` (~150 LOC) with `executeOrQueue<T>(online, offlineQueueAndOptimistic): Result<T>` and 3 typed queue helpers (`queueDocumentUpdate`, `queueDocumentDelete`, `queueTrashAction`).
- `DocumentMetadataRepository`: ctor `pendingChangeDao` + `serverHealthMonitor` removed; `sync` added. `updateDocument` body wrapped in `executeOrQueue`.
- `TrashRepository`: ctor `pendingChangeDao` removed; `sync` added. 3 mutation bodies (`deleteDocument`, `restoreDocuments`, `permanentlyDeleteDocuments`) wrapped in `executeOrQueue`.
- `DocumentRepository` façade ctor: **unchanged** (still 19 deps; `pendingChangeDao` and `serverHealthMonitor` are now unused-but-injected — Phase-5 cleanup target).

## SyncManager compatibility
SyncManager's `pushDocumentChange` parser uses `gson.fromJson(Map::class.java)` reading camelCase keys (`title`, `tags`, `correspondent`, `documentType`, `archiveSerialNumber`, `created`). `DocumentUpdatePayload` property names match exactly; Gson default omits nulls. No SyncManager changes needed.

## Tests
- ✅ New `DocumentSyncRepositoryTest` adds 10 cases including:
  - **Test #2**: `queueDocumentUpdate handles title with embedded quotes safely` — verifies the JSON-injection fix end-to-end (round-trips through gson.fromJson).
  - **Test #9**: `executeOrQueue online IOException falls back to offlineQueueAndOptimistic` — verifies the TOCTOU fix.
  - **Test #10**: `executeOrQueue online HttpException 4xx returns failure without fallback` — verifies HttpException does NOT trigger queue (correct heuristic).
- ✅ Existing `DocumentMetadataRepositoryTest` (12) and `TrashRepositoryTest` (15) migrated to mock `DocumentSyncRepository` instead of `PendingChangeDao` + `ServerHealthMonitor`.
- ✅ Phase-3.1 `coVerifyOrder` cascade-ordering tests preserved.
- ✅ DocumentRepositoryTest unchanged externally.

## Test plan
- [x] `./scripts/validate-ci.sh` (RELEASE variants) green locally
- [ ] **On-device smoke** (CRITICAL — bug-fix verification):
  - Online + offline update with title containing `\"`
  - Mid-flight network drop during update (TOCTOU scenario)
  - Trash cascade: soft-delete, restore, permanently-delete in both modes

Closes #169
EOF
)"
```

- [ ] **Step 12.3: Address CodeRabbit and merge**

Action small ≤5 LOC inline real bugs. Skip Phase-5-territory findings. Squash-merge after CI green and review approved:
```bash
gh pr merge --squash --delete-branch
```

---

## Task 13: Post-merge — update memory

- [ ] **Step 13.1: Sync local main**

```bash
cd "E:/Git/paperless client" && git checkout main && git fetch origin && git rebase origin/main
```
Use `git rebase --skip` for any add/add doc conflicts.

- [ ] **Step 13.2: Update memory file**

Rename `C:\Users\marcu\.claude\projects\E--Git-paperless-client\memory\issue_51_phase4_complete.md` → `issue_51_phase3_3_complete.md` (or update title in-place).

Add Phase 3.3 row to State table:
```
| <PR#> | #169 | DocumentSyncRepository (Phase 3.3) | ~150 | 10 | <merge SHA> |
```

Update DocumentRepository LOC progression: `440 (P4) → ~440 (P3.3 — façade unchanged)`. (Phase 3.3 is internal; DocumentRepository LOC essentially unchanged — DocumentMetadata and Trash both shrink slightly.)

**CRITICAL update:** mark Phase-3.3 debt as **0 markers**. Update the section that tracked the 5 inline `// PHASE-3.3:` markers — they're all gone.

Update "How to resume" to point exclusively at Phase 5 (#171) as the only remaining phase.

- [ ] **Step 13.3: Update MEMORY.md pointer**

```
- [Issue #51 DocumentRepository refactor — Phase 3.3 done, only Phase 5 remains](issue_51_phase3_3_complete.md) — resume: brainstorm Phase 5 (#171 façade cleanup + ViewModel migration) — only remaining sub-issue
```

---

## Acceptance Criteria (from spec §11)

- [ ] 1 new file `DocumentSyncRepository.kt` with `@Inject constructor` + `@Singleton`
- [ ] 4 inline `// PHASE-3.3:` markers and 2 `// FIXME (#169):` markers removed (final count: 0)
- [ ] DocumentMetadataRepository.updateDocument migrated; `pendingChangeDao` + `serverHealthMonitor` deps removed; `sync` added
- [ ] TrashRepository: 3 mutations migrated; `pendingChangeDao` removed; `sync` added
- [ ] DocumentRepository ctor unchanged (still 19 deps)
- [ ] 10 new tests in `DocumentSyncRepositoryTest.kt`, all green
- [ ] **Test #2 (JSON-injection title with `"`) and Test #9 (IOException fallback) BOTH pass — bug-fix verification**
- [ ] Existing DocumentMetadataRepositoryTest + TrashRepositoryTest green with sync-mock migration
- [ ] Phase-3.1 `coVerifyOrder` cascade tests in TrashRepositoryTest preserved + green
- [ ] DocumentRepositoryTest green (no API change)
- [ ] `./scripts/validate-ci.sh` green before push
- [ ] CodeRabbit findings actioned or skipped
- [ ] Sub-issue #169 merged via "Closes #169"
- [ ] **Manual on-device smoke per Task 11.2 completed** (especially mid-flight drop scenario)
- [ ] Memory file + MEMORY.md pointer updated; Phase-3.3 debt count = 0
