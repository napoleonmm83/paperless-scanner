# Design — DocumentRepository Refactor (Issue #51, Phase 1)

**Date:** 2026-05-05
**Tracking issue:** [#51 — DocumentRepository is a 1349-line God-class](https://github.com/napoleonmm83/paperless-scanner/issues/51)
**Master plan reference:** [`docs/code-reviews/REFACTOR_DOCUMENT_REPOSITORY.md`](../../code-reviews/REFACTOR_DOCUMENT_REPOSITORY.md)
**Scope of this spec:** Sub-issue plan for all 5 phases + detailed design for Phase 1 only.

---

## 1. Goal

Decompose `DocumentRepository.kt` (1405 LOC, 40+ public/private methods, 12 caller sites) into 8 specialized repositories + 3 service classes per the master plan. **This spec covers only Phase 1** (extract 3 service classes from private helpers — pure internal extraction with no behavior change). Phases 2–5 are listed as sub-issues with stubs; each gets its own brainstorm + spec when scheduled.

**Non-goals:**
- No public API changes for DocumentRepository in Phase 1.
- No caller migration (Workers, ViewModels, SyncManager continue to inject `DocumentRepository`).
- No new functional behavior; only structural extraction.

---

## 2. Sub-Issue Structure on GitHub

`#51` remains the **parent tracking issue** with a checkbox list referencing each sub-issue. One sub-issue per service in Phase 1, then one sub-issue per repository in Phases 2–4, and one umbrella sub-issue for Phase 5.

```
#51 (parent — open until all sub-issues done)
├── #NEW-A  Phase 1.1: Extract ImageProcessorService          [S]
├── #NEW-B  Phase 1.2: Extract PdfGeneratorService            [S, blocked by A]
├── #NEW-C  Phase 1.3: Extract DocumentSerializer             [S]
├── #NEW-D  Phase 2.1: DocumentListRepository (+ A/B)         [M]
├── #NEW-E  Phase 2.2: DocumentCountRepository                [S]
├── #NEW-F  Phase 2.3: DocumentMetadataRepository             [M]
├── #NEW-G  Phase 3.1: TrashRepository                        [M]
├── #NEW-H  Phase 3.2: AuditRepository                        [S]
├── #NEW-I  Phase 3.3: DocumentSyncRepository (highest risk)  [M]
├── #NEW-J  Phase 4:   PermissionRepository                   [S]
└── #NEW-K  Phase 5:   Façade cleanup + ViewModel migration   [L, multi-PR]
```

Each sub-issue inherits `area:refactor` + `severity:critical` labels from #51 and gets a per-sub-issue effort label (`S` / `M` / `L`).

### Sub-issue body template

```markdown
## Parent
Sub-issue of #51 — DocumentRepository God-class refactor

## Scope (Phase X.Y: <name>)
<what gets extracted>

## Acceptance Criteria
- [ ] New service/repository at `<path>` with `@Inject constructor`
- [ ] DocumentRepository delegates to it; private methods removed
- [ ] DocumentRepository line count reduced by ≥ <N>
- [ ] AppModule.kt provides the new dependency (via @Inject auto-binding or @Provides)
- [ ] Existing `DocumentRepositoryTest.kt` stays green (no behavior change)
- [ ] New `<NewClass>Test.kt` with ≥ <N> tests covering happy path + error paths
- [ ] `validate-ci.sh` passes locally
- [ ] CodeRabbit findings actioned or explicitly skipped with rationale

## Out-of-scope
- <next phase items>
- ViewModel migration (Phase 5)

## Effort
S / M / L
```

Phases 2–5 sub-issues are **stubs** referencing the master plan; their detail design is deferred to dedicated brainstorm sessions when they are scheduled.

---

## 3. Phase 1 Architecture

Phase 1 is rein interner Refactor: 3 private helper-Methoden ziehen in eigene Service-Klassen, `DocumentRepository` ruft die Services via Hilt-injizierte Felder. Caller bleiben unberührt.

### 3.1 Target package layout

```
app/src/main/java/com/paperless/scanner/data/
├── repository/                       (existing — DocumentRepository et al.)
└── service/                          (NEW)
    ├── ImageProcessorService.kt
    ├── PdfGeneratorService.kt
    └── DocumentSerializer.kt
```

Rationale: `service/` is a separate package from `repository/` because services contain pure logic (image bytes, PDF assembly, JSON shape) without owning data sources. Keeps the seam between data-access (repositories) and pure transforms (services) explicit.

### 3.2 Class shapes

All three follow the existing repository pattern in this codebase: concrete class, `@Inject constructor`, `@Singleton` scope. No interfaces (matches `ServerStatusRepository`, `TagRepository`, etc.).

#### ImageProcessorService

```kotlin
// app/src/main/java/com/paperless/scanner/data/service/ImageProcessorService.kt
@Singleton
class ImageProcessorService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val crashlyticsHelper: CrashlyticsHelper
) {
    fun getImageBytesFromUri(uri: Uri): ByteArray { /* moved from DocumentRepository.kt:316-372 */ }
    fun getFileFromUri(uri: Uri): File           { /* moved from DocumentRepository.kt:373-388 */ }
    private fun calculateCompressionQuality(bitmap: Bitmap): Int { /* moved from DocumentRepository.kt:363-371 */ }
}
```

Contract:
- `getImageBytesFromUri` reads the URI, decodes with `inSampleSize` to keep ≤ 16MP, compresses to JPEG at quality determined by pixel count (>12MP→70, >8MP→75, >4MP→80, else 85), recycles bitmap. Throws `IllegalArgumentException` if `openInputStream` returns null, `IllegalStateException` if `decodeStream` returns null.
- `getFileFromUri` writes URI bytes to a timestamped JPG in `cacheDir` and returns the `File`. Throws `IllegalArgumentException` if `openInputStream` returns null.

#### PdfGeneratorService

```kotlin
// app/src/main/java/com/paperless/scanner/data/service/PdfGeneratorService.kt
@Singleton
class PdfGeneratorService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val imageProcessor: ImageProcessorService
) {
    fun createPdfFromImages(uris: List<Uri>): File { /* moved from DocumentRepository.kt:251-315 */ }
}
```

Contract: builds a multi-page PDF (iText) where each page is sized to its source image. If page 0 fails → rethrow `IllegalStateException`. If page > 0 fails → log + skip that page (graceful degradation per existing behavior). If `numberOfPages == 0` after all input → `IllegalStateException`. Cleans up the partial PDF file on any rethrown exception.

#### DocumentSerializer

```kotlin
// app/src/main/java/com/paperless/scanner/data/service/DocumentSerializer.kt
@Singleton
class DocumentSerializer @Inject constructor(
    private val gson: Gson
) {
    fun serializeCustomFieldsForUpload(customFields: Map<Int, Any>?): RequestBody?
    fun deserializeCachedTagIds(cachedJson: String?): List<Int>
}
```

Contract:
- `serializeCustomFieldsForUpload`: returns `null` for null/empty input. Otherwise maps to `[{"field": <id>, "value": <value>}, …]` JSON wrapped as `application/json` request body. Replaces 2 duplicated occurrences in DocumentRepository.kt (L113-115, L196-198).
- `deserializeCachedTagIds`: returns `emptyList()` for `null` or unparseable input; otherwise parses Gson `List<Int>`. Replaces L883-884.

### 3.3 DocumentRepository changes

```kotlin
// constructor signature gains 3 fields, loses none
class DocumentRepository @Inject constructor(
    // existing 10 deps...
    private val imageProcessor: ImageProcessorService,    // +
    private val pdfGenerator: PdfGeneratorService,        // +
    private val serializer: DocumentSerializer,           // +
)
```

Removed bodies:
- `private fun createPdfFromImages` → delegate to `pdfGenerator.createPdfFromImages(uris)`
- `private fun getImageBytesFromUri` → delegate to `imageProcessor.getImageBytesFromUri(uri)` (and the inline call inside the old `createPdfFromImages` becomes a service-internal call inside `PdfGeneratorService`)
- `private fun calculateCompressionQuality` → moves into `ImageProcessorService` (private)
- `private fun getFileFromUri` → delegate to `imageProcessor.getFileFromUri(uri)` (single caller in `uploadDocument` at L84)
- 2× inline `gson.toJson(customFieldsList).toRequestBody(...)` → `serializer.serializeCustomFieldsForUpload(customFields)`
- 1× inline `gson.fromJson<List<Int>>(cached.tags, listType)` → `serializer.deserializeCachedTagIds(cached.tags)`

Net DocumentRepository.kt reduction after all 3 PRs: ≥ 200 LOC (1405 → ≤ 1205 target).

### 3.4 Hilt wiring

`@Inject constructor` on each service auto-binds via Hilt. The `provideDocumentRepository` `@Provides` function in `AppModule.kt:333-344` gains 3 parameters (Hilt resolves them automatically — only the function signature changes, no module additions). No changes to `@Module` or `@InstallIn` declarations.

---

## 4. PR Sequence and Dependencies

| # | Branch | Sub-issue | Depends on | Approx LOC moved |
|---|--------|-----------|------------|-------------------|
| 1 | `refactor/51-extract-image-processor`  | #NEW-A | none           | ~110 |
| 2 | `refactor/51-extract-pdf-generator`    | #NEW-B | PR 1 merged    | ~70  |
| 3 | `refactor/51-extract-document-serializer` | #NEW-C | none (parallel-able with 1, but easier sequenced) | ~30 |

PR-2 must wait for PR-1 because `PdfGeneratorService` consumes `ImageProcessorService`. PR-3 is independent but kept last for sequence clarity.

Each PR follows the project's standard release flow (auto-deploy on merge to `main` per CLAUDE.md release section).

---

## 5. Testing strategy

### Existing safety net
`DocumentRepositoryTest.kt` (402 lines) MUST stay green throughout all 3 PRs. It exercises the public API of `DocumentRepository`, so any drift in delegation behavior fails fast. No new assertions added there.

### New test files (per service, dedicated)

| Test file | Framework | Min cases | Coverage targets |
|-----------|-----------|-----------|------------------|
| `ImageProcessorServiceTest.kt` | Robolectric + mockk | 10 | sample-size for ≤16MP / >16MP, all 4 compression-quality buckets, null `openInputStream`, null `decodeStream`, `getFileFromUri` happy path + error |
| `PdfGeneratorServiceTest.kt`   | Robolectric + mockk | 6  | 1-image PDF, 3-image PDF, page-0 failure rethrows, page-N>0 failure skipped, empty list rejects, cleanup on exception |
| `DocumentSerializerTest.kt`    | plain JUnit (no Robolectric) | 6  | empty/null custom fields → null body, single field shape, multiple fields, deserialize null → empty, deserialize valid JSON → list, deserialize malformed → empty |

Tests live in the mirror package: `app/src/test/java/com/paperless/scanner/data/service/`.

### Manual smoke (pre-merge, on-device)

For PR-1 and PR-2 only (PR-3 is JSON-only, covered by unit tests):
- Single-page upload via Scan flow (exercises `getFileFromUri`).
- Multi-page upload via Scan flow with 3+ pages (exercises full PDF pipeline + `getImageBytesFromUri`).

---

## 6. Validation per PR

| Check | When | Command |
|-------|------|---------|
| Existing repo tests green   | Before push  | `./gradlew testReleaseUnitTest` |
| New service tests green     | Before push  | `./gradlew :app:testReleaseUnitTest --tests *<NewClass>Test*` |
| Lint clean                  | Before push  | `./gradlew lintRelease` |
| Release build green         | Before push  | `./gradlew assembleRelease` |
| Full local CI               | Before push  | `./scripts/validate-ci.sh` (RELEASE variants per CLAUDE.md) |
| Manual on-device smoke      | Before merge | PR-1 + PR-2: scan + upload single & multi-page |
| CodeRabbit                  | After push   | Round-1 findings actioned or skipped with explicit rationale |

---

## 7. Risks and mitigations

| Risk | Severity | Mitigation |
|------|----------|------------|
| iText / Bitmap behavior subtly changes after extraction (e.g. resource lifetime) | Medium | Keep method bodies byte-identical; only enclosing class changes. Manual on-device smoke before merge. Existing `DocumentRepositoryTest.kt` is the regression net. |
| `getFileFromUri` was called only in single-page upload path; missing it would silently break upload | Medium | New `ImageProcessorServiceTest` covers `getFileFromUri` happy path + error; manual smoke for single-page upload. |
| Hilt cyclic dependency if a service grows to need a repository later | Low | Phase 1 services depend only on `Context`, `Gson`, `CrashlyticsHelper`, and (Pdf→Image) — no repo deps. Will revisit if Phase 2+ needs reverse direction. |
| Custom-field JSON shape diverges between the 2 dedup'd call-sites | Low | Both sites currently produce identical structure (verified at L113-115 and L196-198); `DocumentSerializerTest` snapshots the shape. |
| Existing `DocumentRepositoryTest.kt` doesn't actually exercise PDF/image paths and would miss regressions | Medium | Verify before PR-1 by reading the existing test file; add Robolectric coverage in the new service tests as the real safety net rather than relying on the existing repo test. |

---

## 8. Rollback strategy

Phase 1 is purely internal extraction — no public API change, no schema change, no migration. Each PR is reverted by a single `git revert <sha>` which restores the private methods in `DocumentRepository.kt` and removes the new service file. No data risk.

---

## 9. Out-of-scope (explicit)

- **Phase 2** (`DocumentListRepository`, `DocumentCountRepository`, `DocumentMetadataRepository`): requires A/B validation against live Paperless-ngx server, separate spec.
- **Phase 3.1/3.2** (`TrashRepository`, `AuditRepository`): straightforward but deferred until Phase 2 lands.
- **Phase 3.3** (`DocumentSyncRepository`): highest risk per master plan (offline queue + concurrent mutations); requires its own brainstorm + spec including concurrent-mutation tests.
- **Phase 4** (`PermissionRepository`): deferred until Phase 3 lands.
- **Phase 5** (façade cleanup + ViewModel migration): deferred multi-PR umbrella.
- **Caller migration of any kind**: Workers, ViewModels, SyncManager all keep injecting `DocumentRepository` (no callers touched in Phase 1).

---

## 10. Acceptance Criteria for Phase 1 (overall, across the 3 PRs)

- [ ] 3 new files at `data/service/` with `@Inject constructor`
- [ ] DocumentRepository.kt reduced by ≥ 200 LOC
- [ ] All previously private helpers (`createPdfFromImages`, `getImageBytesFromUri`, `calculateCompressionQuality`, `getFileFromUri`) and 2× custom-field serialization + 1× cached-tag deserialization are delegated to the new services
- [ ] `DocumentRepositoryTest.kt` green throughout
- [ ] 3 new test files with combined ≥ 22 test cases
- [ ] `validate-ci.sh` green on each PR
- [ ] All 3 sub-issues (#NEW-A/B/C) closed via "Closes #…" merges
- [ ] #51 parent issue checklist updated to reflect Phase 1 done; Phases 2-5 sub-issues remain open as stubs
