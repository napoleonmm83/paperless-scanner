# Issue #74 — LabelsViewModel race-free reactive refactor

**Issue:** [napoleonmm83/paperless-scanner#74](https://github.com/napoleonmm83/paperless-scanner/issues/74) (HIGH, area:architecture)
**Effort label:** M
**Date:** 2026-05-09
**Pattern reference:** PR #218 / Issue #68 (HomeViewModel.tagMap → MutableStateFlow + combine)

## Problem

`LabelsViewModel` (707 LOC, `app/src/main/java/com/paperless/scanner/ui/screens/labels/LabelsViewModel.kt`) holds the four source-of-truth lists for the Labels screen as plain mutable references:

```kotlin
private var allTags: List<EntityItem> = emptyList()
private var allCorrespondents: List<EntityItem> = emptyList()
private var allDocumentTypes: List<EntityItem> = emptyList()
private var allCustomFields: List<EntityItem> = emptyList()
```
(L103-L106)

Each one is written by a `viewModelScope.launch` collect block (`observeTagsReactively`, `observeCorrespondentsReactively`, `observeDocumentTypesReactively`, `observeCustomFieldsReactively`), and read concurrently by `getActiveEntities()` (L163), `applyCurrentFilters()` (L195), and `prepareDeleteEntity()` (L532). On the JVM these reads may observe stale references because the field is not `@Volatile`, and a late-subscribing `_uiState` collector cannot replay the most recent state because the data does not live in any `StateFlow`.

A second cost of the current shape: every state-changing UI method (`search`, `setSortOption`, `setFilterOption`, `setSortAndFilter`, `resetSortAndFilter`, `clearSearch`, `setEntityType`) must remember to call `applyCurrentFilters()` or the view falls out of sync. Forgetting one is a silent UI bug.

## Goal

Eliminate both classes of bug — the read-during-write race and the manual-trigger fragility — without changing the contract that `LabelsScreen` consumes (`uiState.entities`).

## Non-goals

- Decomposing `LabelsUiState` into separate StateFlows for `currentEntityType` / search / sort / filter (separate concern, not in #74's AC).
- Splitting `LabelsViewModel` (god-VM cleanup is tracked separately under F-042).
- CRUD-path test coverage (per scope decision: minimum test scope).
- Behaviour change for the AppLock screen / navigation / SavedStateHandle plumbing (not affected).

## Design

### Source-of-truth conversion

```kotlin
private val _allTags = MutableStateFlow<List<EntityItem>>(emptyList())
private val _allCorrespondents = MutableStateFlow<List<EntityItem>>(emptyList())
private val _allDocumentTypes = MutableStateFlow<List<EntityItem>>(emptyList())
private val _allCustomFields = MutableStateFlow<List<EntityItem>>(emptyList())
```

Reads use `_allXxx.value` (atomic + volatile-equivalent). Writes use atomic assignment `_allXxx.value = …`.

### Reactive pipeline

The "what list does the UI render right now" computation moves into a single `combine(...)` collected once in `init`:

```kotlin
private data class ListSettings(
    val type: EntityType,
    val query: String,
    val sort: LabelSortOption,
    val filter: LabelFilterOption
)

init {
    observeTagsReactively()
    observeCorrespondentsReactively()
    observeDocumentTypesReactively()
    observeCustomFieldsReactively()

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

`distinctUntilChanged()` on `settingsFlow` is load-bearing: `_uiState` changes for many reasons (loading flags, error, selection, modal state, derived `entities` itself). Without the distinct gate, the collector that *writes* `entities` would re-fire `combine` indirectly via `settingsFlow`. The distinct gate breaks that loop because `ListSettings` only depends on the four fields that should drive a recompute.

### Per-method changes

| Stelle | Before | After |
| --- | --- | --- |
| L103-L106 (4 vars) | `private var allXxx: List<EntityItem>` | `private val _allXxx = MutableStateFlow<List<EntityItem>>(emptyList())` |
| `observeXxxReactively()` × 4 | writes `var`; checks `if (currentEntityType == X)`; manually `_uiState.update { copy(entities = …, isLoading = false, error = null) }` | only `_allXxx.value = … .map { … }`. Per-tab `if` and the inline `_uiState.update` are deleted — combine handles both. |
| `applyCurrentFilters()` (L195) | manual recompute | **deleted** |
| `getActiveEntities()` (L163) | reads `var` | reads `_allXxx.value` |
| `applySearchFilterSortEntities(entities, state: LabelsUiState)` (L339) | takes whole `LabelsUiState` | takes `(entities, settings: ListSettings)`. Smaller surface, easier to test. |
| `setEntityType` / `search` / `setSortOption` / `setFilterOption` / `setSortAndFilter` / `resetSortAndFilter` / `clearSearch` | `_uiState.update {}` followed by `applyCurrentFilters()` | only `_uiState.update {}`. The combine pipeline reacts. |
| `resetState()` (L699) | clears 4 vars | `_allXxx.value = emptyList()` for each |
| `refresh()` (L379) | unchanged | unchanged — still owns `isLoading` and the network-error path |

### Loading + error state

`refresh()` continues to own `isLoading` (set true at start, false when all four awaits complete or the tag await fails). The four observers no longer flip `isLoading = false` or `error = null` — that responsibility now belongs entirely to `refresh()` and the CRUD `onFailure` paths.

This is a **small intentional behaviour change** vs. today: a fresh DB emit will no longer clear an existing network-error message. That is the correct semantic — a successful Room read does not retroactively prove that the network refresh succeeded, so it should not erase a network-failure error. The user explicitly approved this change during brainstorming.

### Late-subscriber correctness

`_uiState` is a `MutableStateFlow`, so a late subscriber receives the current value immediately on collection — including the most-recently-computed `entities`. Combined with `_allXxx.value` reads in `getActiveEntities()`, every consumer always observes a consistent snapshot. The race is gone.

### Loop / deadlock check

- `_uiState` change → `settingsFlow.map { … }.distinctUntilChanged()`:
  - If only `entities` (or `isLoading`, `error`, `selectedEntity`, `documentsForEntity`, `pendingDeleteEntity`, `customFieldsAvailable`) changed → `ListSettings` is unchanged → `distinctUntilChanged` swallows → no recompute → no loop.
  - If user changed `currentEntityType` / `searchQuery` / `sortOption` / `filterOption` → `ListSettings` changes → `combine` fires → `entities` recomputed → `_uiState.update` fires → `settingsFlow` re-evaluates → `ListSettings` is now stable → distinct swallows. One bounded round-trip, no oscillation.

## Test plan

New file `app/src/test/java/com/paperless/scanner/ui/screens/labels/LabelsViewModelTest.kt`. Stack: MockK + `kotlinx.coroutines.test.runTest` + Turbine, matching the `HomeViewModelTest` precedent. JUnit4 + `MainDispatcherRule` (project pattern from #205/#206/#208).

Four targeted tests, scoped to AC #3 only (per agreed minimum scope):

1. **`tab switch recomputes entities without manual trigger`** — populate `_allTags` and `_allCorrespondents` via mocked `tagRepository.observeTags()` and `correspondentRepository.observeCorrespondents()`; assert tag list is rendered; call `setEntityType(CORRESPONDENT)`; assert `uiState.entities` equals correspondents on the next emission. No call to any `applyCurrentFilters` (it does not exist anymore).
2. **`search query update flows through combine`** — populate, call `search("foo")`, await one `uiState` emission, assert the result is filtered by name-contains.
3. **`late subscriber observes current entities`** — mock observe-flows so the four `_allXxx` flows have populated values before the test subscribes, then collect `uiState`, assert the *first* emitted value already contains the populated tags. Verifies the StateFlow replay guarantee.
4. **`concurrent source emissions remain consistent`** — emit on the tag flow and the correspondent flow back-to-back via `Channel`-backed fakes, assert that no intermediate `uiState.entities` value contains the wrong entity type for the active tab. Verifies the race is gone.

Approx 150-200 LOC of test, no production-code branch tracking just for tests.

## Acceptance Criteria mapping

| AC from #74 | Covered by |
| --- | --- |
| Four mutable vars replaced with StateFlow | "Source-of-truth conversion" section |
| `displayedEntities` is derived via `combine()` with no manual triggers | "Reactive pipeline" section + deletion of `applyCurrentFilters()` |
| Tests verify reactive filter updates | Test plan tests 1, 2 |

The issue's exact wording proposes a separate public `displayedEntities: StateFlow<List<EntityItem>>`. We deliberately deviate by folding the derived list into `uiState.entities` (PR #218 precedent), which preserves the screen contract and minimises blast radius. The AC is still satisfied — the list **is** derived via `combine()` with no manual triggers; only the surface shape differs from the issue body's prose.

## Risks & open questions

- **Risk:** A consumer reading `_allXxx.value` before any source-flow emission gets `emptyList()`. This is the same as today's `var` initial state, so behaviour is preserved. `refresh()` still kicks off the fetch.
- **Risk:** Compose collectors on `uiState` will see one extra emission per state change (the combine output now writes back to `uiState`). This is structurally identical to PR #218 and did not regress that screen — `LabelsUiState`'s `data class` equality short-circuits redundant recompositions.
- **Open:** None. User approved the small loading/error semantic change above.

## Out of scope (won't be in the PR)

- LabelsScreen-side refactor (none needed — the `uiState.entities` contract is unchanged).
- ViewModel decomposition / size reduction.
- CRUD-path tests.
- Migration of any other ViewModel — separate issues if more `private var`-on-Flow patterns exist (Memory says #74 is the only remaining one of this family).
