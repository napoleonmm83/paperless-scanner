# Design: HomeViewModel Error Handling (Issue #85)

**Date:** 2026-05-14  
**Issue:** https://github.com/napoleonmm83/paperless-scanner/issues/85  
**Finding:** F-043 — HomeViewModel: 17+ viewModelScope.launch with inconsistent error handling  
**Effort:** L

---

## Problem

`HomeViewModel.kt` (1346 lines) contains 9 `observe*` functions that use `viewModelScope.launch { flow.collect { } }` with **zero error handling**. If any Room Flow throws, the coroutine dies silently and reactive updates stop permanently. Additionally, ~9 action `launch` blocks handle errors inconsistently — some use `try/catch`, some use `onFailure`, some do nothing.

`HomeUiState.error: String?` exists but was never connected to `HomeScreen` (dead code). Errors from `deleteRecentDocument()` and `undoDelete()` update this field but are never shown to the user.

---

## Design

### 1. New Primitives

**File: `app/src/main/java/com/paperless/scanner/ui/screens/home/HomeError.kt`**

```kotlin
package com.paperless.scanner.ui.screens.home

sealed class HomeError {
    data class LoadFailed(val source: String, val cause: Throwable) : HomeError()
    data class ActionFailed(val action: String, val cause: Throwable) : HomeError()
}
```

- `LoadFailed` — emitted when an observe* flow throws (source = "tags", "recentDocuments", etc.)
- `ActionFailed` — emitted when a user-triggered action fails (action = "deleteDocument", "restoreDocument", etc.)

**File: `app/src/main/java/com/paperless/scanner/util/UiResultExtensions.kt`**

```kotlin
package com.paperless.scanner.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

fun <T> Flow<T>.asUiResult(): Flow<Result<T>> =
    map { Result.success(it) }
        .catch { e ->
            if (e is CancellationException) throw e
            emit(Result.failure(e))
        }
```

`CancellationException` is re-thrown per project pattern (see MEMORY: `CancellationException catch ordering`). This keeps coroutine cancellation working correctly.

---

### 2. HomeViewModel Changes

**`HomeUiState`** — remove `error: String?` (dead code, never read by HomeScreen):
```kotlin
data class HomeUiState(
    val stats: DocumentStat = DocumentStat(),
    val recentDocuments: List<RecentDocument> = emptyList(),
    // ... all other fields
    val isLoading: Boolean = true
    // REMOVED: val error: String? = null
)
```

**New error StateFlow** added to `HomeViewModel`:
```kotlin
private val _errorState = MutableStateFlow<HomeError?>(null)
val errorState: StateFlow<HomeError?> = _errorState.asStateFlow()

fun clearHomeError() { _errorState.value = null }
```

`clearError()` method removed (replaced by `clearHomeError()`).

**All 9 observe* functions** rewritten with `asUiResult()`:

| Function | Flow Source | Error `source` key |
|---|---|---|
| `observeTagsReactively` | `tagRepository.observeTags()` | `"tags"` |
| `observeRecentDocumentsReactively` | `combine(documents, tagMap)` | `"recentDocuments"` |
| `observeProcessingTasksReactively` | `taskRepository.observeUnacknowledgedTasksExcludingDeleted()` | `"processingTasks"` |
| `observeUntaggedCountReactively` | `documentCountRepository.observeUntaggedDocumentsCount()` | `"untaggedCount"` |
| `observeDeletedCountReactively` | `trashRepository.observeTrashedDocumentsCount()` | `"deletedCount"` |
| `observeOldestDeletedTimestampReactively` | `trashRepository.observeOldestDeletedTimestamp()` | `"deletedTimestamp"` |
| `observeActiveUploadsCountReactively` | `uploadQueueRepository.pendingCount` | `"activeUploads"` |
| `observeFailedSyncCountReactively` | `syncHistoryRepository.observeFailedCount()` | `"failedSync"` |
| `observePendingUploads` | `pendingChangesCount` (StateFlow) | `"pendingUploads"` |

`startNetworkMonitoring()` is excluded — it is a coordinator function (triggers `loadDashboardData()` on reconnect) with its own internal state logic, not a pure observe flow.

Pattern for each:
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

**Action functions** — replace `_uiState.update { it.copy(error = ...) }` with `_errorState.value = HomeError.ActionFailed(...)`:

| Function | Current | After |
|---|---|---|
| `deleteRecentDocument` | `_uiState.update { error = getString(...) }` | `_errorState.value = HomeError.ActionFailed("deleteDocument", e)` |
| `undoDelete` | `_uiState.update { error = getString(...) }` | `_errorState.value = HomeError.ActionFailed("restoreDocument", e)` |
| `acknowledgeTask` | `logger.log(WARNING, ...)` | unchanged (non-user-visible) |
| `acknowledgeCompletedTasks` | `logger.log(WARNING, ...)` | unchanged (non-user-visible) |
| `loadUntaggedDocuments` | `logger.log(WARNING, ...)` | `_errorState.value = HomeError.LoadFailed("untaggedDocuments", e)` |

Note: `acknowledgeTask` and `acknowledgeCompletedTasks` failures remain as logger-only — a failed acknowledge does not need a visible error snackbar (data is already displayed, user can retry by dismissing again).

---

### 3. HomeScreen Changes

**File: `app/src/main/java/com/paperless/scanner/ui/screens/home/HomeScreen.kt`**

Add error Snackbar alongside the existing undo-delete Snackbar:

```kotlin
val errorState by viewModel.errorState.collectAsStateWithLifecycle()
val errorSnackbarState = remember { SnackbarHostState() }

LaunchedEffect(errorState) {
    errorState?.let { error ->
        val msg = when (error) {
            is HomeError.LoadFailed -> context.getString(R.string.error_load_data)
            is HomeError.ActionFailed -> context.getString(R.string.error_action_failed)
        }
        errorSnackbarState.showSnackbar(msg)
        viewModel.clearHomeError()
    }
}
```

Two new string resources needed:
- `R.string.error_load_data` — "Could not load data. Please try again."
- `R.string.error_action_failed` — "Action failed. Please try again."

---

### 4. Tests

**File: `app/src/test/java/com/paperless/scanner/ui/screens/home/HomeViewModelTest.kt`**

Three new test groups using MockK + `advanceUntilIdle()` + direct StateFlow read:

**Group 1: observe* error propagation**
```kotlin
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
```

**Group 2: action error propagation**
```kotlin
@Test
fun `deleteRecentDocument sets ActionFailed on errorState when trash delete fails`() = runTest {
    coEvery { trashRepository.deleteDocument(any()) } returns Result.failure(RuntimeException("Network failure"))
    val vm = createViewModel()
    vm.deleteRecentDocument(documentId = 1, documentTitle = "Test")
    advanceUntilIdle()
    val error = vm.errorState.value
    assertNotNull(error)
    assertTrue(error is HomeError.ActionFailed)
    assertEquals("deleteDocument", (error as HomeError.ActionFailed).action)
}
```

**Group 3: error recovery**
```kotlin
@Test
fun `clearHomeError resets errorState to null`() = runTest {
    every { tagRepository.observeTags() } returns flow { throw RuntimeException("fail") }
    val vm = createViewModel()
    advanceUntilIdle()
    assertNotNull(vm.errorState.value)
    vm.clearHomeError()
    assertNull(vm.errorState.value)
}
```

Note: `errorState` is initialized to `null`. Tests use `advanceUntilIdle()` to let
coroutines run, then read `.value` directly — no need to handle an initial null emission.

---

## Acceptance Criteria

- [x] All 9 observe* flows funnel through `asUiResult()` shared helper (AC #1)
- [x] Single `errorState: StateFlow<HomeError?>` owns all user-visible errors (AC #2)
- [x] Tests verify error propagation + recovery (AC #3)

## Files Changed

| File | Action |
|---|---|
| `ui/screens/home/HomeError.kt` | Create |
| `util/UiResultExtensions.kt` | Create |
| `ui/screens/home/HomeViewModel.kt` | Modify (major) |
| `ui/screens/home/HomeScreen.kt` | Modify (add error Snackbar) |
| `res/values/strings.xml` | Add 2 error strings |
| `test/.../HomeViewModelTest.kt` | Modify (add 3 test groups) |
