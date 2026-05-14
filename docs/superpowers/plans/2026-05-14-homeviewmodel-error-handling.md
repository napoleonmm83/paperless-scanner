# HomeViewModel Error Handling Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Standardize error handling in HomeViewModel so all 9 observe* flows and action functions surface failures through a single `errorState: StateFlow<HomeError?>`, making silent failures visible.

**Architecture:** Introduce a `HomeError` sealed class and a `Flow<T>.asUiResult()` extension that wraps upstream exceptions into `Result.failure(e)`. All 9 `observe*` functions collect through this extension and emit `HomeError.LoadFailed` on failure. Action functions (`deleteRecentDocument`, `undoDelete`, `loadUntaggedDocuments`) emit `HomeError.ActionFailed`. `HomeScreen` observes `errorState` and shows a Snackbar.

**Tech Stack:** Kotlin Flow, MockK, Turbine, `kotlinx.coroutines.test`, Compose `SnackbarHostState`

**Spec:** `docs/superpowers/specs/2026-05-14-homeviewmodel-error-handling-design.md`

**Issue:** https://github.com/napoleonmm83/paperless-scanner/issues/85

---

## File Map

| File | Action | Responsibility |
|---|---|---|
| `app/src/main/java/com/paperless/scanner/ui/screens/home/HomeError.kt` | **Create** | Sealed class for all HomeViewModel errors |
| `app/src/main/java/com/paperless/scanner/util/UiResultExtensions.kt` | **Create** | `Flow<T>.asUiResult()` shared extension |
| `app/src/main/java/com/paperless/scanner/ui/screens/home/HomeViewModel.kt` | **Modify** | Remove dead `error: String?`, add `_errorState`, refactor 9 observe* + 3 action fns |
| `app/src/main/java/com/paperless/scanner/ui/screens/home/HomeScreen.kt` | **Modify** | Add error Snackbar LaunchedEffect |
| `app/src/main/res/values/strings.xml` | **Modify** | Add `error_load_data` and `error_action_failed` strings |
| `app/src/test/java/com/paperless/scanner/ui/screens/home/HomeViewModelTest.kt` | **Modify** | Update @Before, add 4 error tests, fix compile error |

---

## Task 1: Create `HomeError.kt`

**Files:**
- Create: `app/src/main/java/com/paperless/scanner/ui/screens/home/HomeError.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.paperless.scanner.ui.screens.home

sealed class HomeError {
    data class LoadFailed(val source: String, val cause: Throwable) : HomeError()
    data class ActionFailed(val action: String, val cause: Throwable) : HomeError()
}
```

- [ ] **Step 2: Verify compilation**

```bash
cd "E:\Git\paperless client" && ./gradlew :app:compileDebugKotlin --no-daemon 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add "app/src/main/java/com/paperless/scanner/ui/screens/home/HomeError.kt"
git commit -m "feat: add HomeError sealed class for centralized error handling (#85)"
```

---

## Task 2: Create `UiResultExtensions.kt`

**Files:**
- Create: `app/src/main/java/com/paperless/scanner/util/UiResultExtensions.kt`

- [ ] **Step 1: Create the file**

```kotlin
package com.paperless.scanner.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * Wraps each upstream emission in Result.success(). If the upstream Flow
 * throws, emits Result.failure(e) instead of propagating the exception.
 * CancellationException is always re-thrown so coroutine cancellation works correctly.
 */
fun <T> Flow<T>.asUiResult(): Flow<Result<T>> =
    map { Result.success(it) }
        .catch { e ->
            if (e is CancellationException) throw e
            emit(Result.failure(e))
        }
```

- [ ] **Step 2: Verify compilation**

```bash
cd "E:\Git\paperless client" && ./gradlew :app:compileDebugKotlin --no-daemon 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add "app/src/main/java/com/paperless/scanner/util/UiResultExtensions.kt"
git commit -m "feat: add Flow.asUiResult() extension for shared error wrapping (#85)"
```

---

## Task 3: Update `HomeViewModel.kt` — Add `_errorState`, Remove Dead `error` Field

**Files:**
- Modify: `app/src/main/java/com/paperless/scanner/ui/screens/home/HomeViewModel.kt`

This task adds the error StateFlow and removes the unused `error: String?` from HomeUiState. The observe* and action functions are NOT changed yet.

- [ ] **Step 1: Remove `error: String?` from `HomeUiState`**

Find this in `HomeUiState` (around line 104):
```kotlin
    val isLoading: Boolean = true,
    val error: String? = null
```

Replace with:
```kotlin
    val isLoading: Boolean = true
```

- [ ] **Step 2: Add `_errorState` and `clearHomeError()` to `HomeViewModel`**

Find the companion object + private fields area (around line 178, after `private var wasOffline = false`):
```kotlin
    private var wasOffline = false
```

Add after it:
```kotlin
    private var wasOffline = false

    private val _errorState = MutableStateFlow<HomeError?>(null)
    val errorState: StateFlow<HomeError?> = _errorState.asStateFlow()
```

Find `fun clearError()` in the ViewModel and replace it:
```kotlin
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }
```
Replace with:
```kotlin
    fun clearHomeError() {
        _errorState.value = null
    }
```

- [ ] **Step 3: Verify compilation**

```bash
cd "E:\Git\paperless client" && ./gradlew :app:compileDebugKotlin --no-daemon 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL` (there may be a warning that `clearError` is now gone — if HomeScreen calls `clearError()`, that will also fail and must be fixed: search HomeScreen for `clearError()` and remove/replace.)

Run:
```bash
grep -rn "clearError\|uiState.error\b" app/src/main/ --include="*.kt"
```

If any results appear, fix them:
- `viewModel.clearError()` → `viewModel.clearHomeError()`  
- `uiState.error` → remove the reference (it no longer exists)

- [ ] **Step 4: Commit**

```bash
git add "app/src/main/java/com/paperless/scanner/ui/screens/home/HomeViewModel.kt"
git commit -m "refactor: add _errorState StateFlow, remove dead uiState.error field (#85)"
```

---

## Task 4: Update `HomeViewModelTest.kt` — Fix Compile Error + Add Observe Mocks to `@Before`

**Files:**
- Modify: `app/src/test/java/com/paperless/scanner/ui/screens/home/HomeViewModelTest.kt`

- [ ] **Step 1: Add missing imports**

At the top of the test file, add these imports if not already present:
```kotlin
import kotlinx.coroutines.flow.flow
import com.paperless.scanner.ui.screens.home.HomeError
```

- [ ] **Step 2: Fix the compile error — update the existing test that references `initialState.error`**

Find the test `initial uiState has correct defaults`:
```kotlin
    @Test
    fun `initial uiState has correct defaults`() = runTest {
        val viewModel = createViewModel()
        
        // Check initial state before loading completes
        val initialState = viewModel.uiState.value
        assertTrue(initialState.isLoading)
        assertNull(initialState.error)
    }
```

Replace `assertNull(initialState.error)` with `assertNull(viewModel.errorState.value)`:
```kotlin
    @Test
    fun `initial uiState has correct defaults`() = runTest {
        val viewModel = createViewModel()
        
        val initialState = viewModel.uiState.value
        assertTrue(initialState.isLoading)
        assertNull(viewModel.errorState.value)
    }
```

- [ ] **Step 3: Add explicit observe flow mocks to `@Before`**

In the `setup()` function, after the existing `every { tagRepository.observeTags() } returns MutableStateFlow(emptyList())` line, add:
```kotlin
        every { tagRepository.observeTags() } returns MutableStateFlow(emptyList())
        every { documentListRepository.observeDocuments(page = 1, pageSize = 5) } returns MutableStateFlow(emptyList())
        every { taskRepository.observeUnacknowledgedTasksExcludingDeleted() } returns MutableStateFlow(emptyList())
        every { documentCountRepository.observeUntaggedDocumentsCount() } returns MutableStateFlow(0)
        every { trashRepository.observeTrashedDocumentsCount() } returns MutableStateFlow(0)
        every { trashRepository.observeOldestDeletedTimestamp() } returns MutableStateFlow(null)
        every { syncHistoryRepository.observeFailedCount() } returns MutableStateFlow(0)
```

Note: `uploadQueueRepository.pendingCount` is already mocked as `MutableStateFlow(0)` — no change needed.

- [ ] **Step 4: Verify the existing tests still compile and pass**

```bash
cd "E:\Git\paperless client" && ./gradlew :app:testDebugUnitTest --tests "com.paperless.scanner.ui.screens.home.HomeViewModelTest" --no-daemon 2>&1 | tail -15
```

Expected: All existing tests pass.

- [ ] **Step 5: Commit**

```bash
git add "app/src/test/java/com/paperless/scanner/ui/screens/home/HomeViewModelTest.kt"
git commit -m "test: fix HomeViewModelTest compile error + add explicit observe mocks (#85)"
```

---

## Task 5: Write Failing Tests for Error Propagation

**Files:**
- Modify: `app/src/test/java/com/paperless/scanner/ui/screens/home/HomeViewModelTest.kt`

These tests will FAIL until Task 6 implements the `asUiResult()` wiring.

- [ ] **Step 1: Add the 4 error tests**

Add a new section at the end of `HomeViewModelTest`:

```kotlin
    // ==================== Error Handling (Issue #85) ====================

    @Test
    fun `observeTagsReactively sets LoadFailed on errorState when tags flow throws`() = runTest {
        every { tagRepository.observeTags() } returns flow { throw RuntimeException("DB failure") }

        val vm = createViewModel()
        advanceUntilIdle()

        val error = vm.errorState.value
        assertNotNull(error)
        assertTrue(error is HomeError.LoadFailed)
        assertEquals("tags", (error as HomeError.LoadFailed).source)
    }

    @Test
    fun `observeRecentDocumentsReactively sets LoadFailed when documents flow throws`() = runTest {
        every { documentListRepository.observeDocuments(page = 1, pageSize = 5) } returns
            flow { throw RuntimeException("DB failure") }

        val vm = createViewModel()
        advanceUntilIdle()

        val error = vm.errorState.value
        assertNotNull(error)
        assertTrue(error is HomeError.LoadFailed)
        assertEquals("recentDocuments", (error as HomeError.LoadFailed).source)
    }

    @Test
    fun `deleteRecentDocument sets ActionFailed on errorState when delete fails`() = runTest {
        coEvery { trashRepository.deleteDocument(any()) } returns
            Result.failure(RuntimeException("Network error"))

        val vm = createViewModel()
        advanceUntilIdle()

        vm.deleteRecentDocument(documentId = 1, documentTitle = "Test Doc")
        advanceUntilIdle()

        val error = vm.errorState.value
        assertNotNull(error)
        assertTrue(error is HomeError.ActionFailed)
        assertEquals("deleteDocument", (error as HomeError.ActionFailed).action)
    }

    @Test
    fun `clearHomeError resets errorState to null`() = runTest {
        every { tagRepository.observeTags() } returns flow { throw RuntimeException("DB failure") }

        val vm = createViewModel()
        advanceUntilIdle()

        assertNotNull(vm.errorState.value)
        vm.clearHomeError()
        assertNull(vm.errorState.value)
    }
```

- [ ] **Step 2: Run the new tests — confirm they FAIL**

```bash
cd "E:\Git\paperless client" && ./gradlew :app:testDebugUnitTest --tests "com.paperless.scanner.ui.screens.home.HomeViewModelTest.observeTagsReactively*" --no-daemon 2>&1 | tail -15
```

Expected: **FAIL** — `errorState.value` is null because observe* still has no error handling.

- [ ] **Step 3: Commit the failing tests**

```bash
git add "app/src/test/java/com/paperless/scanner/ui/screens/home/HomeViewModelTest.kt"
git commit -m "test: add failing error propagation tests for Issue #85"
```

---

## Task 6: Refactor 9 `observe*` Functions — Add `asUiResult()`

**Files:**
- Modify: `app/src/main/java/com/paperless/scanner/ui/screens/home/HomeViewModel.kt`

Add the import at the top of HomeViewModel:
```kotlin
import com.paperless.scanner.util.asUiResult
```

Apply the pattern to each of the 9 observe functions. Each one follows the same shape.

- [ ] **Step 1: Refactor `observeTagsReactively()`**

Find:
```kotlin
    private fun observeTagsReactively() {
        viewModelScope.launch {
            tagRepository.observeTags().collect { tags ->
                _tagMap.value = tags.associateBy { it.id }
            }
        }
    }
```

Replace with:
```kotlin
    private fun observeTagsReactively() {
        viewModelScope.launch {
            tagRepository.observeTags()
                .asUiResult()
                .collect { result ->
                    result.onSuccess { tags ->
                        _tagMap.value = tags.associateBy { it.id }
                    }.onFailure { e ->
                        _errorState.value = HomeError.LoadFailed("tags", e)
                    }
                }
        }
    }
```

- [ ] **Step 2: Refactor `observeRecentDocumentsReactively()`**

Find the function starting at the `private fun observeRecentDocumentsReactively()` line. The collect body receives `recentDocs: List<RecentDocument>`.

Replace with:
```kotlin
    private fun observeRecentDocumentsReactively() {
        viewModelScope.launch {
            combine(
                documentListRepository.observeDocuments(page = 1, pageSize = 5),
                _tagMap
            ) { documents, currentTagMap ->
                documents.map { doc ->
                    val firstTagId = doc.tags.firstOrNull()
                    val tag = firstTagId?.let { currentTagMap[it] }
                    RecentDocument(
                        id = doc.id,
                        title = doc.title,
                        timeAgo = formatTimeAgo(doc.added),
                        tagName = tag?.name,
                        tagColor = tag?.color?.let { parseColorToLong(it) }
                    )
                }
            }
                .asUiResult()
                .collect { result ->
                    result.onSuccess { recentDocs ->
                        _uiState.update { it.copy(recentDocuments = recentDocs, isLoading = false) }
                    }.onFailure { e ->
                        _errorState.value = HomeError.LoadFailed("recentDocuments", e)
                    }
                }
        }
    }
```

- [ ] **Step 3: Refactor `observeProcessingTasksReactively()`**

Replace the entire function body. The collect body currently maps tasks and calls `syncCompletedDocuments` — keep all that logic inside `onSuccess`:

```kotlin
    private fun observeProcessingTasksReactively() {
        viewModelScope.launch {
            taskRepository.observeUnacknowledgedTasksExcludingDeleted()
                .asUiResult()
                .collect { result ->
                    result.onSuccess { tasks ->
                        val processingTasks = tasks
                            .filter { task -> task.taskFileName != null }
                            .map { task ->
                                ProcessingTask(
                                    id = task.id,
                                    taskId = task.taskId,
                                    fileName = task.taskFileName ?: context.getString(R.string.document_unknown),
                                    status = mapTaskStatus(task.status),
                                    timeAgo = formatTimeAgo(task.dateCreated),
                                    resultMessage = task.result,
                                    documentId = task.relatedDocument?.toIntOrNull()
                                )
                            }
                            .sortedByDescending { it.id }

                        val activeTasksCount = processingTasks.count {
                            it.status == TaskStatus.PENDING || it.status == TaskStatus.PROCESSING
                        }

                        val previousTasks = _uiState.value.processingTasks
                        syncCompletedDocuments(previousTasks, processingTasks)

                        _uiState.update { currentState ->
                            currentState.copy(
                                processingTasks = processingTasks,
                                allProcessingTasksCount = activeTasksCount,
                                isLoading = false
                            )
                        }

                        if (activeTasksCount > 0) startTaskPolling() else stopTaskPolling()
                    }.onFailure { e ->
                        _errorState.value = HomeError.LoadFailed("processingTasks", e)
                    }
                }
        }
    }
```

- [ ] **Step 4: Refactor `observeUntaggedCountReactively()`**

```kotlin
    private fun observeUntaggedCountReactively() {
        viewModelScope.launch {
            documentCountRepository.observeUntaggedDocumentsCount()
                .asUiResult()
                .collect { result ->
                    result.onSuccess { count ->
                        _uiState.update { it.copy(untaggedCount = count) }
                    }.onFailure { e ->
                        _errorState.value = HomeError.LoadFailed("untaggedCount", e)
                    }
                }
        }
    }
```

- [ ] **Step 5: Refactor `observeDeletedCountReactively()`**

```kotlin
    private fun observeDeletedCountReactively() {
        viewModelScope.launch {
            trashRepository.observeTrashedDocumentsCount()
                .asUiResult()
                .collect { result ->
                    result.onSuccess { count ->
                        _uiState.update { it.copy(deletedCount = count) }
                    }.onFailure { e ->
                        _errorState.value = HomeError.LoadFailed("deletedCount", e)
                    }
                }
        }
    }
```

- [ ] **Step 6: Refactor `observeOldestDeletedTimestampReactively()`**

```kotlin
    private fun observeOldestDeletedTimestampReactively() {
        viewModelScope.launch {
            trashRepository.observeOldestDeletedTimestamp()
                .asUiResult()
                .collect { result ->
                    result.onSuccess { timestamp ->
                        _uiState.update { it.copy(oldestDeletedTimestamp = timestamp) }
                    }.onFailure { e ->
                        _errorState.value = HomeError.LoadFailed("deletedTimestamp", e)
                    }
                }
        }
    }
```

- [ ] **Step 7: Refactor `observeActiveUploadsCountReactively()`**

```kotlin
    private fun observeActiveUploadsCountReactively() {
        viewModelScope.launch {
            uploadQueueRepository.pendingCount
                .asUiResult()
                .collect { result ->
                    result.onSuccess { count ->
                        _uiState.update { it.copy(activeUploadsCount = count) }
                    }.onFailure { e ->
                        _errorState.value = HomeError.LoadFailed("activeUploads", e)
                    }
                }
        }
    }
```

- [ ] **Step 8: Refactor `observeFailedSyncCountReactively()`**

```kotlin
    private fun observeFailedSyncCountReactively() {
        viewModelScope.launch {
            syncHistoryRepository.observeFailedCount()
                .asUiResult()
                .collect { result ->
                    result.onSuccess { count ->
                        _uiState.update { it.copy(failedSyncCount = count) }
                    }.onFailure { e ->
                        _errorState.value = HomeError.LoadFailed("failedSync", e)
                    }
                }
        }
    }
```

- [ ] **Step 9: Refactor `observePendingUploads()`**

```kotlin
    private fun observePendingUploads() {
        viewModelScope.launch {
            pendingChangesCount
                .asUiResult()
                .collect { result ->
                    result.onSuccess { count ->
                        _uiState.update { currentState ->
                            currentState.copy(stats = currentState.stats.copy(pendingUploads = count))
                        }
                    }.onFailure { e ->
                        _errorState.value = HomeError.LoadFailed("pendingUploads", e)
                    }
                }
        }
    }
```

- [ ] **Step 10: Verify compilation**

```bash
cd "E:\Git\paperless client" && ./gradlew :app:compileDebugKotlin --no-daemon 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 11: Run the error propagation tests — confirm tests 1 and 2 pass**

```bash
cd "E:\Git\paperless client" && ./gradlew :app:testDebugUnitTest --tests "com.paperless.scanner.ui.screens.home.HomeViewModelTest" --no-daemon 2>&1 | tail -20
```

Expected: The first 2 new tests (`observeTagsReactively*`, `observeRecentDocumentsReactively*`) now pass. `deleteRecentDocument*` and `clearHomeError*` may still fail.

- [ ] **Step 12: Commit**

```bash
git add "app/src/main/java/com/paperless/scanner/ui/screens/home/HomeViewModel.kt"
git commit -m "refactor: wire asUiResult() to all 9 observe* flows in HomeViewModel (#85)"
```

---

## Task 7: Refactor Action Functions — Emit to `_errorState`

**Files:**
- Modify: `app/src/main/java/com/paperless/scanner/ui/screens/home/HomeViewModel.kt`

- [ ] **Step 1: Refactor `deleteRecentDocument()`**

Find:
```kotlin
                }.onFailure { error ->
                    _uiState.update {
                        it.copy(
                            deletedDocument = null,
                            error = context.getString(R.string.error_delete_document)
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        deletedDocument = null,
                        error = context.getString(R.string.error_delete_document)
                    )
                }
            }
```

Replace with:
```kotlin
                }.onFailure { error ->
                    _uiState.update { it.copy(deletedDocument = null) }
                    _errorState.value = HomeError.ActionFailed("deleteDocument", error)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _uiState.update { it.copy(deletedDocument = null) }
                _errorState.value = HomeError.ActionFailed("deleteDocument", e)
            }
```

- [ ] **Step 2: Refactor `undoDelete()`**

Find inside `undoDelete()`:
```kotlin
            try {
                trashRepository.restoreDocument(deletedDoc.id).onFailure {
                    _uiState.update {
                        it.copy(error = context.getString(R.string.error_restore_document))
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = context.getString(R.string.error_restore_document))
                }
            }
```

Replace with:
```kotlin
            try {
                trashRepository.restoreDocument(deletedDoc.id).onFailure { e ->
                    _errorState.value = HomeError.ActionFailed("restoreDocument", e)
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                _errorState.value = HomeError.ActionFailed("restoreDocument", e)
            }
```

- [ ] **Step 3: Refactor `loadUntaggedDocuments()` — surface failure to errorState**

Find inside `loadUntaggedDocuments()`:
```kotlin
            }.onFailure { error ->
                logger.log(Level.WARNING, "Failed to load untagged documents: ${error.message}")
                _tagSuggestionsState.update {
                    it.copy(isLoading = false)
                }
            }
```

Replace with:
```kotlin
            }.onFailure { error ->
                logger.log(Level.WARNING, "Failed to load untagged documents: ${error.message}")
                _tagSuggestionsState.update { it.copy(isLoading = false) }
                _errorState.value = HomeError.LoadFailed("untaggedDocuments", error)
            }
```

- [ ] **Step 4: Add missing import for CancellationException if not present**

Check the imports at the top of HomeViewModel.kt:
```bash
grep -n "CancellationException" app/src/main/java/com/paperless/scanner/ui/screens/home/HomeViewModel.kt | head -3
```

If not imported, add:
```kotlin
import kotlinx.coroutines.CancellationException
```

- [ ] **Step 5: Run all 4 new tests — all should pass**

```bash
cd "E:\Git\paperless client" && ./gradlew :app:testDebugUnitTest --tests "com.paperless.scanner.ui.screens.home.HomeViewModelTest" --no-daemon 2>&1 | tail -20
```

Expected: All tests including the 4 new error tests pass.

- [ ] **Step 6: Commit**

```bash
git add "app/src/main/java/com/paperless/scanner/ui/screens/home/HomeViewModel.kt"
git commit -m "refactor: standardize action function errors to _errorState in HomeViewModel (#85)"
```

---

## Task 8: Add Error Strings to `strings.xml`

**Files:**
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: Add the two new strings**

Find the area with existing error strings (around line 945–947):
```xml
    <string name="error_restore_document">Error restoring document</string>
    <string name="error_restore_all_documents">Error restoring all documents</string>
    <string name="error_delete_document">Error deleting document</string>
```

Add after them:
```xml
    <string name="error_load_data">Failed to load data. Please try again.</string>
    <string name="error_action_failed">Action failed. Please try again.</string>
```

- [ ] **Step 2: Verify compilation**

```bash
cd "E:\Git\paperless client" && ./gradlew :app:compileDebugKotlin --no-daemon 2>&1 | tail -5
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
git add "app/src/main/res/values/strings.xml"
git commit -m "feat: add error_load_data and error_action_failed string resources (#85)"
```

---

## Task 9: Wire `HomeScreen.kt` Error Snackbar

**Files:**
- Modify: `app/src/main/java/com/paperless/scanner/ui/screens/home/HomeScreen.kt`

- [ ] **Step 1: Add `errorState` collection after the existing state collections**

Find the block of state collections around line 102:
```kotlin
    val uiState by viewModel.uiState.collectAsState()
    val isOnline by viewModel.isOnline.collectAsState()
    // ...
    val createTagState by viewModel.createTagState.collectAsState()
    var showCreateTagDialog by remember { mutableStateOf(false) }
```

Add after `createTagState` collection:
```kotlin
    val errorState by viewModel.errorState.collectAsState()
    val errorMessage = when (errorState) {
        is HomeError.LoadFailed -> stringResource(R.string.error_load_data)
        is HomeError.ActionFailed -> stringResource(R.string.error_action_failed)
        null -> null
    }
```

- [ ] **Step 2: Add `LaunchedEffect` for error Snackbar**

Find the existing `LaunchedEffect(createTagState) { ... }` block. Add the error LaunchedEffect directly after it:
```kotlin
    // Show Snackbar for HomeViewModel errors
    LaunchedEffect(errorState) {
        errorMessage?.let { msg ->
            snackbarHostState.showSnackbar(msg)
            viewModel.clearHomeError()
        }
    }
```

- [ ] **Step 3: Verify compilation**

```bash
cd "E:\Git\paperless client" && ./gradlew :app:compileDebugKotlin --no-daemon 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`

If there's an import error for `HomeError`, it shouldn't happen because `HomeError.kt` is in the same package (`com.paperless.scanner.ui.screens.home`). If there IS a compile error, check the import list.

- [ ] **Step 4: Commit**

```bash
git add "app/src/main/java/com/paperless/scanner/ui/screens/home/HomeScreen.kt"
git commit -m "feat: wire HomeScreen error Snackbar to errorState (#85)"
```

---

## Task 10: Full CI Validation and Final Commit

**Files:** None (validation only)

- [ ] **Step 1: Run the complete test suite**

```bash
cd "E:\Git\paperless client" && ./gradlew :app:testReleaseUnitTest --no-daemon 2>&1 | tail -20
```

Expected: All tests pass (look for `BUILD SUCCESSFUL` and zero failures).

- [ ] **Step 2: Run lint**

```bash
cd "E:\Git\paperless client" && ./gradlew :app:lintRelease --no-daemon 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL` (no new lint errors).

- [ ] **Step 3: Run full CI validation script**

```bash
cd "E:\Git\paperless client" && ./scripts/validate-ci.sh --quick 2>&1 | tail -20
```

Expected: All phases green.

- [ ] **Step 4: Create changelog files**

Determine the next version code by checking `version.properties`:
```bash
cat version.properties
```

The versionCode is `MAJOR*10000 + MINOR*100 + PATCH`. Current is 1.5.113 → 10613. After auto-bump it will be 1.5.114 → 10614.

Create:
```
fastlane/metadata/android/en-US/changelogs/10614.txt
fastlane/metadata/android/de-DE/changelogs/10614.txt
```

Content for `en-US/changelogs/10614.txt` (≤500 chars):
```
Version 1.5.114:

🔧 Improvements:
- Consistent error handling in home screen
- Errors now shown via Snackbar instead of silent failures
```

Content for `de-DE/changelogs/10614.txt` (≤500 chars):
```
Version 1.5.114:

🔧 Verbesserungen:
- Einheitliche Fehlerbehandlung im Home-Screen
- Fehler werden jetzt per Snackbar angezeigt statt still ignoriert
```

Verify character count:
```bash
wc -m fastlane/metadata/android/en-US/changelogs/10614.txt fastlane/metadata/android/de-DE/changelogs/10614.txt
```

Expected: Both under 500 characters.

- [ ] **Step 5: Final commit**

```bash
git add fastlane/metadata/android/en-US/changelogs/10614.txt
git add fastlane/metadata/android/de-DE/changelogs/10614.txt
git commit -m "$(cat <<'EOF'
refactor: HomeViewModel centralized error handling via errorState (Closes #85)

- HomeError sealed class (LoadFailed / ActionFailed)
- Flow<T>.asUiResult() extension re-throws CancellationException
- All 9 observe* flows wrapped with asUiResult()
- Action fns (deleteDocument, undoDelete, loadUntaggedDocuments) emit to _errorState
- HomeScreen LaunchedEffect shows Snackbar for any HomeError
- 4 new unit tests: 2 observe* error paths + 1 action path + 1 clearHomeError

Co-Authored-By: Claude Sonnet 4.6 <noreply@anthropic.com>
EOF
)"
```

---

## Acceptance Criteria Checklist

- [ ] AC #1: All 9 observe* flows use `.asUiResult()` (Tasks 6)
- [ ] AC #2: Single `errorState: StateFlow<HomeError?>` owns user-visible errors (Tasks 3, 7, 9)
- [ ] AC #3: Tests verify error propagation + recovery (Tasks 5, 6, 7 — 4 tests total)
