# Issue #74 — LabelsViewModel race-free reactive refactor — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace four `private var List<EntityItem>` fields in `LabelsViewModel` with `MutableStateFlow<List<EntityItem>>` and drive `uiState.entities` from a single `combine(...)` pipeline so that no manual recomputation trigger and no concurrent-read race remains.

**Architecture:** Four source-of-truth `_allXxx` MutableStateFlows replace the `var` fields. A `ListSettings` data-class flow derived from `_uiState` (with `distinctUntilChanged`) feeds, alongside the four sources, a single `combine` whose collector writes the processed list back into `_uiState.entities`. The four `observeXxxReactively` collectors only update their respective source flow — the per-tab `if` and inline `_uiState.update` blocks go away. `applyCurrentFilters()` and all of its callers go away.

**Tech Stack:** Kotlin 2.0, kotlinx.coroutines (Flow / StateFlow / combine), MockK + Turbine + `kotlinx.coroutines.test.runTest` for tests, JDK 21 for build.

**Spec:** [`docs/superpowers/specs/2026-05-09-issue-74-labelsviewmodel-stateflow-design.md`](../specs/2026-05-09-issue-74-labelsviewmodel-stateflow-design.md)

**Branch:** `refactor/issue-74-labelsviewmodel-stateflow` (already created with the spec commit `506fe17`)

---

## Conventions used throughout

- **All Bash commands MUST export JDK 21** before invoking Gradle, because the system `JAVA_HOME` points at JDK 25 and breaks the Kotlin compiler. Prefix every Gradle invocation with `export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.9.10-hotspot"; export PATH="$JAVA_HOME/bin:$PATH"; ...`
- **Use Release variants for tests/lint/assemble**, exactly like `.github/workflows/auto-deploy-internal.yml` does. Debug variants pass a strict superset and don't catch ProGuard/R8 issues.
- **Commit per task.** Each task ends with one `git commit` so the PR has a clean per-step history.
- **No `--no-verify`.** If pre-commit or pre-push fails, fix the underlying issue.

---

## File Structure

| File | Type | Responsibility |
| --- | --- | --- |
| `app/src/main/java/com/paperless/scanner/ui/screens/labels/LabelsViewModel.kt` | **Modify** (in place, ~707 → ~680 LOC after deletions) | Replace `var` fields with MutableStateFlow; introduce `ListSettings` private data class; install `combine()` pipeline in `init`; delete `applyCurrentFilters()` and its callers; simplify the four `observeXxxReactively` blocks; flip `getActiveEntities()` and `resetState()` to read/write through the flows. |
| `app/src/test/java/com/paperless/scanner/ui/screens/labels/LabelsViewModelTest.kt` | **Create** (~200 LOC) | Four characterization + race-immunity tests against `viewModel.uiState`. |

No other files touched. No screen-side change. No DI / Hilt module change.

---

## Task 1: Create the test skeleton

**Files:**
- Create: `app/src/test/java/com/paperless/scanner/ui/screens/labels/LabelsViewModelTest.kt`

- [ ] **Step 1.1: Create the empty test class**

```kotlin
package com.paperless.scanner.ui.screens.labels

import android.content.Context
import com.paperless.scanner.data.repository.CorrespondentRepository
import com.paperless.scanner.data.repository.CustomFieldRepository
import com.paperless.scanner.data.repository.DocumentTypeRepository
import com.paperless.scanner.data.repository.TagRepository
import com.paperless.scanner.domain.model.Correspondent
import com.paperless.scanner.domain.model.CustomField
import com.paperless.scanner.domain.model.DocumentType
import com.paperless.scanner.domain.model.Tag
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before

@OptIn(ExperimentalCoroutinesApi::class)
class LabelsViewModelTest {

    private lateinit var context: Context
    private lateinit var tagRepository: TagRepository
    private lateinit var correspondentRepository: CorrespondentRepository
    private lateinit var documentTypeRepository: DocumentTypeRepository
    private lateinit var customFieldRepository: CustomFieldRepository

    private lateinit var tagFlow: MutableStateFlow<List<Tag>>
    private lateinit var correspondentFlow: MutableStateFlow<List<Correspondent>>
    private lateinit var documentTypeFlow: MutableStateFlow<List<DocumentType>>
    private lateinit var customFieldFlow: MutableStateFlow<List<CustomField>>

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = mockk(relaxed = true)
        tagRepository = mockk(relaxed = true)
        correspondentRepository = mockk(relaxed = true)
        documentTypeRepository = mockk(relaxed = true)
        customFieldRepository = mockk(relaxed = true)

        tagFlow = MutableStateFlow(emptyList())
        correspondentFlow = MutableStateFlow(emptyList())
        documentTypeFlow = MutableStateFlow(emptyList())
        customFieldFlow = MutableStateFlow(emptyList())

        every { tagRepository.observeTags() } returns tagFlow
        every { correspondentRepository.observeCorrespondents() } returns correspondentFlow
        every { documentTypeRepository.observeDocumentTypes() } returns documentTypeFlow
        every { customFieldRepository.observeCustomFields() } returns customFieldFlow

        coEvery { tagRepository.getTags(any()) } returns Result.success(emptyList())
        coEvery { correspondentRepository.getCorrespondents(any()) } returns Result.success(emptyList())
        coEvery { documentTypeRepository.getDocumentTypes(any()) } returns Result.success(emptyList())
        coEvery { customFieldRepository.getCustomFields(any()) } returns Result.success(emptyList())
        coEvery { customFieldRepository.isCustomFieldsApiAvailable() } returns false
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): LabelsViewModel = LabelsViewModel(
        context = context,
        tagRepository = tagRepository,
        correspondentRepository = correspondentRepository,
        documentTypeRepository = documentTypeRepository,
        customFieldRepository = customFieldRepository
    )
}
```

**IMPORTANT:** Verify the domain-model class names by checking `app/src/main/java/com/paperless/scanner/domain/model/` before assuming `Tag`, `Correspondent`, `DocumentType`, `CustomField` exist with those names. If a name is different (e.g. `Correspondent` lives in another package), adjust the import — but do not change the test logic.

- [ ] **Step 1.2: Compile-only run to verify the skeleton**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.9.10-hotspot"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:compileReleaseUnitTestKotlin --no-daemon
```

Expected: `BUILD SUCCESSFUL`. If the build fails on `Tag`/`Correspondent`/`DocumentType`/`CustomField` imports, fix the imports to match the actual paths and re-run.

- [ ] **Step 1.3: Commit**

```bash
git add app/src/test/java/com/paperless/scanner/ui/screens/labels/LabelsViewModelTest.kt
git commit -m "$(cat <<'EOF'
test: scaffold LabelsViewModelTest with mocked observe-flows (Refs #74)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 2: Test 1 — tab switch recomputes entities (characterization)

**Files:**
- Modify: `app/src/test/java/com/paperless/scanner/ui/screens/labels/LabelsViewModelTest.kt`

- [ ] **Step 2.1: Add the test**

Append inside the class body, before the closing `}`:

```kotlin
    @org.junit.Test
    fun `tab switch recomputes entities without manual trigger`() = kotlinx.coroutines.test.runTest {
        tagFlow.value = listOf(Tag(id = 1, name = "Tag-A", color = "#FFFFFF", documentCount = 0))
        correspondentFlow.value = listOf(Correspondent(id = 10, name = "Corr-X", documentCount = 0))

        val viewModel = createViewModel()
        kotlinx.coroutines.test.runCurrent()

        // Active tab defaults to TAG.
        org.junit.Assert.assertEquals(
            listOf("Tag-A"),
            viewModel.uiState.value.entities.map { it.name }
        )

        viewModel.setEntityType(EntityType.CORRESPONDENT)
        kotlinx.coroutines.test.runCurrent()

        org.junit.Assert.assertEquals(
            listOf("Corr-X"),
            viewModel.uiState.value.entities.map { it.name }
        )
    }
```

**Note:** Fully-qualified names are used here so the imports stay minimal. After Task 5 the imports will be tidied; for now keep them inline so the next test still compiles even if you commit between tasks.

If `Tag`, `Correspondent`, etc. need additional fields beyond `(id, name, color, documentCount)` to satisfy their data-class signatures, set them via named arguments — the assertion only inspects `name`. Do NOT delete fields.

- [ ] **Step 2.2: Run the test**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.9.10-hotspot"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:testReleaseUnitTest --tests "com.paperless.scanner.ui.screens.labels.LabelsViewModelTest.tab switch recomputes entities without manual trigger" --no-daemon
```

Expected: `PASSED`. The current code passes because `setEntityType` calls `applyCurrentFilters()` synchronously. This test is a characterization that locks the behaviour in for the refactor.

- [ ] **Step 2.3: Commit**

```bash
git add app/src/test/java/com/paperless/scanner/ui/screens/labels/LabelsViewModelTest.kt
git commit -m "$(cat <<'EOF'
test: tab switch recomputes entities (Refs #74)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 3: Test 2 — search query flows through

**Files:**
- Modify: `app/src/test/java/com/paperless/scanner/ui/screens/labels/LabelsViewModelTest.kt`

- [ ] **Step 3.1: Add the test**

Append inside the class body:

```kotlin
    @org.junit.Test
    fun `search query update flows into uiState entities`() = kotlinx.coroutines.test.runTest {
        tagFlow.value = listOf(
            Tag(id = 1, name = "Invoice", color = "#FFFFFF", documentCount = 0),
            Tag(id = 2, name = "Receipt", color = "#FFFFFF", documentCount = 0)
        )

        val viewModel = createViewModel()
        kotlinx.coroutines.test.runCurrent()
        org.junit.Assert.assertEquals(2, viewModel.uiState.value.entities.size)

        viewModel.search("inv")
        kotlinx.coroutines.test.runCurrent()

        org.junit.Assert.assertEquals(
            listOf("Invoice"),
            viewModel.uiState.value.entities.map { it.name }
        )
    }
```

- [ ] **Step 3.2: Run the test**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.9.10-hotspot"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:testReleaseUnitTest --tests "com.paperless.scanner.ui.screens.labels.LabelsViewModelTest.search query update flows into uiState entities" --no-daemon
```

Expected: `PASSED`. Current code passes because `search()` calls `applyCurrentFilters()`. Characterization.

- [ ] **Step 3.3: Commit**

```bash
git add app/src/test/java/com/paperless/scanner/ui/screens/labels/LabelsViewModelTest.kt
git commit -m "$(cat <<'EOF'
test: search query flows into uiState (Refs #74)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 4: Test 3 — late subscriber observes current entities

**Files:**
- Modify: `app/src/test/java/com/paperless/scanner/ui/screens/labels/LabelsViewModelTest.kt`

- [ ] **Step 4.1: Add the test**

```kotlin
    @org.junit.Test
    fun `late subscriber sees latest entities on first emission`() = kotlinx.coroutines.test.runTest {
        tagFlow.value = listOf(Tag(id = 1, name = "Tag-A", color = "#FFFFFF", documentCount = 0))

        val viewModel = createViewModel()
        kotlinx.coroutines.test.runCurrent()

        // Late subscriber: collect AFTER the source flow has emitted and the VM has processed it.
        val firstEmission = viewModel.uiState.value
        org.junit.Assert.assertEquals(
            listOf("Tag-A"),
            firstEmission.entities.map { it.name }
        )
    }
```

- [ ] **Step 4.2: Run the test**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.9.10-hotspot"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:testReleaseUnitTest --tests "com.paperless.scanner.ui.screens.labels.LabelsViewModelTest.late subscriber sees latest entities on first emission" --no-daemon
```

Expected: `PASSED`. uiState is a StateFlow, so its value is always replayable. Characterization.

- [ ] **Step 4.3: Commit**

```bash
git add app/src/test/java/com/paperless/scanner/ui/screens/labels/LabelsViewModelTest.kt
git commit -m "$(cat <<'EOF'
test: late subscriber sees latest entities (Refs #74)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: Test 4 — interleaved source emissions stay consistent

**Files:**
- Modify: `app/src/test/java/com/paperless/scanner/ui/screens/labels/LabelsViewModelTest.kt`

- [ ] **Step 5.1: Add the test**

```kotlin
    @org.junit.Test
    fun `interleaved tag and correspondent emissions stay consistent on TAG tab`() = kotlinx.coroutines.test.runTest {
        val viewModel = createViewModel()
        kotlinx.coroutines.test.runCurrent()

        // Active tab is TAG. Emit on the correspondent flow first to ensure
        // the unrelated source does not leak into uiState.entities.
        correspondentFlow.value = listOf(Correspondent(id = 99, name = "Corr-Leak", documentCount = 0))
        kotlinx.coroutines.test.runCurrent()

        org.junit.Assert.assertTrue(
            "uiState.entities must not contain a correspondent while TAG is the active tab",
            viewModel.uiState.value.entities.none { it.entityType == EntityType.CORRESPONDENT }
        )

        // Now emit on the tag flow — this MUST land in uiState.entities.
        tagFlow.value = listOf(Tag(id = 1, name = "Tag-A", color = "#FFFFFF", documentCount = 0))
        kotlinx.coroutines.test.runCurrent()

        org.junit.Assert.assertEquals(
            listOf("Tag-A" to EntityType.TAG),
            viewModel.uiState.value.entities.map { it.name to it.entityType }
        )

        // Switch to CORRESPONDENT — the correspondent that arrived earlier must
        // now be the visible state, with no tag bleed-through.
        viewModel.setEntityType(EntityType.CORRESPONDENT)
        kotlinx.coroutines.test.runCurrent()

        org.junit.Assert.assertEquals(
            listOf("Corr-Leak" to EntityType.CORRESPONDENT),
            viewModel.uiState.value.entities.map { it.name to it.entityType }
        )
    }
```

- [ ] **Step 5.2: Run the test**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.9.10-hotspot"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:testReleaseUnitTest --tests "com.paperless.scanner.ui.screens.labels.LabelsViewModelTest.interleaved tag and correspondent emissions stay consistent on TAG tab" --no-daemon
```

Expected: `PASSED`. The current code's `observeCorrespondentsReactively` collector has an `if (currentEntityType == CORRESPONDENT)` guard that prevents leak; this test characterises that guarantee. After the refactor it's enforced by `combine` + `ListSettings.type`.

- [ ] **Step 5.3: Commit**

```bash
git add app/src/test/java/com/paperless/scanner/ui/screens/labels/LabelsViewModelTest.kt
git commit -m "$(cat <<'EOF'
test: interleaved source emissions stay consistent (Refs #74)

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: Refactor LabelsViewModel — convert vars to MutableStateFlow + combine pipeline

**Files:**
- Modify: `app/src/main/java/com/paperless/scanner/ui/screens/labels/LabelsViewModel.kt`

This is the meat of the change. It MUST be done as a single edit so the file compiles at every commit boundary.

- [ ] **Step 6.1: Add new imports near the existing kotlinx.coroutines.flow imports**

Find the import block at the top (around L16-L20) and add these imports if missing:

```kotlin
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
```

- [ ] **Step 6.2: Replace the four `private var` fields (currently L103-L106) with MutableStateFlow**

Find:
```kotlin
    // Separate collections for each entity type (all as EntityItem)
    private var allTags: List<EntityItem> = emptyList()
    private var allCorrespondents: List<EntityItem> = emptyList()
    private var allDocumentTypes: List<EntityItem> = emptyList()
    private var allCustomFields: List<EntityItem> = emptyList()
```

Replace with:
```kotlin
    // Source-of-truth StateFlows for each entity type — race-free, replayable.
    private val _allTags = MutableStateFlow<List<EntityItem>>(emptyList())
    private val _allCorrespondents = MutableStateFlow<List<EntityItem>>(emptyList())
    private val _allDocumentTypes = MutableStateFlow<List<EntityItem>>(emptyList())
    private val _allCustomFields = MutableStateFlow<List<EntityItem>>(emptyList())

    // Settings tuple consumed by the reactive entities pipeline.
    private data class ListSettings(
        val type: EntityType,
        val query: String,
        val sort: LabelSortOption,
        val filter: LabelFilterOption
    )
```

- [ ] **Step 6.3: Replace the `init` block to install the combine pipeline**

Find:
```kotlin
    init {
        // BEST PRACTICE: Start Flow observer FIRST, then trigger API refresh
        observeTagsReactively()
        observeCorrespondentsReactively()
        observeDocumentTypesReactively()
        observeCustomFieldsReactively()
        detectCustomFieldsAvailability()
        refresh()
    }
```

Replace with:
```kotlin
    init {
        // BEST PRACTICE: Start Flow observer FIRST, then trigger API refresh.
        observeTagsReactively()
        observeCorrespondentsReactively()
        observeDocumentTypesReactively()
        observeCustomFieldsReactively()

        // Single reactive pipeline that derives uiState.entities from
        // (active source flow) × (search/sort/filter settings). Replaces the
        // old applyCurrentFilters() manual-trigger pattern.
        viewModelScope.launch {
            val settingsFlow = _uiState
                .map { ListSettings(it.currentEntityType, it.searchQuery, it.sortOption, it.filterOption) }
                .distinctUntilChanged()

            combine(
                _allTags,
                _allCorrespondents,
                _allDocumentTypes,
                _allCustomFields,
                settingsFlow
            ) { tags, corr, docTypes, custom, settings ->
                val active = when (settings.type) {
                    EntityType.TAG            -> tags
                    EntityType.CORRESPONDENT  -> corr
                    EntityType.DOCUMENT_TYPE  -> docTypes
                    EntityType.CUSTOM_FIELD   -> custom
                }
                applySearchFilterSortEntities(active, settings)
            }.collect { processed ->
                _uiState.update { it.copy(entities = processed) }
            }
        }

        detectCustomFieldsAvailability()
        refresh()
    }
```

- [ ] **Step 6.4: Simplify `getActiveEntities()` to read from the flows**

Find:
```kotlin
    private fun getActiveEntities(): List<EntityItem> {
        return when (_uiState.value.currentEntityType) {
            EntityType.TAG -> allTags
            EntityType.CORRESPONDENT -> allCorrespondents
            EntityType.DOCUMENT_TYPE -> allDocumentTypes
            EntityType.CUSTOM_FIELD -> allCustomFields
        }
    }
```

Replace with:
```kotlin
    private fun getActiveEntities(): List<EntityItem> {
        return when (_uiState.value.currentEntityType) {
            EntityType.TAG -> _allTags.value
            EntityType.CORRESPONDENT -> _allCorrespondents.value
            EntityType.DOCUMENT_TYPE -> _allDocumentTypes.value
            EntityType.CUSTOM_FIELD -> _allCustomFields.value
        }
    }
```

- [ ] **Step 6.5: Delete `applyCurrentFilters()` entirely (currently L195-L205)**

Remove the whole function and its KDoc:

```kotlin
    /**
     * Applies current search, filter, and sort settings to the active entity type.
     * Updates the UI state with processed entities.
     */
    private fun applyCurrentFilters() {
        val activeEntities = getActiveEntities()
        val processed = applySearchFilterSortEntities(activeEntities, _uiState.value)

        _uiState.update {
            it.copy(
                entities = processed,
                isLoading = false
            )
        }
    }
```

(Just delete these lines.)

- [ ] **Step 6.6: Remove the `applyCurrentFilters()` calls from every public state-changing method**

These seven methods currently each end with a call to `applyCurrentFilters()`. Delete that single line from each — the combine pipeline does the recompute now. The resulting bodies should be exactly:

```kotlin
    fun setEntityType(type: EntityType) {
        _uiState.update {
            it.copy(
                currentEntityType = type,
                selectedEntity = null,
                documentsForEntity = emptyList()
            )
        }
    }

    fun search(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun setSortOption(option: LabelSortOption) {
        _uiState.update { it.copy(sortOption = option) }
    }

    fun setFilterOption(option: LabelFilterOption) {
        _uiState.update { it.copy(filterOption = option) }
    }

    fun setSortAndFilter(sort: LabelSortOption, filter: LabelFilterOption) {
        _uiState.update { it.copy(sortOption = sort, filterOption = filter) }
    }

    fun resetSortAndFilter() {
        _uiState.update {
            it.copy(
                sortOption = LabelSortOption.NAME_ASC,
                filterOption = LabelFilterOption.ALL
            )
        }
    }

    fun clearSearch() {
        _uiState.update { it.copy(searchQuery = "") }
    }
```

After this edit, `git grep -n "applyCurrentFilters" app/src/main/java/com/paperless/scanner/ui/screens/labels/LabelsViewModel.kt` MUST return zero matches.

- [ ] **Step 6.7: Simplify the four `observeXxxReactively` blocks**

Each one currently writes a `var`, checks `if (currentEntityType == X)`, and inline-`_uiState.update`s. After the refactor each becomes one atomic flow write. Replace `observeTagsReactively`:

```kotlin
    private fun observeTagsReactively() {
        viewModelScope.launch {
            tagRepository.observeTags().collect { tags ->
                _allTags.value = tags.map { tag ->
                    EntityItem(
                        id = tag.id,
                        name = tag.name,
                        color = parseColor(tag.color),
                        documentCount = tag.documentCount ?: 0,
                        entityType = EntityType.TAG
                    )
                }
            }
        }
    }
```

`observeCorrespondentsReactively`:

```kotlin
    private fun observeCorrespondentsReactively() {
        viewModelScope.launch {
            correspondentRepository.observeCorrespondents().collect { correspondents ->
                _allCorrespondents.value = correspondents.map { correspondent ->
                    EntityItem(
                        id = correspondent.id,
                        name = correspondent.name,
                        documentCount = correspondent.documentCount ?: 0,
                        entityType = EntityType.CORRESPONDENT
                    )
                }
            }
        }
    }
```

`observeDocumentTypesReactively`:

```kotlin
    private fun observeDocumentTypesReactively() {
        viewModelScope.launch {
            documentTypeRepository.observeDocumentTypes().collect { documentTypes ->
                _allDocumentTypes.value = documentTypes.map { documentType ->
                    EntityItem(
                        id = documentType.id,
                        name = documentType.name,
                        documentCount = documentType.documentCount ?: 0,
                        entityType = EntityType.DOCUMENT_TYPE
                    )
                }
            }
        }
    }
```

`observeCustomFieldsReactively`:

```kotlin
    private fun observeCustomFieldsReactively() {
        viewModelScope.launch {
            customFieldRepository.observeCustomFields().collect { customFields ->
                _allCustomFields.value = customFields.map { customField ->
                    EntityItem(
                        id = customField.id,
                        name = customField.name,
                        documentCount = 0, // Custom fields don't have document count
                        entityType = EntityType.CUSTOM_FIELD,
                        dataType = customField.dataType
                    )
                }
            }
        }
    }
```

Removed from each: the per-tab `if` check and the inline `_uiState.update { copy(entities = ..., isLoading = false, error = null) }` block. The `error = null` semantics change (DB-emit no longer clears errors) is intentional and documented in the spec.

- [ ] **Step 6.8: Change `applySearchFilterSortEntities` signature from `LabelsUiState` to `ListSettings`**

Find:
```kotlin
    private fun applySearchFilterSortEntities(
        entities: List<EntityItem>,
        state: LabelsUiState
    ): List<EntityItem> {
        // 1. Apply search
        var result = if (state.searchQuery.isBlank()) {
            entities
        } else {
            entities.filter { it.name.contains(state.searchQuery, ignoreCase = true) }
        }

        // 2. Apply filter
        result = when (state.filterOption) {
            LabelFilterOption.ALL -> result
            LabelFilterOption.WITH_DOCUMENTS -> result.filter { it.documentCount > 0 }
            LabelFilterOption.EMPTY -> result.filter { it.documentCount == 0 }
            LabelFilterOption.MANY_DOCUMENTS -> result.filter { it.documentCount > 5 }
        }

        // 3. Apply sort
        result = when (state.sortOption) {
            LabelSortOption.NAME_ASC -> result.sortedBy { it.name.lowercase() }
            LabelSortOption.NAME_DESC -> result.sortedByDescending { it.name.lowercase() }
            LabelSortOption.COUNT_DESC -> result.sortedByDescending { it.documentCount }
            LabelSortOption.COUNT_ASC -> result.sortedBy { it.documentCount }
            LabelSortOption.NEWEST -> result.sortedByDescending { it.id }
            LabelSortOption.OLDEST -> result.sortedBy { it.id }
        }

        return result
    }
```

Replace with:
```kotlin
    private fun applySearchFilterSortEntities(
        entities: List<EntityItem>,
        settings: ListSettings
    ): List<EntityItem> {
        // 1. Apply search
        var result = if (settings.query.isBlank()) {
            entities
        } else {
            entities.filter { it.name.contains(settings.query, ignoreCase = true) }
        }

        // 2. Apply filter
        result = when (settings.filter) {
            LabelFilterOption.ALL -> result
            LabelFilterOption.WITH_DOCUMENTS -> result.filter { it.documentCount > 0 }
            LabelFilterOption.EMPTY -> result.filter { it.documentCount == 0 }
            LabelFilterOption.MANY_DOCUMENTS -> result.filter { it.documentCount > 5 }
        }

        // 3. Apply sort
        result = when (settings.sort) {
            LabelSortOption.NAME_ASC -> result.sortedBy { it.name.lowercase() }
            LabelSortOption.NAME_DESC -> result.sortedByDescending { it.name.lowercase() }
            LabelSortOption.COUNT_DESC -> result.sortedByDescending { it.documentCount }
            LabelSortOption.COUNT_ASC -> result.sortedBy { it.documentCount }
            LabelSortOption.NEWEST -> result.sortedByDescending { it.id }
            LabelSortOption.OLDEST -> result.sortedBy { it.id }
        }

        return result
    }
```

- [ ] **Step 6.9: Update `resetState()` to clear the flows**

Find:
```kotlin
    fun resetState() {
        _uiState.update { LabelsUiState() }
        allTags = emptyList()
        allCorrespondents = emptyList()
        allDocumentTypes = emptyList()
        allCustomFields = emptyList()
        refresh()
    }
```

Replace with:
```kotlin
    fun resetState() {
        _uiState.update { LabelsUiState() }
        _allTags.value = emptyList()
        _allCorrespondents.value = emptyList()
        _allDocumentTypes.value = emptyList()
        _allCustomFields.value = emptyList()
        refresh()
    }
```

- [ ] **Step 6.10: Compile + run the entire labels test class**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.9.10-hotspot"
export PATH="$JAVA_HOME/bin:$PATH"
./gradlew :app:testReleaseUnitTest --tests "com.paperless.scanner.ui.screens.labels.LabelsViewModelTest" --no-daemon
```

Expected: 4 tests PASSED. If a test fails, read the failure carefully — the most likely cause is a missed `applyCurrentFilters()` call site or a stale reference to one of the deleted `var` fields. `git grep -n "applyCurrentFilters\\|^[ ]*allTags\\b\\|^[ ]*allCorrespondents\\b\\|^[ ]*allDocumentTypes\\b\\|^[ ]*allCustomFields\\b" app/src/main/java/com/paperless/scanner/ui/screens/labels/LabelsViewModel.kt` should return zero results.

- [ ] **Step 6.11: Commit**

```bash
git add app/src/main/java/com/paperless/scanner/ui/screens/labels/LabelsViewModel.kt
git commit -m "$(cat <<'EOF'
refactor: LabelsViewModel mutable vars to MutableStateFlow + combine pipeline

Replace four private var List<EntityItem> fields with MutableStateFlow and
drive uiState.entities from a single combine() pipeline. Eliminates the
read-during-write race and the manual applyCurrentFilters() trigger pattern.

Closes #74

Co-Authored-By: Claude Opus 4.7 (1M context) <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: Local CI gauntlet (full Release variants, exactly like GitHub Actions)

- [ ] **Step 7.1: Run the full local CI script**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.9.10-hotspot"
export PATH="$JAVA_HOME/bin:$PATH"
./scripts/validate-ci.sh
```

Expected: all phases pass — Validation Checks, `testReleaseUnitTest`, `assembleRelease`, `lintRelease`. If any phase fails, fix the underlying issue and re-run. Do NOT use `--no-verify` to skip pre-commit / pre-push hooks.

If `validate-ci.sh` is unavailable on the system, run the equivalent commands directly:

```bash
./gradlew testReleaseUnitTest --no-daemon
./gradlew lintRelease --no-daemon
./gradlew assembleRelease --no-daemon
```

All three must produce `BUILD SUCCESSFUL`.

- [ ] **Step 7.2: No commit needed** — this task only validates. If a fix was required to make CI pass, commit it as part of the relevant earlier task and re-run from the start of Task 7.

---

## Task 8: Push and open the PR

- [ ] **Step 8.1: Push the branch**

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.9.10-hotspot"
export PATH="$JAVA_HOME/bin:$PATH"
git push -u origin refactor/issue-74-labelsviewmodel-stateflow
```

The pre-push hook will run again. Expected: green.

- [ ] **Step 8.2: Open the PR via gh CLI**

```bash
gh pr create --title "refactor: LabelsViewModel mutable vars to MutableStateFlow + combine (Closes #74)" --body "$(cat <<'EOF'
## Summary

Closes #74. Replaces four `private var List<EntityItem>` fields in `LabelsViewModel` (L103-L106) with `MutableStateFlow<List<EntityItem>>` and drives `uiState.entities` from a single `combine(...)` pipeline. Eliminates the read-during-write race risk and the manual `applyCurrentFilters()` trigger pattern.

Pattern follows PR #218 / Issue #68 (HomeViewModel.tagMap → MutableStateFlow + combine).

## Changes

- `LabelsViewModel.kt`: 4× `var` → 4× `MutableStateFlow`. New private `ListSettings` data class. New `combine(...)` pipeline in `init`. Deleted `applyCurrentFilters()` and all 7 of its call sites. Simplified the four `observeXxxReactively` collectors. `applySearchFilterSortEntities` signature changed from `(entities, LabelsUiState)` to `(entities, ListSettings)`.
- `LabelsViewModelTest.kt`: new file. Four tests covering tab-switch reactivity, search-query propagation, late-subscriber correctness, and interleaved-source consistency.

## Intentional behaviour change

The four `observeXxxReactively` collectors no longer set `error = null` on each emission. A successful Room read does not retroactively prove that a network refresh succeeded, so it should not erase a network-failure error message. Errors are now cleared exclusively by `refresh()`-success, `clearError()`, and CRUD-success paths. This is the more correct semantic.

## Acceptance Criteria mapping

- [x] Four mutable vars replaced with StateFlow
- [x] displayedEntities is derived via combine() with no manual triggers
- [x] Tests verify reactive filter updates

(Issue body's exact wording proposes a separate public `displayedEntities` flow; we fold the derived list into `uiState.entities` instead, matching the PR #218 precedent. Same AC, smaller blast radius.)

## Test plan

- [x] `LabelsViewModelTest.kt` — 4 new tests pass on Release
- [x] `testReleaseUnitTest` green
- [x] `lintRelease` green
- [x] `assembleRelease` green
- [ ] CodeRabbit review

## Spec + plan

- Spec: `docs/superpowers/specs/2026-05-09-issue-74-labelsviewmodel-stateflow-design.md`
- Plan: `docs/superpowers/plans/2026-05-09-issue-74-labelsviewmodel-stateflow-plan.md`

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 8.3: Wait for CI + CodeRabbit, then squash-merge**

```bash
gh pr checks --watch
```

After all checks green and CodeRabbit has reviewed, squash-merge:

```bash
gh pr merge --squash --delete-branch
```

(Do NOT use `--auto` if you want to wait for CodeRabbit — `--auto` falls through to immediate merge in this repo because there is no required-checks branch protection. The auto-deploy workflow will then bump the version and add the changelog automatically. If a manual changelog is desired, add `fastlane/metadata/android/{de-DE,en-US}/changelogs/<nextVersionCode>.txt` BEFORE merging — but only if `main` has not already auto-bumped past the changelog filename you used.)

---

## Self-review checklist (already performed; for re-runners)

- ✅ Spec coverage:
  - Source-of-truth conversion → Task 6 Step 6.2
  - Reactive pipeline → Task 6 Step 6.3
  - `getActiveEntities` flow read → Task 6 Step 6.4
  - `applyCurrentFilters` deletion → Task 6 Steps 6.5 + 6.6
  - Observer simplification → Task 6 Step 6.7
  - Signature change → Task 6 Step 6.8
  - `resetState` flow write → Task 6 Step 6.9
  - Loading + error semantic change → covered in Task 6 Step 6.7 + PR body
  - Test 1 (tab switch) → Task 2
  - Test 2 (search) → Task 3
  - Test 3 (late subscriber) → Task 4
  - Test 4 (interleaved emissions) → Task 5

- ✅ No placeholders: every code block is complete and concrete; no "TBD", no "similar to above".

- ✅ Type consistency: `_allTags`/`_allCorrespondents`/`_allDocumentTypes`/`_allCustomFields` and `ListSettings(type, query, sort, filter)` used consistently from Task 1 onward; `applySearchFilterSortEntities(entities, settings)` matches its only caller in Task 6 Step 6.3.
