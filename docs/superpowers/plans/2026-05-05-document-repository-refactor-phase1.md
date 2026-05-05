# DocumentRepository Refactor — Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Decompose `DocumentRepository.kt` (1405 LOC) by extracting 3 service classes into `data/service/`, opened as 3 sequential PRs against `main`. Phase 1 is pure internal extraction — no caller change, no public API change.

**Architecture:** Concrete `@Singleton` classes with `@Inject constructor`, located at `app/src/main/java/com/paperless/scanner/data/service/`. `DocumentRepository` gains 3 new fields and delegates the moved logic. Hilt resolves all bindings; `AppModule.provideDocumentRepository` gains 3 parameters.

**Tech Stack:** Kotlin 2.0, Hilt, Robolectric + mockk for service tests, JUnit 4. iText (PDF), `BitmapFactory` (image), Gson (serialization).

**Spec reference:** `docs/superpowers/specs/2026-05-05-document-repository-refactor-design.md`

**GitHub parent issue:** [#51](https://github.com/napoleonmm83/paperless-scanner/issues/51)

---

## Task 0: Create GitHub Sub-Issues for #51

**Files:** none (GitHub state only)

This is a one-time setup. After completion, the engineer notes the assigned issue numbers (`#NEW-A`, `#NEW-B`, `#NEW-C`, etc.) and substitutes them into Tasks 1–3 below.

- [ ] **Step 0.1: Verify gh CLI is authenticated**

Run: `gh auth status`
Expected: shows `Logged in to github.com as napoleonmm83`

- [ ] **Step 0.2: Create the 3 Phase-1 sub-issues**

Run each command. Capture the issue number from the URL printed after each `gh issue create`.

```bash
gh issue create \
  --repo napoleonmm83/paperless-scanner \
  --title "[refactor] Phase 1.1: Extract ImageProcessorService from DocumentRepository (sub of #51)" \
  --label "area:refactor,severity:critical" \
  --milestone "Code Review 2026-05" \
  --body "$(cat <<'EOF'
## Parent
Sub-issue of #51 — DocumentRepository God-class refactor

## Scope
Extract image-handling private helpers from `DocumentRepository.kt` (lines 316-388) into a new `ImageProcessorService` at `app/src/main/java/com/paperless/scanner/data/service/ImageProcessorService.kt`.

Methods moved:
- `getImageBytesFromUri(uri: Uri): ByteArray`  (L316-372)
- `getFileFromUri(uri: Uri): File`              (L373-388)
- `calculateCompressionQuality(bitmap: Bitmap): Int` (private, L363-371)

## Acceptance Criteria
- [ ] `ImageProcessorService.kt` created at `data/service/` with `@Singleton @Inject constructor`
- [ ] `DocumentRepository` gains `imageProcessor` field and delegates; private methods removed
- [ ] `DocumentRepository.kt` reduced by ≥ 90 LOC
- [ ] `AppModule.provideDocumentRepository` accepts `imageProcessor` parameter
- [ ] Existing `DocumentRepositoryTest.kt` stays green
- [ ] New `ImageProcessorServiceTest.kt` with ≥ 10 tests (Robolectric + mockk)
- [ ] `validate-ci.sh` passes locally
- [ ] CodeRabbit findings actioned or explicitly skipped with rationale

## Out-of-scope
- PdfGeneratorService extraction (Phase 1.2)
- DocumentSerializer extraction (Phase 1.3)
- Caller migration (Phase 5)

## Effort
S
EOF
)"

gh issue create \
  --repo napoleonmm83/paperless-scanner \
  --title "[refactor] Phase 1.2: Extract PdfGeneratorService from DocumentRepository (sub of #51)" \
  --label "area:refactor,severity:critical" \
  --milestone "Code Review 2026-05" \
  --body "$(cat <<'EOF'
## Parent
Sub-issue of #51 — DocumentRepository God-class refactor

## Scope
Extract `createPdfFromImages` from `DocumentRepository.kt` (L251-315) into `PdfGeneratorService` at `app/src/main/java/com/paperless/scanner/data/service/PdfGeneratorService.kt`. Service depends on `ImageProcessorService` (Phase 1.1).

## Blocked by
Phase 1.1 (ImageProcessorService extraction must be merged first)

## Acceptance Criteria
- [ ] `PdfGeneratorService.kt` created at `data/service/` with `@Singleton @Inject constructor(@ApplicationContext context, imageProcessor: ImageProcessorService)`
- [ ] `DocumentRepository` gains `pdfGenerator` field and delegates; `createPdfFromImages` removed
- [ ] `DocumentRepository.kt` reduced by ≥ 65 LOC (cumulative ≥ 155 incl. Phase 1.1)
- [ ] `AppModule.provideDocumentRepository` accepts `pdfGenerator` parameter
- [ ] Existing `DocumentRepositoryTest.kt` stays green
- [ ] New `PdfGeneratorServiceTest.kt` with ≥ 6 tests (Robolectric + mockk)
- [ ] `validate-ci.sh` passes locally
- [ ] CodeRabbit findings actioned or explicitly skipped

## Out-of-scope
- DocumentSerializer extraction (Phase 1.3)

## Effort
S
EOF
)"

gh issue create \
  --repo napoleonmm83/paperless-scanner \
  --title "[refactor] Phase 1.3: Extract DocumentSerializer from DocumentRepository (sub of #51)" \
  --label "area:refactor,severity:critical" \
  --milestone "Code Review 2026-05" \
  --body "$(cat <<'EOF'
## Parent
Sub-issue of #51 — DocumentRepository God-class refactor

## Scope
Centralize Gson custom-field + tag JSON serialization that is currently scattered in `DocumentRepository.kt` (L113-115, L196-198, L883-884) into a new `DocumentSerializer` at `app/src/main/java/com/paperless/scanner/data/service/DocumentSerializer.kt`.

Bonus: deduplicates 2 identical `gson.toJson(customFieldsList).toRequestBody(...)` call-sites.

## Acceptance Criteria
- [ ] `DocumentSerializer.kt` created at `data/service/` with `@Singleton @Inject constructor(gson: Gson)`
- [ ] Two methods: `serializeCustomFieldsForUpload(customFields: Map<Int, Any>?): RequestBody?`, `deserializeCachedTagIds(cachedJson: String?): List<Int>`
- [ ] `DocumentRepository` gains `serializer` field; 3 inline gson uses replaced
- [ ] `DocumentRepository.kt` reduced by ≥ 25 LOC (cumulative ≥ 180)
- [ ] `AppModule.provideDocumentRepository` accepts `serializer` parameter
- [ ] Existing `DocumentRepositoryTest.kt` stays green
- [ ] New `DocumentSerializerTest.kt` with ≥ 6 tests (plain JUnit, no Robolectric)
- [ ] `validate-ci.sh` passes locally
- [ ] CodeRabbit findings actioned or explicitly skipped

## Out-of-scope
- Removing `gson` parameter from `DocumentRepository` constructor — defer; verify no other usages remain in a follow-up.

## Effort
S
EOF
)"
```

Expected: 3 GitHub URLs printed. Note the issue numbers as `<ISSUE_A>`, `<ISSUE_B>`, `<ISSUE_C>` for substitution into Tasks 1-3.

- [ ] **Step 0.3: Create the 8 stub sub-issues for Phases 2-5**

These are stubs only — they reference the master plan and are not implemented in this session. Same `gh issue create` pattern; use the body template from `docs/superpowers/specs/2026-05-05-document-repository-refactor-design.md` Section 2. One issue per:
- Phase 2.1: DocumentListRepository
- Phase 2.2: DocumentCountRepository
- Phase 2.3: DocumentMetadataRepository
- Phase 3.1: TrashRepository
- Phase 3.2: AuditRepository
- Phase 3.3: DocumentSyncRepository (highest risk)
- Phase 4: PermissionRepository
- Phase 5: Façade cleanup + ViewModel migration

Each stub body should contain only:
```markdown
## Parent
Sub-issue of #51 — DocumentRepository God-class refactor

## Scope (deferred)
See Master Plan: `docs/code-reviews/REFACTOR_DOCUMENT_REPOSITORY.md` — Phase X.Y.

This issue is a stub. Detailed design + implementation plan will be authored in a dedicated brainstorm session before this work begins.

## Effort
<S/M/L per master plan>
```

- [ ] **Step 0.4: Update parent issue #51 with sub-issue checklist**

```bash
gh issue edit 51 --repo napoleonmm83/paperless-scanner --body "$(cat <<EOF
## Context

Single class spans PDF creation, image compression, document CRUD, paging, search, trash, permissions, notes, history, user/group management, tag operations and pending-change tracking. SRP and testability are wrecked.

## Location
- File: \`app/src/main/java/com/paperless/scanner/data/repository/DocumentRepository.kt\`
- Lines: L57-L1405
- Target: \`DocumentRepository\`

## Decomposition Plan
See \`docs/code-reviews/REFACTOR_DOCUMENT_REPOSITORY.md\` and design spec \`docs/superpowers/specs/2026-05-05-document-repository-refactor-design.md\`.

## Sub-issues
- [ ] #<ISSUE_A> Phase 1.1: Extract ImageProcessorService
- [ ] #<ISSUE_B> Phase 1.2: Extract PdfGeneratorService
- [ ] #<ISSUE_C> Phase 1.3: Extract DocumentSerializer
- [ ] #<ISSUE_D> Phase 2.1: DocumentListRepository
- [ ] #<ISSUE_E> Phase 2.2: DocumentCountRepository
- [ ] #<ISSUE_F> Phase 2.3: DocumentMetadataRepository
- [ ] #<ISSUE_G> Phase 3.1: TrashRepository
- [ ] #<ISSUE_H> Phase 3.2: AuditRepository
- [ ] #<ISSUE_I> Phase 3.3: DocumentSyncRepository (highest risk)
- [ ] #<ISSUE_J> Phase 4: PermissionRepository
- [ ] #<ISSUE_K> Phase 5: Façade + ViewModel migration

## Acceptance Criteria
- [ ] DocumentRepository becomes a delegating façade (≤ 200 LOC after Phase 5)
- [ ] Each new repository ≤ 250 LOC with single responsibility
- [ ] All existing callers compile unchanged via façade
- [ ] Each new repository has dedicated unit tests
- [ ] Phased migration committed in atomic PRs
- [ ] Documentation updated in \`docs/TECHNICAL.md\`

**Effort:** XL
EOF
)"
```

Substitute the actual issue numbers captured in steps 0.2 and 0.3 before running.

---

## Task 1: PR-1 — Extract ImageProcessorService

**Files:**
- Create: `app/src/main/java/com/paperless/scanner/data/service/ImageProcessorService.kt`
- Modify: `app/src/main/java/com/paperless/scanner/data/repository/DocumentRepository.kt`
- Modify: `app/src/main/java/com/paperless/scanner/di/AppModule.kt`
- Test: `app/src/test/java/com/paperless/scanner/data/service/ImageProcessorServiceTest.kt`

- [ ] **Step 1.1: Create branch from updated main**

```bash
git checkout main
git pull --rebase
git checkout -b refactor/51-extract-image-processor
```

- [ ] **Step 1.2: Create ImageProcessorService.kt with the moved bodies**

Create file `app/src/main/java/com/paperless/scanner/data/service/ImageProcessorService.kt`:

```kotlin
package com.paperless.scanner.data.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.paperless.scanner.R
import com.paperless.scanner.data.analytics.CrashlyticsHelper
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Image-handling service extracted from DocumentRepository as part of issue #51 Phase 1.1.
 *
 * Contract:
 * - getImageBytesFromUri: reads URI, sample-decodes to ≤16MP, JPEG-compresses with
 *   pixel-count-based quality, recycles bitmap. Throws IllegalArgumentException on null
 *   input stream, IllegalStateException on null bitmap decode.
 * - getFileFromUri: copies URI bytes to a timestamped JPG in cacheDir.
 */
@Singleton
class ImageProcessorService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val crashlyticsHelper: CrashlyticsHelper
) {
    fun getImageBytesFromUri(uri: Uri): ByteArray {
        crashlyticsHelper.logActionBreadcrumb("IMAGE_PROCESS", uri.lastPathSegment ?: "unknown")
        // First pass: Get image dimensions without loading into memory
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BitmapFactory.decodeStream(stream, null, options)
        }

        // Calculate sample size for large images to prevent OOM
        val maxPixels = 16_000_000L // 16MP max
        val imagePixels = options.outWidth.toLong() * options.outHeight.toLong()
        val sampleSize = if (imagePixels > maxPixels) {
            var sample = 1
            while ((options.outWidth / sample) * (options.outHeight / sample) > maxPixels) {
                sample *= 2
            }
            sample
        } else {
            1
        }

        // Second pass: Load the actual bitmap with calculated sample size
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
        }

        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException(context.getString(R.string.error_open_input_stream))

        val bitmap = inputStream.use { stream ->
            BitmapFactory.decodeStream(stream, null, decodeOptions)
                ?: throw IllegalStateException(context.getString(R.string.error_decode_image))
        }

        return try {
            val quality = calculateCompressionQuality(bitmap)
            ByteArrayOutputStream().use { outputStream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
                outputStream.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    fun getFileFromUri(uri: Uri): File {
        val inputStream = context.contentResolver.openInputStream(uri)
            ?: throw IllegalArgumentException(context.getString(R.string.error_open_input_stream))

        val fileName = "document_${System.currentTimeMillis()}.jpg"
        val tempFile = File(context.cacheDir, fileName)

        FileOutputStream(tempFile).use { outputStream ->
            inputStream.copyTo(outputStream)
        }
        inputStream.close()

        return tempFile
    }

    private fun calculateCompressionQuality(bitmap: Bitmap): Int {
        val pixels = bitmap.width.toLong() * bitmap.height.toLong()
        return when {
            pixels > 12_000_000 -> 70  // >12MP: aggressive compression
            pixels > 8_000_000 -> 75   // >8MP: moderate compression
            pixels > 4_000_000 -> 80   // >4MP: light compression
            else -> 85                  // <=4MP: high quality
        }
    }
}
```

- [ ] **Step 1.3: Verify the new file compiles in isolation**

Run: `./gradlew compileReleaseKotlin --no-daemon`
Expected: BUILD SUCCESSFUL.

If compile fails because `ImageProcessorService` is unused — that's fine, ignore for this step. We only care that the new file itself is valid.

- [ ] **Step 1.4: Modify DocumentRepository.kt — add field, delegate, remove private methods**

Open `app/src/main/java/com/paperless/scanner/data/repository/DocumentRepository.kt`.

(a) Add import below the existing imports:
```kotlin
import com.paperless.scanner.data.service.ImageProcessorService
```

(b) Add field to constructor (after `crashlyticsHelper`):
```kotlin
class DocumentRepository @Inject constructor(
    private val context: Context,
    private val api: PaperlessApi,
    private val cachedDocumentDao: CachedDocumentDao,
    private val cachedTagDao: CachedTagDao,
    private val cachedTaskDao: CachedTaskDao,
    private val pendingChangeDao: PendingChangeDao,
    private val networkMonitor: NetworkMonitor,
    private val serverHealthMonitor: ServerHealthMonitor,
    private val gson: Gson,
    private val crashlyticsHelper: CrashlyticsHelper,
    private val imageProcessor: ImageProcessorService   // NEW
) {
```

(c) Replace inline call inside `createPdfFromImages` (currently around L261):
```kotlin
val imageBytes = getImageBytesFromUri(uri)
```
→ becomes:
```kotlin
val imageBytes = imageProcessor.getImageBytesFromUri(uri)
```

(d) Replace inline call inside `uploadDocument` (around L84):
```kotlin
val file = getFileFromUri(uri)
```
→ becomes:
```kotlin
val file = imageProcessor.getFileFromUri(uri)
```

(e) Delete the 3 private methods entirely from DocumentRepository.kt:
- `private fun getImageBytesFromUri(uri: Uri): ByteArray { ... }` (L316-372)
- `private fun calculateCompressionQuality(bitmap: Bitmap): Int { ... }` (L363-371)
- `private fun getFileFromUri(uri: Uri): File { ... }` (L373-388)

(f) Remove now-unused imports from `DocumentRepository.kt`:
```kotlin
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.FileOutputStream
```
(Verify each is unused after the deletion before removing — they may be referenced elsewhere in the file. Use `Grep` to confirm: e.g. `Grep("BitmapFactory", path=DocumentRepository.kt)`. If the only matches are in deleted bodies, remove the import.)

- [ ] **Step 1.5: Modify AppModule.kt — add `imageProcessor` parameter to provideDocumentRepository**

Open `app/src/main/java/com/paperless/scanner/di/AppModule.kt`. Locate the `provideDocumentRepository` function (around L333).

Replace this block:
```kotlin
@Provides
@Singleton
fun provideDocumentRepository(
    @ApplicationContext context: Context,
    api: PaperlessApi,
    cachedDocumentDao: CachedDocumentDao,
    cachedTagDao: CachedTagDao,
    cachedTaskDao: CachedTaskDao,
    pendingChangeDao: PendingChangeDao,
    networkMonitor: NetworkMonitor,
    serverHealthMonitor: ServerHealthMonitor,
    gson: Gson,
    crashlyticsHelper: CrashlyticsHelper
): DocumentRepository = DocumentRepository(context, api, cachedDocumentDao, cachedTagDao, cachedTaskDao, pendingChangeDao, networkMonitor, serverHealthMonitor, gson, crashlyticsHelper)
```

with:
```kotlin
@Provides
@Singleton
fun provideDocumentRepository(
    @ApplicationContext context: Context,
    api: PaperlessApi,
    cachedDocumentDao: CachedDocumentDao,
    cachedTagDao: CachedTagDao,
    cachedTaskDao: CachedTaskDao,
    pendingChangeDao: PendingChangeDao,
    networkMonitor: NetworkMonitor,
    serverHealthMonitor: ServerHealthMonitor,
    gson: Gson,
    crashlyticsHelper: CrashlyticsHelper,
    imageProcessor: ImageProcessorService
): DocumentRepository = DocumentRepository(context, api, cachedDocumentDao, cachedTagDao, cachedTaskDao, pendingChangeDao, networkMonitor, serverHealthMonitor, gson, crashlyticsHelper, imageProcessor)
```

Add the import at the top of AppModule.kt:
```kotlin
import com.paperless.scanner.data.service.ImageProcessorService
```

- [ ] **Step 1.6: Compile the project**

Run: `./gradlew compileReleaseKotlin --no-daemon`
Expected: BUILD SUCCESSFUL with no errors. If errors mention missing imports or wrong signatures, fix them and re-run.

- [ ] **Step 1.7: Write the failing test file**

Create `app/src/test/java/com/paperless/scanner/data/service/ImageProcessorServiceTest.kt`:

```kotlin
package com.paperless.scanner.data.service

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.paperless.scanner.R
import com.paperless.scanner.data.analytics.CrashlyticsHelper
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.slot
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ImageProcessorServiceTest {

    private lateinit var context: Context
    private lateinit var contentResolver: ContentResolver
    private lateinit var crashlyticsHelper: CrashlyticsHelper
    private lateinit var service: ImageProcessorService
    private lateinit var tempCacheDir: File

    private val testUri: Uri = Uri.parse("content://test/image.jpg")

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        contentResolver = mockk(relaxed = true)
        crashlyticsHelper = mockk(relaxed = true)
        tempCacheDir = createTempDir(prefix = "imgproc-test")

        every { context.contentResolver } returns contentResolver
        every { context.cacheDir } returns tempCacheDir
        every { context.getString(R.string.error_open_input_stream) } returns "open_input_stream_failed"
        every { context.getString(R.string.error_decode_image) } returns "decode_image_failed"

        service = ImageProcessorService(context, crashlyticsHelper)
    }

    @After
    fun teardown() {
        tempCacheDir.deleteRecursively()
    }

    // ---- getImageBytesFromUri ----

    @Test
    fun `getImageBytesFromUri throws IllegalArgumentException when openInputStream returns null on second pass`() {
        // First pass call (decodeBounds) is permissive; second pass (full decode) returns null → exception
        every { contentResolver.openInputStream(testUri) } returns null

        val ex = assertThrows(IllegalArgumentException::class.java) {
            service.getImageBytesFromUri(testUri)
        }
        assertEquals("open_input_stream_failed", ex.message)
    }

    @Test
    fun `getImageBytesFromUri throws IllegalStateException when BitmapFactory decodeStream returns null`() {
        val emptyStream: InputStream = ByteArrayInputStream(ByteArray(0))
        every { contentResolver.openInputStream(testUri) } returns emptyStream

        mockkStatic(BitmapFactory::class)
        every { BitmapFactory.decodeStream(any(), null, any()) } returns null

        try {
            val ex = assertThrows(IllegalStateException::class.java) {
                service.getImageBytesFromUri(testUri)
            }
            assertEquals("decode_image_failed", ex.message)
        } finally {
            unmockkStatic(BitmapFactory::class)
        }
    }

    @Test
    fun `getImageBytesFromUri logs breadcrumb with last URI segment`() {
        val emptyStream: InputStream = ByteArrayInputStream(ByteArray(0))
        every { contentResolver.openInputStream(testUri) } returns emptyStream
        mockkStatic(BitmapFactory::class)
        every { BitmapFactory.decodeStream(any(), null, any()) } returns null

        try {
            runCatching { service.getImageBytesFromUri(testUri) }
            io.mockk.verify { crashlyticsHelper.logActionBreadcrumb("IMAGE_PROCESS", "image.jpg") }
        } finally {
            unmockkStatic(BitmapFactory::class)
        }
    }

    @Test
    fun `getImageBytesFromUri returns compressed JPEG bytes for valid bitmap`() {
        val bitmap = mockk<Bitmap>(relaxed = true)
        every { bitmap.width } returns 1000
        every { bitmap.height } returns 1000
        every { bitmap.compress(Bitmap.CompressFormat.JPEG, any(), any()) } answers {
            (thirdArg<java.io.OutputStream>()).write(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte()))
            true
        }

        every { contentResolver.openInputStream(testUri) } returns ByteArrayInputStream(ByteArray(0))
        mockkStatic(BitmapFactory::class)
        every { BitmapFactory.decodeStream(any(), null, any()) } returns bitmap

        try {
            val bytes = service.getImageBytesFromUri(testUri)
            assertNotNull(bytes)
            assertEquals(3, bytes.size) // we wrote 3 bytes via compress mock
            io.mockk.verify { bitmap.recycle() }
        } finally {
            unmockkStatic(BitmapFactory::class)
        }
    }

    // ---- compression-quality bucket coverage (via getImageBytesFromUri) ----

    @Test
    fun `compression quality is 85 for small images (≤4MP)`() {
        val capturedQuality = exerciseCompressionWithDimensions(width = 1000, height = 1000) // 1MP
        assertEquals(85, capturedQuality)
    }

    @Test
    fun `compression quality is 80 for medium images (>4MP)`() {
        val capturedQuality = exerciseCompressionWithDimensions(width = 3000, height = 2000) // 6MP
        assertEquals(80, capturedQuality)
    }

    @Test
    fun `compression quality is 75 for large images (>8MP)`() {
        val capturedQuality = exerciseCompressionWithDimensions(width = 4000, height = 3000) // 12MP
        // Note: pixel count is exactly 12_000_000 → falls into >8MP bucket per `>` boundary.
        assertEquals(75, capturedQuality)
    }

    @Test
    fun `compression quality is 70 for very large images (>12MP)`() {
        val capturedQuality = exerciseCompressionWithDimensions(width = 5000, height = 4000) // 20MP downsampled
        // After sample-size-2: 2500x2000 = 5MP. >4MP -> 80. We test the BUCKET via mocked dimensions
        // keeping bitmap at >12MP shape: pass post-decode bitmap directly at 5000x3000 = 15MP.
        // Adjust: use direct bitmap with width=5000,height=3000.
        val q = exerciseCompressionWithDimensions(width = 5000, height = 3000)
        assertEquals(70, q)
    }

    // ---- getFileFromUri ----

    @Test
    fun `getFileFromUri writes bytes to a new file in cacheDir`() {
        val payload = "hello-bytes".toByteArray()
        every { contentResolver.openInputStream(testUri) } returns ByteArrayInputStream(payload)

        val file = service.getFileFromUri(testUri)

        assertTrue(file.exists())
        assertEquals(tempCacheDir.absolutePath, file.parentFile?.absolutePath)
        assertTrue(file.name.startsWith("document_"))
        assertTrue(file.name.endsWith(".jpg"))
        assertArrayEquals(payload, file.readBytes())
    }

    @Test
    fun `getFileFromUri throws IllegalArgumentException when openInputStream returns null`() {
        every { contentResolver.openInputStream(testUri) } returns null

        val ex = assertThrows(IllegalArgumentException::class.java) {
            service.getFileFromUri(testUri)
        }
        assertEquals("open_input_stream_failed", ex.message)
    }

    @Test
    fun `getFileFromUri creates uniquely named files when called rapidly`() {
        every { contentResolver.openInputStream(testUri) } returns ByteArrayInputStream("a".toByteArray()) andThen ByteArrayInputStream("b".toByteArray())
        val f1 = service.getFileFromUri(testUri)
        Thread.sleep(2) // System.currentTimeMillis() resolution
        val f2 = service.getFileFromUri(testUri)
        assertTrue("Files should differ in name", f1.name != f2.name)
    }

    // ---- helpers ----

    /** Drives getImageBytesFromUri end-to-end with a synthetic bitmap and returns the JPEG quality used. */
    private fun exerciseCompressionWithDimensions(width: Int, height: Int): Int {
        val bitmap = mockk<Bitmap>(relaxed = true)
        every { bitmap.width } returns width
        every { bitmap.height } returns height
        val qualitySlot = slot<Int>()
        every { bitmap.compress(Bitmap.CompressFormat.JPEG, capture(qualitySlot), any()) } returns true

        every { contentResolver.openInputStream(testUri) } returns ByteArrayInputStream(ByteArray(0))
        mockkStatic(BitmapFactory::class)
        every { BitmapFactory.decodeStream(any(), null, any()) } returns bitmap

        return try {
            service.getImageBytesFromUri(testUri)
            qualitySlot.captured
        } finally {
            unmockkStatic(BitmapFactory::class)
        }
    }
}
```

- [ ] **Step 1.8: Run the new test suite**

Run: `./gradlew testReleaseUnitTest --tests "com.paperless.scanner.data.service.ImageProcessorServiceTest" --no-daemon`
Expected: All tests pass. If any fail, debug and fix the test or service implementation. Do NOT change behavior of moved methods — fix the test instead.

- [ ] **Step 1.9: Run the existing DocumentRepositoryTest to ensure no regression**

Run: `./gradlew testReleaseUnitTest --tests "com.paperless.scanner.data.repository.DocumentRepositoryTest" --no-daemon`
Expected: All existing tests still pass. If any fail, the delegation in DocumentRepository.kt is incorrect — review Step 1.4 changes against the original line numbers.

- [ ] **Step 1.10: Run full local CI**

Run: `./scripts/validate-ci.sh`
Expected: All phases (Validation, Build & Test, Lint) pass. If failure, fix root cause — never use `--no-verify` per CLAUDE.md.

- [ ] **Step 1.11: Commit**

```bash
git add \
  app/src/main/java/com/paperless/scanner/data/service/ImageProcessorService.kt \
  app/src/main/java/com/paperless/scanner/data/repository/DocumentRepository.kt \
  app/src/main/java/com/paperless/scanner/di/AppModule.kt \
  app/src/test/java/com/paperless/scanner/data/service/ImageProcessorServiceTest.kt

git commit -m "$(cat <<'EOF'
refactor(repository): extract ImageProcessorService from DocumentRepository

Phase 1.1 of #51 — moves getImageBytesFromUri, getFileFromUri, and
calculateCompressionQuality (private) from DocumentRepository.kt into
a new data/service/ImageProcessorService.kt. DocumentRepository delegates
through a new injected field; AppModule.provideDocumentRepository gains
the imageProcessor parameter.

- DocumentRepository.kt reduced by ~110 LOC (no public API change)
- New ImageProcessorServiceTest.kt: 10 Robolectric+mockk cases covering
  null input stream, null bitmap decode, all 4 compression-quality
  buckets, getFileFromUri happy path + error
- Existing DocumentRepositoryTest.kt remains green

Closes #<ISSUE_A>
Sub of #51
EOF
)"
```

Substitute `<ISSUE_A>` with the issue number from Task 0.

- [ ] **Step 1.12: Push and open PR**

```bash
git push -u origin refactor/51-extract-image-processor

gh pr create \
  --repo napoleonmm83/paperless-scanner \
  --base main \
  --head refactor/51-extract-image-processor \
  --title "refactor: extract ImageProcessorService (Phase 1.1 of #51)" \
  --body "$(cat <<'EOF'
## Summary
- Extracts 3 private image-handling helpers from DocumentRepository.kt into a new `ImageProcessorService` at `data/service/`.
- DocumentRepository delegates through a new `imageProcessor: ImageProcessorService` field; AppModule wiring updated.
- Adds 10 Robolectric+mockk tests covering the new service surface.

## Closes
- Closes #<ISSUE_A>
- Sub of #51

## Test plan
- [x] `./gradlew testReleaseUnitTest --tests *ImageProcessorServiceTest*` green
- [x] `./gradlew testReleaseUnitTest --tests *DocumentRepositoryTest*` green (no regression)
- [x] `./scripts/validate-ci.sh` green
- [ ] Manual on-device: single-page upload via Scan flow
- [ ] Manual on-device: multi-page upload via Scan flow

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 1.13: Wait for CI + CodeRabbit, action findings**

Wait for: CI green + CodeRabbit review posted (typically 5-10 min).
Action: review CodeRabbit's actionable findings — fix them in a follow-up commit on the same branch, OR explicitly skip with rationale comment per the project's Sprint-1 pattern.

- [ ] **Step 1.14: Merge after approval**

Once CI + CodeRabbit are clean, squash-merge via GitHub UI. The pre-push hook auto-rebases for any version-bump commits from the auto-deploy workflow.

---

## Task 2: PR-2 — Extract PdfGeneratorService

**Prerequisite:** Task 1 PR merged to main.

**Files:**
- Create: `app/src/main/java/com/paperless/scanner/data/service/PdfGeneratorService.kt`
- Modify: `app/src/main/java/com/paperless/scanner/data/repository/DocumentRepository.kt`
- Modify: `app/src/main/java/com/paperless/scanner/di/AppModule.kt`
- Test: `app/src/test/java/com/paperless/scanner/data/service/PdfGeneratorServiceTest.kt`

- [ ] **Step 2.1: Sync with main and create branch**

```bash
git checkout main
git pull --rebase
git checkout -b refactor/51-extract-pdf-generator
```

- [ ] **Step 2.2: Create PdfGeneratorService.kt with the moved body**

Create file `app/src/main/java/com/paperless/scanner/data/service/PdfGeneratorService.kt`:

```kotlin
package com.paperless.scanner.data.service

import android.content.Context
import android.net.Uri
import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document as ITextDocument
import com.itextpdf.layout.element.Image
import com.paperless.scanner.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Multi-page PDF assembly service extracted from DocumentRepository as part of
 * issue #51 Phase 1.2. Delegates image-byte loading to ImageProcessorService.
 *
 * Contract:
 * - Page 0 failure rethrows as IllegalStateException (cannot create empty PDF).
 * - Page N>0 failure logs and skips (graceful — partial PDF is better than none).
 * - Empty result (numberOfPages == 0) throws IllegalStateException.
 * - Cleans up the partial PDF file on any rethrown exception.
 */
@Singleton
class PdfGeneratorService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val imageProcessor: ImageProcessorService
) {
    fun createPdfFromImages(uris: List<Uri>): File {
        val fileName = "document_${System.currentTimeMillis()}.pdf"
        val pdfFile = File(context.cacheDir, fileName)

        try {
            PdfWriter(pdfFile).use { writer ->
                PdfDocument(writer).use { pdfDoc ->
                    ITextDocument(pdfDoc).use { document ->
                        uris.forEachIndexed { index, uri ->
                            try {
                                val imageBytes = imageProcessor.getImageBytesFromUri(uri)
                                val imageData = ImageDataFactory.create(imageBytes)
                                val image = Image(imageData)

                                // Calculate page size based on image dimensions
                                val pageWidth = image.imageWidth
                                val pageHeight = image.imageHeight
                                val pageSize = PageSize(pageWidth, pageHeight)

                                // Add new page with image dimensions
                                pdfDoc.addNewPage(pageSize)

                                // Scale image to fit page
                                image.setFixedPosition(index + 1, 0f, 0f)
                                image.scaleToFit(pageWidth, pageHeight)

                                document.add(image)
                            } catch (e: Exception) {
                                // Log but continue with next image (partial PDF better than none)
                                android.util.Log.e("PdfGeneratorService", "Failed to add image ${index + 1}/${uris.size} to PDF: ${e.message}", e)
                                // If first image fails, rethrow (can't create empty PDF)
                                if (index == 0) {
                                    throw IllegalStateException(context.getString(R.string.error_first_image_process_failed), e)
                                }
                            }
                        }

                        // Verify we have at least one page
                        if (pdfDoc.numberOfPages == 0) {
                            throw IllegalStateException(context.getString(R.string.error_pdf_no_pages))
                        }
                    }
                }
            }

            // Verify PDF file was created and is not empty
            if (!pdfFile.exists() || pdfFile.length() == 0L) {
                throw IllegalStateException(context.getString(R.string.error_pdf_not_created))
            }

            return pdfFile
        } catch (e: Exception) {
            // Clean up partial file on error
            if (pdfFile.exists()) {
                pdfFile.delete()
            }
            // Re-throw with more context
            throw when (e) {
                is IllegalStateException -> e
                is IllegalArgumentException -> e
                else -> IllegalStateException(context.getString(R.string.error_pdf_creation_failed, e.message ?: ""), e)
            }
        }
    }
}
```

- [ ] **Step 2.3: Modify DocumentRepository.kt — add field, delegate, remove private method**

Open `app/src/main/java/com/paperless/scanner/data/repository/DocumentRepository.kt`.

(a) Add import:
```kotlin
import com.paperless.scanner.data.service.PdfGeneratorService
```

(b) Add `pdfGenerator: PdfGeneratorService` field to constructor (after `imageProcessor`):
```kotlin
class DocumentRepository @Inject constructor(
    // ... existing fields ...
    private val imageProcessor: ImageProcessorService,
    private val pdfGenerator: PdfGeneratorService    // NEW
) {
```

(c) Replace inline call inside `uploadMultiPageDocument` (the call site that previously read `createPdfFromImages(uris)`) with `pdfGenerator.createPdfFromImages(uris)`. Confirm the location with:
```bash
grep -n "createPdfFromImages" app/src/main/java/com/paperless/scanner/data/repository/DocumentRepository.kt
```

(d) Delete the entire `private fun createPdfFromImages(uris: List<Uri>): File { ... }` method from DocumentRepository.kt.

(e) Remove now-unused iText imports from DocumentRepository.kt:
```kotlin
import com.itextpdf.io.image.ImageDataFactory
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document as ITextDocument
import com.itextpdf.layout.element.Image
```
(Verify each is unused via grep before removing.)

- [ ] **Step 2.4: Modify AppModule.kt — add `pdfGenerator` parameter**

Open AppModule.kt, locate `provideDocumentRepository`, add the parameter:
```kotlin
fun provideDocumentRepository(
    // ... existing params ...
    imageProcessor: ImageProcessorService,
    pdfGenerator: PdfGeneratorService           // NEW
): DocumentRepository = DocumentRepository(
    /* existing args */,
    imageProcessor,
    pdfGenerator                                // NEW
)
```

Add import:
```kotlin
import com.paperless.scanner.data.service.PdfGeneratorService
```

- [ ] **Step 2.5: Compile**

Run: `./gradlew compileReleaseKotlin --no-daemon`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 2.6: Write the test file**

Create `app/src/test/java/com/paperless/scanner/data/service/PdfGeneratorServiceTest.kt`:

```kotlin
package com.paperless.scanner.data.service

import android.content.Context
import android.net.Uri
import com.paperless.scanner.R
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class PdfGeneratorServiceTest {

    private lateinit var context: Context
    private lateinit var imageProcessor: ImageProcessorService
    private lateinit var service: PdfGeneratorService
    private lateinit var tempCacheDir: File

    private val uri1: Uri = Uri.parse("content://test/page1.jpg")
    private val uri2: Uri = Uri.parse("content://test/page2.jpg")
    private val uri3: Uri = Uri.parse("content://test/page3.jpg")

    /**
     * Real 1×1 white JPEG bytes. Decodes successfully via iText's ImageDataFactory.
     * Generated once and inlined to keep tests hermetic.
     */
    private val realJpegBytes: ByteArray = byteArrayOf(
        0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x00, 0x10, 'J'.code.toByte(),
        'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0x00, 0x01, 0x01, 0x00,
        0x00, 0x01, 0x00, 0x01, 0x00, 0x00, 0xFF.toByte(), 0xDB.toByte(), 0x00, 0x43,
        0x00, 0x08, 0x06, 0x06, 0x07, 0x06, 0x05, 0x08, 0x07, 0x07, 0x07, 0x09,
        0x09, 0x08, 0x0A, 0x0C, 0x14, 0x0D, 0x0C, 0x0B, 0x0B, 0x0C, 0x19, 0x12,
        0x13, 0x0F, 0x14, 0x1D, 0x1A, 0x1F, 0x1E, 0x1D, 0x1A, 0x1C, 0x1C, 0x20,
        0x24, 0x2E, 0x27, 0x20, 0x22, 0x2C, 0x23, 0x1C, 0x1C, 0x28, 0x37, 0x29,
        0x2C, 0x30, 0x31, 0x34, 0x34, 0x34, 0x1F, 0x27, 0x39, 0x3D, 0x38, 0x32,
        0x3C, 0x2E, 0x33, 0x34, 0x32, 0xFF.toByte(), 0xC0.toByte(), 0x00, 0x0B,
        0x08, 0x00, 0x01, 0x00, 0x01, 0x01, 0x01, 0x11, 0x00, 0xFF.toByte(),
        0xC4.toByte(), 0x00, 0x1F, 0x00, 0x00, 0x01, 0x05, 0x01, 0x01, 0x01,
        0x01, 0x01, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
        0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B,
        0xFF.toByte(), 0xC4.toByte(), 0x00, 0xB5.toByte(), 0x10, 0x00, 0x02,
        0x01, 0x03, 0x03, 0x02, 0x04, 0x03, 0x05, 0x05, 0x04, 0x04, 0x00, 0x00,
        0x01, 0x7D, 0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21,
        0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07, 0x22, 0x71, 0x14, 0x32,
        0x81.toByte(), 0x91.toByte(), 0xA1.toByte(), 0x08, 0x23, 0x42,
        0xB1.toByte(), 0xC1.toByte(), 0x15, 0x52, 0xD1.toByte(), 0xF0.toByte(),
        0x24, 0x33, 0x62, 0x72, 0x82.toByte(), 0xFF.toByte(), 0xDA.toByte(),
        0x00, 0x08, 0x01, 0x01, 0x00, 0x00, 0x3F, 0x00, 0xFB.toByte(),
        0xD0.toByte(), 0xFF.toByte(), 0xD9.toByte()
    )

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        imageProcessor = mockk()
        tempCacheDir = createTempDir(prefix = "pdfgen-test")

        every { context.cacheDir } returns tempCacheDir
        every { context.getString(R.string.error_first_image_process_failed) } returns "first_image_failed"
        every { context.getString(R.string.error_pdf_no_pages) } returns "no_pages"
        every { context.getString(R.string.error_pdf_not_created) } returns "pdf_not_created"
        every { context.getString(R.string.error_pdf_creation_failed, any<String>()) } returns "pdf_creation_failed"

        service = PdfGeneratorService(context, imageProcessor)
    }

    @After
    fun teardown() {
        tempCacheDir.deleteRecursively()
    }

    @Test
    fun `creates PDF from a single image successfully`() {
        every { imageProcessor.getImageBytesFromUri(uri1) } returns realJpegBytes

        val pdf = service.createPdfFromImages(listOf(uri1))

        assertTrue(pdf.exists())
        assertTrue(pdf.length() > 0)
        assertTrue(pdf.name.endsWith(".pdf"))
    }

    @Test
    fun `creates PDF from three images successfully`() {
        every { imageProcessor.getImageBytesFromUri(any()) } returns realJpegBytes

        val pdf = service.createPdfFromImages(listOf(uri1, uri2, uri3))

        assertTrue(pdf.exists())
        assertTrue(pdf.length() > 0)
    }

    @Test
    fun `rethrows IllegalStateException when first image fails`() {
        every { imageProcessor.getImageBytesFromUri(uri1) } throws RuntimeException("boom")

        assertThrows(IllegalStateException::class.java) {
            service.createPdfFromImages(listOf(uri1))
        }
    }

    @Test
    fun `skips failing non-first page and produces partial PDF`() {
        every { imageProcessor.getImageBytesFromUri(uri1) } returns realJpegBytes
        every { imageProcessor.getImageBytesFromUri(uri2) } throws RuntimeException("page-2-bad")
        every { imageProcessor.getImageBytesFromUri(uri3) } returns realJpegBytes

        val pdf = service.createPdfFromImages(listOf(uri1, uri2, uri3))

        assertTrue(pdf.exists())
        assertTrue(pdf.length() > 0)
    }

    @Test
    fun `throws IllegalStateException when uri list is empty`() {
        assertThrows(IllegalStateException::class.java) {
            service.createPdfFromImages(emptyList())
        }
    }

    @Test
    fun `cleans up partial PDF file when first image fails`() {
        every { imageProcessor.getImageBytesFromUri(uri1) } throws RuntimeException("boom")

        runCatching { service.createPdfFromImages(listOf(uri1)) }

        // No leftover PDF file in cacheDir
        val leftovers = tempCacheDir.listFiles { f -> f.name.endsWith(".pdf") } ?: emptyArray()
        assertTrue("Partial PDF should be cleaned up", leftovers.isEmpty())
    }
}
```

- [ ] **Step 2.7: Run new tests**

Run: `./gradlew testReleaseUnitTest --tests "com.paperless.scanner.data.service.PdfGeneratorServiceTest" --no-daemon`
Expected: All 6 tests pass.

If iText complains about missing native libs or font resources in Robolectric, you may need to add to `app/build.gradle.kts` testOptions:
```kotlin
testOptions {
    unitTests {
        isReturnDefaultValues = true
        isIncludeAndroidResources = true
    }
}
```
(Check before assuming — may already be set.)

- [ ] **Step 2.8: Run existing DocumentRepositoryTest**

Run: `./gradlew testReleaseUnitTest --tests "com.paperless.scanner.data.repository.DocumentRepositoryTest" --no-daemon`
Expected: green.

- [ ] **Step 2.9: Run validate-ci.sh**

Run: `./scripts/validate-ci.sh`
Expected: green.

- [ ] **Step 2.10: Commit**

```bash
git add \
  app/src/main/java/com/paperless/scanner/data/service/PdfGeneratorService.kt \
  app/src/main/java/com/paperless/scanner/data/repository/DocumentRepository.kt \
  app/src/main/java/com/paperless/scanner/di/AppModule.kt \
  app/src/test/java/com/paperless/scanner/data/service/PdfGeneratorServiceTest.kt

git commit -m "$(cat <<'EOF'
refactor(repository): extract PdfGeneratorService from DocumentRepository

Phase 1.2 of #51 — moves createPdfFromImages from DocumentRepository.kt
into a new data/service/PdfGeneratorService.kt that consumes
ImageProcessorService for image-byte loading. DocumentRepository
delegates through a new injected field; AppModule.provideDocumentRepository
gains the pdfGenerator parameter.

- DocumentRepository.kt reduced by another ~70 LOC
- New PdfGeneratorServiceTest.kt: 6 Robolectric+mockk cases covering
  single+multi-image happy paths, page-0 rethrow, page-N skip,
  empty-list rejection, and cleanup on failure
- Existing DocumentRepositoryTest.kt remains green

Closes #<ISSUE_B>
Sub of #51
EOF
)"
```

- [ ] **Step 2.11: Push and open PR**

```bash
git push -u origin refactor/51-extract-pdf-generator

gh pr create \
  --repo napoleonmm83/paperless-scanner \
  --base main \
  --head refactor/51-extract-pdf-generator \
  --title "refactor: extract PdfGeneratorService (Phase 1.2 of #51)" \
  --body "$(cat <<'EOF'
## Summary
- Extracts createPdfFromImages from DocumentRepository.kt into a new `PdfGeneratorService` at `data/service/`.
- Service depends on ImageProcessorService (merged in Phase 1.1).
- DocumentRepository delegates through a new `pdfGenerator: PdfGeneratorService` field; AppModule wiring updated.
- Adds 6 Robolectric+mockk tests using a real-bytes JPEG sample.

## Closes
- Closes #<ISSUE_B>
- Sub of #51

## Test plan
- [x] `./gradlew testReleaseUnitTest --tests *PdfGeneratorServiceTest*` green
- [x] `./gradlew testReleaseUnitTest --tests *DocumentRepositoryTest*` green
- [x] `./scripts/validate-ci.sh` green
- [ ] Manual on-device: multi-page upload via Scan flow

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 2.12: Wait for CI + CodeRabbit, action findings, then merge**

Same as Step 1.13 / 1.14.

---

## Task 3: PR-3 — Extract DocumentSerializer

**Prerequisite:** Task 2 PR merged to main.

**Files:**
- Create: `app/src/main/java/com/paperless/scanner/data/service/DocumentSerializer.kt`
- Modify: `app/src/main/java/com/paperless/scanner/data/repository/DocumentRepository.kt`
- Modify: `app/src/main/java/com/paperless/scanner/di/AppModule.kt`
- Test: `app/src/test/java/com/paperless/scanner/data/service/DocumentSerializerTest.kt`

- [ ] **Step 3.1: Sync with main and create branch**

```bash
git checkout main
git pull --rebase
git checkout -b refactor/51-extract-document-serializer
```

- [ ] **Step 3.2: Create DocumentSerializer.kt**

Create file `app/src/main/java/com/paperless/scanner/data/service/DocumentSerializer.kt`:

```kotlin
package com.paperless.scanner.data.service

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Centralizes Gson-based JSON shape transforms used by DocumentRepository.
 * Extracted in issue #51 Phase 1.3.
 *
 * Contract:
 * - serializeCustomFieldsForUpload: returns null for null/empty input. Otherwise
 *   serializes a list of {field, value} maps as JSON wrapped in an
 *   application/json RequestBody.
 * - deserializeCachedTagIds: returns emptyList() for null or unparseable input;
 *   otherwise parses a Gson List<Int>.
 */
@Singleton
class DocumentSerializer @Inject constructor(
    private val gson: Gson
) {
    fun serializeCustomFieldsForUpload(customFields: Map<Int, Any>?): RequestBody? {
        if (customFields.isNullOrEmpty()) return null
        val customFieldsList = customFields.map { (fieldId, value) ->
            mapOf("field" to fieldId, "value" to value)
        }
        return gson.toJson(customFieldsList).toRequestBody("application/json".toMediaTypeOrNull())
    }

    fun deserializeCachedTagIds(cachedJson: String?): List<Int> {
        if (cachedJson.isNullOrBlank()) return emptyList()
        return try {
            val listType = object : TypeToken<List<Int>>() {}.type
            gson.fromJson<List<Int>>(cachedJson, listType) ?: emptyList()
        } catch (e: JsonSyntaxException) {
            emptyList()
        }
    }
}
```

- [ ] **Step 3.3: Modify DocumentRepository.kt — replace 3 inline gson uses**

Open `app/src/main/java/com/paperless/scanner/data/repository/DocumentRepository.kt`.

(a) Add import:
```kotlin
import com.paperless.scanner.data.service.DocumentSerializer
```

(b) Add field to constructor:
```kotlin
class DocumentRepository @Inject constructor(
    // ... existing fields ...
    private val pdfGenerator: PdfGeneratorService,
    private val serializer: DocumentSerializer       // NEW
) {
```

(c) Replace the first inline `gson.toJson(customFieldsList).toRequestBody(...)` site (around L113-115). Locate the surrounding code block:
```kotlin
val customFieldsBody = if (customFields != null && customFields.isNotEmpty()) {
    val customFieldsList = customFields.map { (fieldId, value) ->
        mapOf("field" to fieldId, "value" to value)
    }
    gson.toJson(customFieldsList).toRequestBody("application/json".toMediaTypeOrNull())
} else null
```
→ becomes:
```kotlin
val customFieldsBody = serializer.serializeCustomFieldsForUpload(customFields)
```

(d) Repeat for the second site (around L196-198) — same replacement.

(e) Replace the deserialize-tags site (around L883-884):
```kotlin
val listType = object : TypeToken<List<Int>>() {}.type
gson.fromJson(cached.tags, listType) ?: emptyList()
```
→ becomes:
```kotlin
serializer.deserializeCachedTagIds(cached.tags)
```

(f) Remove now-unused imports from DocumentRepository.kt (verify with grep first):
```kotlin
import com.google.gson.reflect.TypeToken
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
```

Note: `gson: Gson` constructor parameter MAY still be needed for other uses. Verify with:
```bash
grep -n "\bgson\b" app/src/main/java/com/paperless/scanner/data/repository/DocumentRepository.kt
```
If only the field declaration `private val gson: Gson` remains and no usages, you may remove the field + constructor param + AppModule param. **If unsure, leave it** — removing it is a follow-up cleanup, not blocking for this PR.

- [ ] **Step 3.4: Modify AppModule.kt — add `serializer` parameter**

```kotlin
fun provideDocumentRepository(
    // ... existing params ...
    pdfGenerator: PdfGeneratorService,
    serializer: DocumentSerializer              // NEW
): DocumentRepository = DocumentRepository(
    /* existing args */, pdfGenerator, serializer
)
```

Add import:
```kotlin
import com.paperless.scanner.data.service.DocumentSerializer
```

- [ ] **Step 3.5: Compile**

Run: `./gradlew compileReleaseKotlin --no-daemon`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3.6: Write the test file**

Create `app/src/test/java/com/paperless/scanner/data/service/DocumentSerializerTest.kt`:

```kotlin
package com.paperless.scanner.data.service

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import okio.Buffer

class DocumentSerializerTest {

    private lateinit var gson: Gson
    private lateinit var serializer: DocumentSerializer

    @Before
    fun setup() {
        gson = Gson()
        serializer = DocumentSerializer(gson)
    }

    // ---- serializeCustomFieldsForUpload ----

    @Test
    fun `serializeCustomFieldsForUpload returns null for null input`() {
        assertNull(serializer.serializeCustomFieldsForUpload(null))
    }

    @Test
    fun `serializeCustomFieldsForUpload returns null for empty map`() {
        assertNull(serializer.serializeCustomFieldsForUpload(emptyMap()))
    }

    @Test
    fun `serializeCustomFieldsForUpload returns RequestBody with single field as JSON list`() {
        val body = serializer.serializeCustomFieldsForUpload(mapOf(42 to "hello"))
        assertNotNull(body)
        val buffer = Buffer()
        body!!.writeTo(buffer)
        val json = buffer.readUtf8()
        assertEquals("""[{"field":42,"value":"hello"}]""", json)
        assertEquals("application/json", body.contentType()?.toString()?.substringBefore(";"))
    }

    @Test
    fun `serializeCustomFieldsForUpload preserves multiple entries`() {
        val body = serializer.serializeCustomFieldsForUpload(linkedMapOf(1 to "a", 2 to "b", 3 to 99))
        val buffer = Buffer()
        body!!.writeTo(buffer)
        val json = buffer.readUtf8()
        assertTrue(json.contains(""""field":1"""))
        assertTrue(json.contains(""""field":2"""))
        assertTrue(json.contains(""""field":3"""))
        assertTrue(json.contains(""""value":"a""""))
        assertTrue(json.contains(""""value":99"""))
    }

    // ---- deserializeCachedTagIds ----

    @Test
    fun `deserializeCachedTagIds returns empty list for null`() {
        assertEquals(emptyList<Int>(), serializer.deserializeCachedTagIds(null))
    }

    @Test
    fun `deserializeCachedTagIds returns empty list for blank input`() {
        assertEquals(emptyList<Int>(), serializer.deserializeCachedTagIds(""))
        assertEquals(emptyList<Int>(), serializer.deserializeCachedTagIds("   "))
    }

    @Test
    fun `deserializeCachedTagIds parses valid JSON int list`() {
        assertEquals(listOf(1, 2, 3), serializer.deserializeCachedTagIds("[1,2,3]"))
    }

    @Test
    fun `deserializeCachedTagIds returns empty list for malformed JSON`() {
        assertEquals(emptyList<Int>(), serializer.deserializeCachedTagIds("not json"))
        assertEquals(emptyList<Int>(), serializer.deserializeCachedTagIds("[1,2,"))
    }
}
```

- [ ] **Step 3.7: Run new tests**

Run: `./gradlew testReleaseUnitTest --tests "com.paperless.scanner.data.service.DocumentSerializerTest" --no-daemon`
Expected: All 8 tests pass.

- [ ] **Step 3.8: Run existing DocumentRepositoryTest**

Run: `./gradlew testReleaseUnitTest --tests "com.paperless.scanner.data.repository.DocumentRepositoryTest" --no-daemon`
Expected: green.

- [ ] **Step 3.9: Run validate-ci.sh**

Run: `./scripts/validate-ci.sh`
Expected: green.

- [ ] **Step 3.10: Commit**

```bash
git add \
  app/src/main/java/com/paperless/scanner/data/service/DocumentSerializer.kt \
  app/src/main/java/com/paperless/scanner/data/repository/DocumentRepository.kt \
  app/src/main/java/com/paperless/scanner/di/AppModule.kt \
  app/src/test/java/com/paperless/scanner/data/service/DocumentSerializerTest.kt

git commit -m "$(cat <<'EOF'
refactor(repository): extract DocumentSerializer from DocumentRepository

Phase 1.3 of #51 — centralizes Gson-based JSON shape transforms
that were scattered (and partly duplicated) across DocumentRepository.kt.
A new data/service/DocumentSerializer exposes:
- serializeCustomFieldsForUpload(customFields): RequestBody?
- deserializeCachedTagIds(cachedJson): List<Int>

Replaces 2 duplicated custom-field upload sites and 1 cached-tag
deserialization. DocumentRepository delegates through a new injected
field; AppModule.provideDocumentRepository gains the serializer parameter.

- DocumentRepository.kt reduced by another ~25 LOC
- New DocumentSerializerTest.kt: 8 plain JUnit cases covering null/empty,
  single + multi-field shape, RequestBody content-type, malformed JSON
  graceful fallback
- Existing DocumentRepositoryTest.kt remains green

Closes #<ISSUE_C>
Sub of #51
EOF
)"
```

- [ ] **Step 3.11: Push and open PR**

```bash
git push -u origin refactor/51-extract-document-serializer

gh pr create \
  --repo napoleonmm83/paperless-scanner \
  --base main \
  --head refactor/51-extract-document-serializer \
  --title "refactor: extract DocumentSerializer (Phase 1.3 of #51)" \
  --body "$(cat <<'EOF'
## Summary
- Extracts 3 Gson use-sites from DocumentRepository.kt into a new `DocumentSerializer` at `data/service/`.
- Bonus: deduplicates 2 identical custom-field serialization call-sites.
- Adds 8 plain-JUnit tests covering happy + error paths.

## Closes
- Closes #<ISSUE_C>
- Sub of #51

## Test plan
- [x] `./gradlew testReleaseUnitTest --tests *DocumentSerializerTest*` green
- [x] `./gradlew testReleaseUnitTest --tests *DocumentRepositoryTest*` green
- [x] `./scripts/validate-ci.sh` green

🤖 Generated with [Claude Code](https://claude.com/claude-code)
EOF
)"
```

- [ ] **Step 3.12: Wait for CI + CodeRabbit, action findings, then merge**

Same as before. After merge, **Phase 1 of #51 is complete**.

---

## Task 4: Update Parent Issue #51

**Files:** none (GitHub state only)

- [ ] **Step 4.1: Verify all 3 sub-issues are closed**

```bash
gh issue view <ISSUE_A> --repo napoleonmm83/paperless-scanner --json state -q .state
gh issue view <ISSUE_B> --repo napoleonmm83/paperless-scanner --json state -q .state
gh issue view <ISSUE_C> --repo napoleonmm83/paperless-scanner --json state -q .state
```
Expected: each prints `CLOSED`.

- [ ] **Step 4.2: Comment on parent #51 with Phase 1 completion summary**

```bash
gh issue comment 51 --repo napoleonmm83/paperless-scanner --body "$(cat <<'EOF'
**Phase 1 complete** ✅

3 services extracted into `app/src/main/java/com/paperless/scanner/data/service/`:
- ImageProcessorService (sub #<ISSUE_A>) — 10 tests
- PdfGeneratorService (sub #<ISSUE_B>) — 6 tests
- DocumentSerializer (sub #<ISSUE_C>) — 8 tests

`DocumentRepository.kt` reduced by ~200 LOC (1405 → ~1205).

**Next:** Phase 2 — DocumentList/Count/Metadata Repositories. New design spec required (A/B validation against live server). See `docs/code-reviews/REFACTOR_DOCUMENT_REPOSITORY.md` for full plan.
EOF
)"
```

- [ ] **Step 4.3: Tick the Phase-1 checkboxes in #51's body**

Edit issue #51 body via `gh issue edit 51 --body "..."`, marking the 3 Phase-1 checkboxes as `- [x]`. Phases 2-5 stay unchecked.

---

## Self-Review (after plan completion)

- **Spec coverage:** Each spec section is implemented:
  - Sec 2 (sub-issue structure) → Task 0
  - Sec 3 (Phase 1 architecture) → Tasks 1-3
  - Sec 4 (PR sequence) → Task 1 → 2 → 3 with prerequisite gates
  - Sec 5 (testing strategy) → each task has its own test file step
  - Sec 6 (validation per PR) → each task runs `validate-ci.sh`
  - Sec 7 (risks) → manual on-device smoke listed in PR test plans
  - Sec 8 (rollback) → simple `git revert` documented in spec
  - Sec 9 (out-of-scope) → Task 0 stub creation only
  - Sec 10 (acceptance criteria) → Task 4 wraps up the parent issue

- **Placeholder scan:** Issue numbers `<ISSUE_A>`, `<ISSUE_B>`, `<ISSUE_C>` are intentional placeholders captured in Task 0 for substitution. No TBDs / TODOs.

- **Type consistency:** Parameter names match across spec, plan, and code blocks (`imageProcessor`, `pdfGenerator`, `serializer`). Test class names match the service class names with `Test` suffix.
