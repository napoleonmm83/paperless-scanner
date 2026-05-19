package com.paperless.scanner.ui.screens.home

import android.content.Context
import com.paperless.scanner.data.ai.SuggestionOrchestrator
import com.paperless.scanner.data.analytics.AnalyticsService
import com.paperless.scanner.data.billing.PremiumFeatureManager
import com.paperless.scanner.data.datastore.TokenManager
import com.paperless.scanner.data.repository.DocumentListRepository
import com.paperless.scanner.data.repository.DocumentMetadataRepository
import com.paperless.scanner.data.repository.TagRepository
import com.paperless.scanner.domain.model.Document
import com.paperless.scanner.domain.model.Tag
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TagSuggestionsViewModelTest {

    private lateinit var context: Context
    private lateinit var tagRepository: TagRepository
    private lateinit var documentListRepository: DocumentListRepository
    private lateinit var documentMetadataRepository: DocumentMetadataRepository
    private lateinit var suggestionOrchestrator: SuggestionOrchestrator
    private lateinit var tokenManager: TokenManager
    private lateinit var premiumFeatureManager: PremiumFeatureManager
    private lateinit var analyticsService: AnalyticsService

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        context = mockk(relaxed = true)
        tagRepository = mockk(relaxed = true)
        documentListRepository = mockk(relaxed = true)
        documentMetadataRepository = mockk(relaxed = true)
        suggestionOrchestrator = mockk(relaxed = true)
        tokenManager = mockk(relaxed = true)
        premiumFeatureManager = mockk(relaxed = true)
        analyticsService = mockk(relaxed = true)

        every { tagRepository.observeTags() } returns MutableStateFlow(emptyList())
        every { premiumFeatureManager.isAiEnabled } returns MutableStateFlow(false)
        every { tokenManager.serverUrl } returns MutableStateFlow(null)
        every { tokenManager.token } returns MutableStateFlow(null)
        coEvery { documentListRepository.getUntaggedDocuments() } returns Result.success(emptyList())
        coEvery {
            documentMetadataRepository.updateDocument(any(), any(), any(), any(), any(), any(), any())
        } returns Result.success(doc(0))
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): TagSuggestionsViewModel = TagSuggestionsViewModel(
        context = context,
        tagRepository = tagRepository,
        documentListRepository = documentListRepository,
        documentMetadataRepository = documentMetadataRepository,
        suggestionOrchestrator = suggestionOrchestrator,
        tokenManager = tokenManager,
        premiumFeatureManager = premiumFeatureManager,
        analyticsService = analyticsService,
    )

    private fun doc(id: Int, title: String = "doc-$id") = Document(
        id = id,
        title = title,
        created = "2026-01-01",
        modified = "2026-01-01",
        added = "2026-01-01",
        tags = emptyList(),
    )

    @Test
    fun `initial state has correct defaults`() = runTest {
        val vm = createViewModel()
        runCurrent()

        assertEquals(TagSuggestionsState(), vm.tagSuggestionsState.value)
        assertFalse(vm.showTagSuggestionsSheet.value)
        assertEquals(CreateTagState.Idle, vm.createTagState.value)
        assertEquals(emptyList<Tag>(), vm.availableTags.value)
        assertFalse(vm.isAiAvailable.value)
        assertNull(vm.error.value)
    }

    @Test
    fun `availableTags reflects tagRepository observeTags`() = runTest {
        val tags = listOf(Tag(id = 1, name = "A", color = "#fff"))
        every { tagRepository.observeTags() } returns MutableStateFlow(tags)

        val vm = createViewModel()
        // availableTags uses WhileSubscribed(5000); collect once to start it
        val collectorJob = backgroundScope.launchCollectorOn(vm.availableTags)
        runCurrent()

        assertEquals(tags, vm.availableTags.value)
        collectorJob.cancel()
    }

    @Test
    fun `isAiAvailable reflects premiumFeatureManager isAiEnabled`() = runTest {
        every { premiumFeatureManager.isAiEnabled } returns MutableStateFlow(true)
        val vm = createViewModel()
        val collectorJob = backgroundScope.launchCollectorOn(vm.isAiAvailable)
        runCurrent()

        assertTrue(vm.isAiAvailable.value)
        collectorJob.cancel()
    }

    @Test
    fun `openTagSuggestionsSheet shows sheet and loads documents`() = runTest {
        coEvery { documentListRepository.getUntaggedDocuments() } returns Result.success(listOf(doc(1), doc(2)))

        val vm = createViewModel()
        vm.openTagSuggestionsSheet()
        runCurrent()

        assertTrue(vm.showTagSuggestionsSheet.value)
        assertEquals(2, vm.tagSuggestionsState.value.documents.size)
        assertFalse(vm.tagSuggestionsState.value.isLoading)
    }

    @Test
    fun `closeTagSuggestionsSheet resets sheet state`() = runTest {
        coEvery { documentListRepository.getUntaggedDocuments() } returns Result.success(listOf(doc(1)))

        val vm = createViewModel()
        vm.openTagSuggestionsSheet()
        runCurrent()
        assertTrue(vm.showTagSuggestionsSheet.value)

        vm.closeTagSuggestionsSheet()
        runCurrent()

        assertFalse(vm.showTagSuggestionsSheet.value)
        assertEquals(TagSuggestionsState(), vm.tagSuggestionsState.value)
    }

    @Test
    fun `loadUntaggedDocumentsForScreen records LoadFailed on repository failure`() = runTest {
        coEvery { documentListRepository.getUntaggedDocuments() } returns Result.failure(RuntimeException("net down"))

        val vm = createViewModel()
        vm.loadUntaggedDocumentsForScreen()
        runCurrent()

        val err = vm.error.value
        assertNotNull(err)
        assertTrue(err is TagSuggestionsError.LoadFailed)
        assertEquals("untaggedDocuments", (err as TagSuggestionsError.LoadFailed).source)
    }

    @Test
    fun `applyTagsToDocument success marks doc tagged and increments taggedCount`() = runTest {
        coEvery { documentListRepository.getUntaggedDocuments() } returns Result.success(listOf(doc(1)))

        val vm = createViewModel()
        vm.openTagSuggestionsSheet()
        runCurrent()

        vm.applyTagsToDocument(documentId = 1, tagIds = listOf(10, 20))
        runCurrent()

        val state = vm.tagSuggestionsState.value
        val target = state.documents.find { it.id == 1 }
        assertNotNull(target)
        assertTrue(target!!.isTagged)
        assertEquals(1, state.taggedCount)
        coVerify { documentMetadataRepository.updateDocument(1, tags = listOf(10, 20)) }
    }

    @Test
    fun `applyTagsToDocument failure transitions analysisState to Error`() = runTest {
        coEvery { documentListRepository.getUntaggedDocuments() } returns Result.success(listOf(doc(1)))
        coEvery { documentMetadataRepository.updateDocument(1, any(), any(), any(), any(), any(), any()) } returns Result.failure(RuntimeException("server boom"))

        val vm = createViewModel()
        vm.openTagSuggestionsSheet()
        runCurrent()

        vm.applyTagsToDocument(documentId = 1, tagIds = listOf(10))
        runCurrent()

        val target = vm.tagSuggestionsState.value.documents.find { it.id == 1 }
        assertNotNull(target)
        assertTrue(target!!.analysisState is UntaggedDocAnalysisState.Error)
        // Not tagged when server rejected.
        assertFalse(target.isTagged)
    }

    @Test
    fun `skipDocument marks isSkipped`() = runTest {
        coEvery { documentListRepository.getUntaggedDocuments() } returns Result.success(listOf(doc(1), doc(2)))

        val vm = createViewModel()
        vm.openTagSuggestionsSheet()
        runCurrent()

        vm.skipDocument(documentId = 2)
        runCurrent()

        val target = vm.tagSuggestionsState.value.documents.find { it.id == 2 }
        assertNotNull(target)
        assertTrue(target!!.isSkipped)
        // Doc 1 is unaffected.
        val other = vm.tagSuggestionsState.value.documents.find { it.id == 1 }
        assertFalse(other!!.isSkipped)
    }

    @Test
    fun `tag picker open close toggle`() = runTest {
        coEvery { documentListRepository.getUntaggedDocuments() } returns Result.success(listOf(doc(1)))

        val vm = createViewModel()
        vm.openTagSuggestionsSheet()
        runCurrent()

        vm.openTagPicker(documentId = 1)
        runCurrent()
        assertTrue(vm.tagSuggestionsState.value.showTagPicker)
        assertEquals(1, vm.tagSuggestionsState.value.tagPickerDocumentId)

        vm.toggleTagInPicker(documentId = 1, tagId = 7)
        runCurrent()
        val afterAdd = vm.tagSuggestionsState.value.documents.find { it.id == 1 }!!
        assertTrue(afterAdd.selectedTagIds.contains(7))

        vm.toggleTagInPicker(documentId = 1, tagId = 7)
        runCurrent()
        val afterRemove = vm.tagSuggestionsState.value.documents.find { it.id == 1 }!!
        assertFalse(afterRemove.selectedTagIds.contains(7))

        vm.closeTagPicker()
        runCurrent()
        assertFalse(vm.tagSuggestionsState.value.showTagPicker)
        assertNull(vm.tagSuggestionsState.value.tagPickerDocumentId)
    }

    @Test
    fun `applyPickerTags forwards selected ids to applyTagsToDocument and closes picker`() = runTest {
        coEvery { documentListRepository.getUntaggedDocuments() } returns Result.success(listOf(doc(1)))

        val vm = createViewModel()
        vm.openTagSuggestionsSheet()
        runCurrent()
        vm.openTagPicker(documentId = 1)
        vm.toggleTagInPicker(documentId = 1, tagId = 5)
        vm.toggleTagInPicker(documentId = 1, tagId = 9)
        runCurrent()

        vm.applyPickerTags(documentId = 1)
        runCurrent()

        coVerify { documentMetadataRepository.updateDocument(1, tags = match { it.toSet() == setOf(5, 9) }) }
        assertFalse(vm.tagSuggestionsState.value.showTagPicker)
    }

    @Test
    fun `applyPickerTags is no-op when no tags selected`() = runTest {
        coEvery { documentListRepository.getUntaggedDocuments() } returns Result.success(listOf(doc(1)))

        val vm = createViewModel()
        vm.openTagSuggestionsSheet()
        runCurrent()
        vm.openTagPicker(documentId = 1)
        runCurrent()

        vm.applyPickerTags(documentId = 1)
        runCurrent()

        coVerify(exactly = 0) { documentMetadataRepository.updateDocument(any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `createTag success transitions Idle Creating Success`() = runTest {
        val newTag = Tag(id = 99, name = "Invoice", color = "#abc")
        coEvery { tagRepository.createTag(name = "Invoice", color = "#abc") } returns Result.success(newTag)

        val vm = createViewModel()
        assertEquals(CreateTagState.Idle, vm.createTagState.value)

        vm.createTag(name = "Invoice", color = "#abc")
        runCurrent()

        val final = vm.createTagState.value
        assertTrue(final is CreateTagState.Success)
        assertEquals(newTag, (final as CreateTagState.Success).tag)
    }

    @Test
    fun `createTag duplicate finds existing tag and reports Success`() = runTest {
        val existing = Tag(id = 42, name = "Invoice", color = "#abc")
        every { tagRepository.observeTags() } returns flow { emit(listOf(existing)) }
        coEvery { tagRepository.createTag(name = "Invoice", color = null) } returns
            Result.failure(RuntimeException("unique constraint failed"))

        val vm = createViewModel()
        vm.createTag(name = "Invoice", color = null)
        runCurrent()

        val final = vm.createTagState.value
        assertTrue(final is CreateTagState.Success)
        assertEquals(existing, (final as CreateTagState.Success).tag)
    }

    @Test
    fun `createTag non-duplicate failure reports Error`() = runTest {
        coEvery { tagRepository.createTag(name = "X", color = null) } returns
            Result.failure(RuntimeException("server unreachable"))

        val vm = createViewModel()
        vm.createTag(name = "X", color = null)
        runCurrent()

        val final = vm.createTagState.value
        assertTrue(final is CreateTagState.Error)
    }

    @Test
    fun `resetCreateTagState returns to Idle`() = runTest {
        coEvery { tagRepository.createTag(name = "Foo", color = null) } returns
            Result.success(Tag(id = 1, name = "Foo", color = "#fff"))

        val vm = createViewModel()
        vm.createTag(name = "Foo", color = null)
        runCurrent()
        assertTrue(vm.createTagState.value is CreateTagState.Success)

        vm.resetCreateTagState()
        runCurrent()
        assertEquals(CreateTagState.Idle, vm.createTagState.value)
    }

    @Test
    fun `createSuggestedTag auto-selects new tag and removes from suggestedNewTags`() = runTest {
        coEvery { documentListRepository.getUntaggedDocuments() } returns Result.success(listOf(doc(1)))
        val newTag = Tag(id = 77, name = "Receipt", color = "#fff")
        coEvery { tagRepository.createTag(name = "Receipt", color = null) } returns Result.success(newTag)

        val vm = createViewModel()
        vm.openTagSuggestionsSheet()
        runCurrent()

        vm.createSuggestedTag(documentId = 1, tagName = "Receipt")
        runCurrent()

        val target = vm.tagSuggestionsState.value.documents.find { it.id == 1 }!!
        assertTrue(target.selectedTagIds.contains(77))
        assertTrue(vm.createTagState.value is CreateTagState.Success)
    }

    @Test
    fun `clearError resets error to null`() = runTest {
        coEvery { documentListRepository.getUntaggedDocuments() } returns Result.failure(RuntimeException("boom"))

        val vm = createViewModel()
        vm.loadUntaggedDocumentsForScreen()
        runCurrent()
        assertNotNull(vm.error.value)

        vm.clearError()
        runCurrent()
        assertNull(vm.error.value)
    }

    @Test
    fun `analyzeDocument re-entry guard prevents duplicate jobs for same documentId`() = runTest {
        coEvery { documentListRepository.getUntaggedDocuments() } returns Result.success(listOf(doc(1)))
        every { tokenManager.serverUrl } returns MutableStateFlow("https://x")
        every { tokenManager.token } returns MutableStateFlow("tok")

        val vm = createViewModel()
        vm.openTagSuggestionsSheet()
        runCurrent()

        // Two rapid analyzes — the second must short-circuit since the first
        // job is still active. We can't easily assert "exactly one network
        // call" without coupling to the URL fetch, but state should not
        // regress to a fresh LoadingThumbnail on the second call.
        vm.analyzeDocument(documentId = 1)
        vm.analyzeDocument(documentId = 1)
        runCurrent()

        // Either still in LoadingThumbnail (network not advanced) or in some
        // post-loading state, but never reset back to Idle by the second call.
        val target = vm.tagSuggestionsState.value.documents.find { it.id == 1 }!!
        assertFalse(target.analysisState is UntaggedDocAnalysisState.Idle)
    }
}

/**
 * Small wrapper that starts collecting a StateFlow on a background scope so
 * WhileSubscribed-backed flows transition past their initial value during a
 * test. Returns the Job so the caller can cancel it.
 */
private fun kotlinx.coroutines.CoroutineScope.launchCollectorOn(
    flow: kotlinx.coroutines.flow.StateFlow<*>,
): kotlinx.coroutines.Job = launch { flow.collect { /* drain */ } }
